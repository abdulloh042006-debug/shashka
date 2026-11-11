package uz.draughtsai

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Iterative-deepening Negamax search with alpha-beta pruning.
 *
 * Create one instance per search. The engine is deterministic for a given
 * position and limits, and it only asks RussianDraughtsRules for legal turns.
 */
class RussianDraughtsAi {
    private val transpositionTable = HashMap<Long, TranspositionEntry>(1 shl 15)
    private val killers = Array(MAX_PLY) { arrayOfNulls<DraughtsMove>(2) }
    private val history = IntArray(64 * 64)

    private var startedAtNanos = 0L
    private var timeLimitNanos = 0L
    private var nodeLimit = 0L
    private var visitedNodes = 0L
    private var quiescenceDepth = 0

    fun search(
        board: Board,
        limits: SearchLimits = SearchLimits()
    ): SearchResult {
        startedAtNanos = System.nanoTime()
        timeLimitNanos = limits.timeLimitMillis * NANOS_PER_MILLI
        nodeLimit = limits.maxNodes
        visitedNodes = 0L
        quiescenceDepth = limits.quiescenceDepth
        transpositionTable.clear()
        killers.forEach { it[0] = null; it[1] = null }
        history.fill(0)

        val rootMoves = RussianDraughtsRules.legalMoves(board)
        if (rootMoves.isEmpty()) {
            return result(
                bestMove = null,
                score = -MATE_SCORE,
                depth = 0,
                principalVariation = emptyList()
            )
        }

        var bestMove = orderMoves(board, rootMoves, null, 0).first()
        var bestScore = evaluateForSideToMove(board)
        var bestPv = listOf(bestMove)
        var completedDepth = 0

        for (depth in 1..limits.maxDepth) {
            try {
                val iteration = searchRoot(board, depth)
                bestMove = iteration.principalVariation.firstOrNull() ?: bestMove
                bestScore = iteration.score
                bestPv = iteration.principalVariation.ifEmpty { listOf(bestMove) }
                completedDepth = depth
                if (abs(bestScore) >= MATE_THRESHOLD) break
            } catch (_: SearchStopped) {
                break
            }
        }

        return result(bestMove, bestScore, completedDepth, bestPv)
    }

    private fun searchRoot(board: Board, depth: Int): NodeValue {
        checkStop()
        val key = PositionHasher.hash(board)
        val ttMove = transpositionTable[key]?.bestMove
        val moves = orderMoves(board, RussianDraughtsRules.legalMoves(board), ttMove, 0)
        var alpha = -INFINITY
        val beta = INFINITY
        var bestScore = -INFINITY
        var bestMove: DraughtsMove? = null
        var bestPv: List<DraughtsMove> = emptyList()

        for (move in moves) {
            checkStop()
            val child = RussianDraughtsRules.applyMove(board, move)
            val childValue = negamax(child, depth - 1, -beta, -alpha, 1)
            val score = -childValue.score
            if (score > bestScore) {
                bestScore = score
                bestMove = move
                bestPv = listOf(move) + childValue.principalVariation
            }
            alpha = max(alpha, score)
        }

        val chosen = bestMove ?: return NodeValue(-MATE_SCORE, emptyList())
        store(
            key = key,
            depth = depth,
            score = bestScore,
            ply = 0,
            bound = Bound.EXACT,
            bestMove = chosen
        )
        return NodeValue(bestScore, bestPv)
    }

    private fun negamax(
        board: Board,
        depth: Int,
        alphaInput: Int,
        betaInput: Int,
        ply: Int
    ): NodeValue {
        checkStop()
        visitedNodes++
        if (depth <= 0) {
            return quiescence(board, alphaInput, betaInput, ply, quiescenceDepth)
        }

        val key = PositionHasher.hash(board)
        val originalAlpha = alphaInput
        val originalBeta = betaInput
        var alpha = alphaInput
        var beta = betaInput
        val cached = transpositionTable[key]
        val ttMove = cached?.bestMove

        if (cached != null && cached.depth >= depth) {
            val cachedScore = scoreFromTable(cached.score, ply)
            when (cached.bound) {
                Bound.EXACT -> return NodeValue(cachedScore, emptyList())
                Bound.LOWER -> alpha = max(alpha, cachedScore)
                Bound.UPPER -> beta = min(beta, cachedScore)
            }
            if (alpha >= beta) return NodeValue(cachedScore, emptyList())
        }

        val moves = RussianDraughtsRules.legalMoves(board)
        if (moves.isEmpty()) {
            return NodeValue(-MATE_SCORE + ply, emptyList())
        }

        var bestScore = -INFINITY
        var bestMove: DraughtsMove? = null
        var bestPv: List<DraughtsMove> = emptyList()

        for (move in orderMoves(board, moves, ttMove, ply)) {
            checkStop()
            val child = RussianDraughtsRules.applyMove(board, move)
            val childValue = negamax(child, depth - 1, -beta, -alpha, ply + 1)
            val score = -childValue.score

            if (score > bestScore) {
                bestScore = score
                bestMove = move
                bestPv = listOf(move) + childValue.principalVariation
            }

            alpha = max(alpha, score)
            if (alpha >= beta) {
                if (!move.isCapture) rememberKiller(move, ply)
                history[move.from.index * 64 + move.to.index] = min(
                    1_000_000,
                    history[move.from.index * 64 + move.to.index] + depth * depth
                )
                break
            }
        }

        val chosen = bestMove
        if (chosen != null) {
            val bound = when {
                bestScore <= originalAlpha -> Bound.UPPER
                bestScore >= originalBeta -> Bound.LOWER
                else -> Bound.EXACT
            }
            store(key, depth, bestScore, ply, bound, chosen)
        }
        return NodeValue(bestScore, bestPv)
    }

    /**
     * Extends leaf nodes while a compulsory capture is available. Because a
     * complete multi-capture is represented as one DraughtsMove, this searches
     * complete turns rather than individual jumps.
     */
    private fun quiescence(
        board: Board,
        alphaInput: Int,
        betaInput: Int,
        ply: Int,
        remaining: Int
    ): NodeValue {
        checkStop()
        visitedNodes++

        val moves = RussianDraughtsRules.legalMoves(board)
        if (moves.isEmpty()) return NodeValue(-MATE_SCORE + ply, emptyList())

        val captures = moves.filter { it.isCapture }
        if (captures.isEmpty() || remaining <= 0) {
            return NodeValue(evaluateForSideToMove(board), emptyList())
        }

        var alpha = alphaInput
        var bestScore = -INFINITY
        var bestPv: List<DraughtsMove> = emptyList()

        for (move in orderMoves(board, captures, null, ply)) {
            checkStop()
            val child = RussianDraughtsRules.applyMove(board, move)
            val childValue = quiescence(child, -betaInput, -alpha, ply + 1, remaining - 1)
            val score = -childValue.score
            if (score > bestScore) {
                bestScore = score
                bestPv = listOf(move) + childValue.principalVariation
            }
            alpha = max(alpha, score)
            if (alpha >= betaInput) break
        }

        return NodeValue(bestScore, bestPv)
    }

    private fun orderMoves(
        board: Board,
        moves: List<DraughtsMove>,
        ttMove: DraughtsMove?,
        ply: Int
    ): List<DraughtsMove> = moves.sortedByDescending { move ->
        var score = 0
        if (move == ttMove) score += 1_000_000

        if (move.isCapture) {
            score += 100_000 + move.captured.size * 4_000
            score += move.captured.count { board[it].isKing } * 2_000
        } else if (ply < MAX_PLY) {
            if (move == killers[ply][0]) score += 30_000
            else if (move == killers[ply][1]) score += 20_000
            score += history[move.from.index * 64 + move.to.index]
        }

        val movingPiece = board[move.from]
        val promotionRow = if (movingPiece.side == Side.WHITE) 0 else 7
        if (!movingPiece.isKing && move.landings.any { it.row == promotionRow }) {
            score += 15_000
        }

        val destination = move.to
        score += 20 - abs(destination.row * 2 - 7) - abs(destination.col * 2 - 7)
        score
    }

    private fun rememberKiller(move: DraughtsMove, ply: Int) {
        if (ply !in 0 until MAX_PLY || killers[ply][0] == move) return
        killers[ply][1] = killers[ply][0]
        killers[ply][0] = move
    }

    private fun store(
        key: Long,
        depth: Int,
        score: Int,
        ply: Int,
        bound: Bound,
        bestMove: DraughtsMove
    ) {
        if (transpositionTable.size >= MAX_TABLE_ENTRIES) {
            transpositionTable.clear()
        }
        val existing = transpositionTable[key]
        if (existing == null || depth >= existing.depth || bound == Bound.EXACT) {
            transpositionTable[key] = TranspositionEntry(
                depth = depth,
                score = scoreToTable(score, ply),
                bound = bound,
                bestMove = bestMove
            )
        }
    }

    private fun checkStop() {
        if (Thread.currentThread().isInterrupted) throw SearchStopped
        if (visitedNodes >= nodeLimit) throw SearchStopped
        if (System.nanoTime() - startedAtNanos >= timeLimitNanos) throw SearchStopped
    }

    private fun evaluateForSideToMove(board: Board): Int {
        val whiteScore = evaluateWhite(board)
        return if (board.sideToMove == Side.WHITE) whiteScore else -whiteScore
    }

    private fun evaluateWhite(board: Board): Int {
        val totalPieces = board.cells.count { it != Piece.EMPTY }
        val endgameBonus = max(0, 12 - totalPieces) * 2
        var score = 0

        board.cells.forEachIndexed { index, piece ->
            if (piece == Piece.EMPTY) return@forEachIndexed
            val square = Square.fromIndex(index)
            val center = 6 - (abs(square.row * 2 - 7) + abs(square.col * 2 - 7)) / 2
            val value = if (piece.isKing) {
                330 + endgameBonus + center * 2
            } else {
                val progress = if (piece.side == Side.WHITE) 7 - square.row else square.row
                100 + progress * 4 + center
            }
            score += if (piece.side == Side.WHITE) value else -value
        }

        // A small tempo bonus helps the search distinguish otherwise equal moves.
        if (board.sideToMove == Side.WHITE) score += 4 else score -= 4
        return score
    }

    private fun scoreToTable(score: Int, ply: Int): Int = when {
        score >= MATE_THRESHOLD -> score + ply
        score <= -MATE_THRESHOLD -> score - ply
        else -> score
    }

    private fun scoreFromTable(score: Int, ply: Int): Int = when {
        score >= MATE_THRESHOLD -> score - ply
        score <= -MATE_THRESHOLD -> score + ply
        else -> score
    }

    private fun result(
        bestMove: DraughtsMove?,
        score: Int,
        depth: Int,
        principalVariation: List<DraughtsMove>
    ): SearchResult = SearchResult(
        bestMove = bestMove,
        score = score,
        completedDepth = depth,
        nodes = visitedNodes,
        elapsedMillis = (System.nanoTime() - startedAtNanos) / NANOS_PER_MILLI,
        principalVariation = principalVariation
    )

    private data class NodeValue(
        val score: Int,
        val principalVariation: List<DraughtsMove>
    )

    private data class TranspositionEntry(
        val depth: Int,
        val score: Int,
        val bound: Bound,
        val bestMove: DraughtsMove
    )

    private enum class Bound { EXACT, LOWER, UPPER }

    private object SearchStopped : RuntimeException()

    private companion object {
        const val INFINITY = 1_000_000
        const val MATE_SCORE = 100_000
        const val MATE_THRESHOLD = 90_000
        const val MAX_PLY = 64
        const val MAX_TABLE_ENTRIES = 180_000
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

data class SearchLimits(
    val maxDepth: Int = 8,
    val timeLimitMillis: Long = 1_500,
    val quiescenceDepth: Int = 10,
    val maxNodes: Long = 1_000_000
) {
    init {
        require(maxDepth in 1..64) { "maxDepth must be between 1 and 64." }
        require(timeLimitMillis in 1..120_000) { "timeLimitMillis must be between 1 and 120000." }
        require(quiescenceDepth in 0..32) { "quiescenceDepth must be between 0 and 32." }
        require(maxNodes > 0) { "maxNodes must be positive." }
    }
}

/**
 * Scores are centipawn-like heuristic units from the root side's perspective.
 * A mate score is near +/-100000. PV may be shorter when a transposition hit
 * supplies an exact score without a fully reconstructed continuation.
 */
data class SearchResult(
    val bestMove: DraughtsMove?,
    val score: Int,
    val completedDepth: Int,
    val nodes: Long,
    val elapsedMillis: Long,
    val principalVariation: List<DraughtsMove>
) {
    val hasMove: Boolean get() = bestMove != null
}

private object PositionHasher {
    private const val GOLDEN_GAMMA = -7046029254386353131L
    private val pieceKeys: Array<LongArray>
    private val sideKey: Long

    init {
        val keys = Array(64) { LongArray(Piece.values().size) }
        var state = 7640891576956012809L
        for (square in 0 until 64) {
            for (piece in Piece.values()) {
                state += GOLDEN_GAMMA
                keys[square][piece.ordinal] = mix64(state)
            }
        }
        state += GOLDEN_GAMMA
        pieceKeys = keys
        sideKey = mix64(state)
    }

    fun hash(board: Board): Long {
        var hash = if (board.sideToMove == Side.BLACK) sideKey else 0L
        board.cells.forEachIndexed { index, piece ->
            if (piece != Piece.EMPTY) hash = hash xor pieceKeys[index][piece.ordinal]
        }
        return hash
    }

    private fun mix64(input: Long): Long {
        var value = input
        value = (value xor (value ushr 30)) * -4658895280553007687L
        value = (value xor (value ushr 27)) * -7723592293110705685L
        return value xor (value ushr 31)
    }
}
