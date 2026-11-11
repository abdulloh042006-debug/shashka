package uz.draughtsai

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Configuration parameters for the Russian Draughts AI search engine.
 */
data class SearchConfig(
    val maxDepth: Int = 7,
    val timeLimitMs: Long = 2_500L
)

/**
 * Detailed result returned by an AI search.
 */
data class SearchResult(
    val bestMove: Move?,
    val principalVariation: List<Move>,
    val score: Int,
    val completedDepth: Int,
    val nodesSearched: Long,
    val elapsedMs: Long
) {
    /** Formats the evaluation score in pawn/man units (e.g. "+1.25" or "-0.40"). */
    fun formattedScore(): String {
        if (abs(score) >= DraughtsAI.MATE_THRESHOLD) {
            return if (score > 0) "+WIN" else "-LOSS"
        }
        val value = score / 100.0
        val sign = if (value > 0) "+" else ""
        return String.format("%s%.2f", sign, value)
    }
}

/**
 * Local offline AI engine for Classic Russian Draughts using:
 * - Iterative Deepening Negamax with Alpha-Beta Pruning
 * - Zobrist Hashing & Transposition Table (EXACT, LOWER_BOUND, UPPER_BOUND)
 * - Principal Variation (PV) tracking and move ordering (TT move, captures, promotions, centrality)
 * - Domain-specific Russian Draughts static evaluation (material, flying kings, advancement,
 *   center control, back-rank "golden checker" protection, and mobility)
 */
class DraughtsAI(
    transpositionSizePowerOfTwo: Int = 16 // 65,536 entries
) {
    companion object {
        const val INF = 1_000_000
        const val WIN_SCORE = 100_000
        const val MATE_THRESHOLD = 90_000

        private const val MAN_VALUE = 100
        private const val KING_VALUE = 320

        private const val TT_EXACT: Byte = 0
        private const val TT_LOWER: Byte = 1
        private const val TT_UPPER: Byte = 2

        // Deterministic Zobrist keys for 64 squares x 4 piece states + sideToMove
        private val ZOBRIST_PIECES: Array<LongArray> = Array(64) { LongArray(4) }
        private val ZOBRIST_BLACK_TO_MOVE: Long

        init {
            val rng = Random(0x5255_53_44_52_41_55L) // Fixed seed for deterministic search
            for (sq in 0 until 64) {
                for (p in 0 until 4) {
                    ZOBRIST_PIECES[sq][p] = rng.nextLong()
                }
            }
            ZOBRIST_BLACK_TO_MOVE = rng.nextLong()
        }

        fun computeZobristHash(board: Board): Long {
            var h = 0L
            for (pos in Pos.PLAYABLE_SQUARES) {
                val piece = board[pos] ?: continue
                val sqIndex = (pos.row shl 3) or pos.col
                val pieceIndex = when (piece.side) {
                    Side.WHITE -> if (piece.isKing) 1 else 0
                    Side.BLACK -> if (piece.isKing) 3 else 2
                }
                h = h xor ZOBRIST_PIECES[sqIndex][pieceIndex]
            }
            if (board.sideToMove == Side.BLACK) {
                h = h xor ZOBRIST_BLACK_TO_MOVE
            }
            return h
        }
    }

    private class TTEntry(
        var key: Long = 0L,
        var depth: Int = -1,
        var score: Int = 0,
        var flag: Byte = TT_EXACT,
        var bestMove: Move? = null
    )

    private val ttMask: Int = (1 shl transpositionSizePowerOfTwo) - 1
    private val tt: Array<TTEntry> = Array(1 shl transpositionSizePowerOfTwo) { TTEntry() }

    /**
     * Searches the best legal move for `board.sideToMove`.
     * Can be cancelled cooperatively via [cancelFlag].
     */
    fun findBestMove(
        board: Board,
        config: SearchConfig = SearchConfig(),
        cancelFlag: AtomicBoolean = AtomicBoolean(false)
    ): SearchResult {
        val startTime = System.currentTimeMillis()
        val rootMoves = RussianDraughtsRules.legalMoves(board)

        if (rootMoves.isEmpty()) {
            return SearchResult(
                bestMove = null,
                principalVariation = emptyList(),
                score = -WIN_SCORE,
                completedDepth = 0,
                nodesSearched = 0L,
                elapsedMs = System.currentTimeMillis() - startTime
            )
        }

        // If only a single move is legal (e.g. forced single capture), return immediately
        if (rootMoves.size == 1) {
            val single = rootMoves.first()
            val after = RussianDraughtsRules.applyMove(board, single)
            val eval = -evaluateForSideToMove(after)
            return SearchResult(
                bestMove = single,
                principalVariation = listOf(single),
                score = eval,
                completedDepth = 1,
                nodesSearched = 1L,
                elapsedMs = max(1L, System.currentTimeMillis() - startTime)
            )
        }

        var bestMoveSoFar: Move = rootMoves.first()
        var bestScoreSoFar = evaluateForSideToMove(board)
        var bestPvSoFar: List<Move> = listOf(bestMoveSoFar)
        var completedDepth = 0
        var totalNodes = 0L
        val deadline = startTime + max(50L, config.timeLimitMs)

        val maxTargetDepth = config.maxDepth.coerceIn(1, 20)

        for (depth in 1..maxTargetDepth) {
            val context = SearchContext(
                deadlineMs = deadline,
                cancelFlag = cancelFlag,
                allowTimeoutAbort = depth > 1
            )

            val score = negamax(
                board = board,
                depth = depth,
                ply = 0,
                alphaInit = -INF,
                betaInit = INF,
                context = context
            )

            totalNodes += context.nodes

            if (context.aborted) {
                break
            }

            completedDepth = depth
            bestScoreSoFar = score
            val pv = extractPrincipalVariation(board, depth)
            if (pv.isNotEmpty()) {
                bestMoveSoFar = pv.first()
                bestPvSoFar = pv
            }

            // Stop early if a forced win/loss is already found
            if (abs(score) >= MATE_THRESHOLD) {
                break
            }

            if (System.currentTimeMillis() >= deadline) {
                break
            }
        }

        return SearchResult(
            bestMove = bestMoveSoFar,
            principalVariation = bestPvSoFar,
            score = bestScoreSoFar,
            completedDepth = max(1, completedDepth),
            nodesSearched = totalNodes,
            elapsedMs = max(1L, System.currentTimeMillis() - startTime)
        )
    }

    /**
     * Static evaluation from the perspective of `board.sideToMove`.
     * Positive values favor `board.sideToMove`, negative values favor the opponent.
     */
    fun evaluateForSideToMove(board: Board): Int {
        val whiteScore = evaluateWhitePerspective(board)
        return if (board.sideToMove == Side.WHITE) whiteScore else -whiteScore
    }

    /**
     * Static evaluation from White's perspective.
     */
    fun evaluateWhitePerspective(board: Board): Int {
        var whiteMaterial = 0
        var blackMaterial = 0
        var whitePositional = 0
        var blackPositional = 0

        for (pos in Pos.PLAYABLE_SQUARES) {
            val piece = board[pos] ?: continue
            val isCenterCol = pos.col in 2..5
            val isInnerCenter = pos.row in 2..5 && pos.col in 2..5

            if (piece.side == Side.WHITE) {
                if (piece.isKing) {
                    whiteMaterial += KING_VALUE
                    if (isInnerCenter) whitePositional += 18
                    // Main diagonal (a1-h8 has row + col == 7) control bonus for flying kings
                    if (pos.row + pos.col == 7) whitePositional += 14
                } else {
                    whiteMaterial += MAN_VALUE
                    // Advancement toward row 0 (rank 8)
                    val advancement = 7 - pos.row
                    whitePositional += advancement * 6
                    if (isCenterCol) whitePositional += 8
                    if (isInnerCenter) whitePositional += 6
                    // Back-rank defense ("golden checker" e1 is row=7, col=4; c1 is row=7, col=2)
                    if (pos.row == 7 && (pos.col == 2 || pos.col == 4)) {
                        whitePositional += 12
                    }
                }
            } else {
                if (piece.isKing) {
                    blackMaterial += KING_VALUE
                    if (isInnerCenter) blackPositional += 18
                    if (pos.row + pos.col == 7) blackPositional += 14
                } else {
                    blackMaterial += MAN_VALUE
                    // Advancement toward row 7 (rank 1)
                    val advancement = pos.row
                    blackPositional += advancement * 6
                    if (isCenterCol) blackPositional += 8
                    if (isInnerCenter) blackPositional += 6
                    // Back-rank defense ("golden checker" d8 is row=0, col=3; f8 is row=0, col=5)
                    if (pos.row == 0 && (pos.col == 3 || pos.col == 5)) {
                        blackPositional += 12
                    }
                }
            }
        }

        if (whiteMaterial == 0) return -WIN_SCORE
        if (blackMaterial == 0) return WIN_SCORE

        return (whiteMaterial - blackMaterial) + (whitePositional - blackPositional)
    }

    // -------------------------------------------------------------------------
    // Internal Negamax + Alpha-Beta + Quiescence Capture Extension
    // -------------------------------------------------------------------------

    private class SearchContext(
        val deadlineMs: Long,
        val cancelFlag: AtomicBoolean,
        val allowTimeoutAbort: Boolean
    ) {
        var nodes: Long = 0L
        var aborted: Boolean = false

        fun shouldAbort(): Boolean {
            if (aborted) return true
            if (cancelFlag.get()) {
                aborted = true
                return true
            }
            if (allowTimeoutAbort && (nodes and 255L) == 0L) {
                if (System.currentTimeMillis() >= deadlineMs) {
                    aborted = true
                    return true
                }
            }
            return false
        }
    }

    private fun negamax(
        board: Board,
        depth: Int,
        ply: Int,
        alphaInit: Int,
        betaInit: Int,
        context: SearchContext
    ): Int {
        context.nodes++
        if (context.shouldAbort()) return 0

        var alpha = alphaInit
        var beta = betaInit

        val hash = computeZobristHash(board)
        val ttSlot = tt[(hash.toInt() and Int.MAX_VALUE) and ttMask]
        var ttMove: Move? = null

        if (ttSlot.key == hash && ttSlot.depth >= depth && ply > 0) {
            val cachedScore = ttSlot.score
            when (ttSlot.flag) {
                TT_EXACT -> return cachedScore
                TT_LOWER -> alpha = max(alpha, cachedScore)
                TT_UPPER -> beta = min(beta, cachedScore)
            }
            if (alpha >= beta) {
                return cachedScore
            }
            ttMove = ttSlot.bestMove
        } else if (ttSlot.key == hash) {
            ttMove = ttSlot.bestMove
        }

        val legalMoves = RussianDraughtsRules.legalMoves(board)
        if (legalMoves.isEmpty()) {
            return -(WIN_SCORE - ply)
        }

        // At leaf depth, if current position has compulsory captures, extend until quiet
        if (depth <= 0) {
            if (legalMoves.first().isCapture && ply < 24) {
                var bestCapScore = -INF
                for (move in orderMoves(legalMoves, ttMove)) {
                    val child = RussianDraughtsRules.applyMove(board, move)
                    val score = -negamax(child, 0, ply + 1, -beta, -alpha, context)
                    if (context.aborted) return 0
                    if (score > bestCapScore) bestCapScore = score
                    if (score > alpha) alpha = score
                    if (alpha >= beta) break
                }
                return bestCapScore
            }
            return evaluateForSideToMove(board)
        }

        val orderedMoves = orderMoves(legalMoves, ttMove)
        var bestScore = -INF
        var bestMove: Move = orderedMoves.first()

        for (move in orderedMoves) {
            val nextBoard = RussianDraughtsRules.applyMove(board, move)
            val score = -negamax(
                board = nextBoard,
                depth = depth - 1,
                ply = ply + 1,
                alphaInit = -beta,
                betaInit = -alpha,
                context = context
            )

            if (context.aborted) return 0

            if (score > bestScore) {
                bestScore = score
                bestMove = move
            }
            if (score > alpha) {
                alpha = score
            }
            if (alpha >= beta) {
                break
            }
        }

        // Store in Transposition Table
        val flag = when {
            bestScore <= alphaInit -> TT_UPPER
            bestScore >= betaInit -> TT_LOWER
            else -> TT_EXACT
        }
        ttSlot.key = hash
        ttSlot.depth = depth
        ttSlot.score = bestScore
        ttSlot.flag = flag
        ttSlot.bestMove = bestMove

        return bestScore
    }

    private fun orderMoves(moves: List<Move>, ttMove: Move?): List<Move> {
        if (moves.size <= 1) return moves
        return moves.sortedByDescending { move ->
            var priority = 0
            if (ttMove != null && move.path == ttMove.path && move.captured == ttMove.captured) {
                priority += 100_000
            }
            if (move.isCapture) {
                priority += 10_000 + move.captureCount * 1_000
            }
            if (move.promoted) {
                priority += 5_000
            }
            // Prefer central landing squares
            if (move.to.col in 2..5 && move.to.row in 2..5) {
                priority += 50
            }
            priority
        }
    }

    private fun extractPrincipalVariation(rootBoard: Board, maxSteps: Int): List<Move> {
        val pv = mutableListOf<Move>()
        val visitedHashes = mutableSetOf<Long>()
        var current = rootBoard

        for (step in 0 until maxSteps) {
            val hash = computeZobristHash(current)
            if (!visitedHashes.add(hash)) break

            val slot = tt[(hash.toInt() and Int.MAX_VALUE) and ttMask]
            val candidate = slot.bestMove ?: break
            val legal = RussianDraughtsRules.legalMoves(current)
            val verified = legal.firstOrNull {
                it.path == candidate.path && it.captured == candidate.captured
            } ?: break

            pv.add(verified)
            current = RussianDraughtsRules.applyMove(current, verified)
        }

        if (pv.isEmpty()) {
            val fallback = RussianDraughtsRules.legalMoves(rootBoard).firstOrNull()
            if (fallback != null) pv.add(fallback)
        }
        return pv
    }

    fun clearTranspositionTable() {
        for (entry in tt) {
            entry.key = 0L
            entry.depth = -1
            entry.bestMove = null
        }
    }
}
