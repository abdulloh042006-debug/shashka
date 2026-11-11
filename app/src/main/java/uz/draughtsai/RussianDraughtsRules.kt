package uz.draughtsai

/**
 * Match status for a Russian Draughts board position.
 */
enum class GameStatus {
    ONGOING,
    WHITE_WINS,
    BLACK_WINS
}

/**
 * Deterministic, Android-independent rules engine implementing **Classic Russian Draughts**
 * (Русские шашки) according to the Russian Draughts Federation rules:
 *
 * 1. 8x8 board, 32 dark squares, 12 men per side, White moves first.
 * 2. Men move 1 square diagonally forward on quiet moves.
 * 3. Men capture adjacent enemy pieces in all 4 diagonal directions (including backwards).
 * 4. Capturing is strictly compulsory; quiet moves are illegal when any capture exists.
 * 5. Multi-captures must continue with the same piece until no further capture is available.
 * 6. Kings are flying kings: they move any unobstructed diagonal distance and, when capturing,
 *    may land on any empty square beyond the captured piece before the next occupied square
 *    (provided that if any landing square offers a continuation capture, the king must land on
 *    a square that continues the capture).
 * 7. Turkish strike (Турецкий удар): captured pieces remain on the board until the entire capture
 *    sequence finishes. They block diagonal rays and cannot be captured a second time.
 * 8. Mid-capture promotion: a Man reaching the promotion row during a capture immediately
 *    becomes a King and continues the same turn using flying-king capture rules.
 * 9. Free capture choice: when multiple complete capture sequences exist, the player may choose
 *    any of them (no mandatory maximum-capture restriction).
 */
object RussianDraughtsRules {

    private val DIAGONAL_DIRECTIONS = arrayOf(
        -1 to -1,
        -1 to 1,
        1 to -1,
        1 to 1
    )

    /**
     * Generates all complete legal turns for `board.sideToMove`.
     */
    fun legalMoves(board: Board): List<Move> {
        val side = board.sideToMove
        val captureMoves = mutableListOf<Move>()

        for (pos in Pos.PLAYABLE_SQUARES) {
            val piece = board[pos] ?: continue
            if (piece.side != side) continue
            captureMoves.addAll(generateCapturesForPiece(board, pos, piece))
        }

        // Compulsory capture rule: if any capture exists, quiet moves are illegal.
        if (captureMoves.isNotEmpty()) {
            return captureMoves
        }

        val quietMoves = mutableListOf<Move>()
        for (pos in Pos.PLAYABLE_SQUARES) {
            val piece = board[pos] ?: continue
            if (piece.side != side) continue
            quietMoves.addAll(generateQuietMovesForPiece(board, pos, piece))
        }
        return quietMoves
    }

    /**
     * Returns all legal moves starting from [from] in the given [board].
     * Respects global compulsory capture (returns empty if another piece must capture instead).
     */
    fun legalMovesFrom(board: Board, from: Pos): List<Move> {
        return legalMoves(board).filter { it.from == from }
    }

    /**
     * Returns true if [side] has at least one capture available on [board].
     */
    fun hasCapture(board: Board, side: Side = board.sideToMove): Boolean {
        val targetBoard = board.withSideToMove(side)
        for (pos in Pos.PLAYABLE_SQUARES) {
            val piece = targetBoard[pos] ?: continue
            if (piece.side != side) continue
            if (generateCapturesForPiece(targetBoard, pos, piece).isNotEmpty()) {
                return true
            }
        }
        return false
    }

    /**
     * Checks whether [move] is a complete, legal move in [board].
     * Rejects incomplete multi-captures, quiet moves when a capture exists, and illegal paths.
     */
    fun isLegalMove(board: Board, move: Move): Boolean {
        return legalMoves(board).any { legal ->
            legal.path == move.path && legal.captured == move.captured
        }
    }

    /**
     * Applies a verified legal [move] to [board], removing all captured pieces at the end
     * of the sequence, crowning promoted pieces, and switching `sideToMove`.
     */
    fun applyMove(board: Board, move: Move): Board {
        val matchedMove = legalMoves(board).firstOrNull { legal ->
            legal.path == move.path && legal.captured == move.captured
        } ?: throw IllegalArgumentException("Illegal or incomplete move: $move for board:\n$board")

        val movingPiece = board[matchedMove.from]
            ?: throw IllegalStateException("No piece at move start ${matchedMove.from}")

        var nextBoard = board.removePiece(matchedMove.from)

        // Remove all captured enemy pieces after the entire sequence is completed.
        for (victimPos in matchedMove.captured) {
            nextBoard = nextBoard.removePiece(victimPos)
        }

        val finalPiece = if (matchedMove.promoted || matchedMove.to.row == movingPiece.side.promotionRow) {
            movingPiece.promote()
        } else {
            movingPiece
        }

        nextBoard = nextBoard
            .setPiece(matchedMove.to, finalPiece)
            .withSideToMove(board.sideToMove.opposite())

        return nextBoard
    }

    /**
     * Evaluates whether the game is ongoing or won by one side (opponent has no pieces or no legal moves).
     */
    fun gameStatus(board: Board): GameStatus {
        val moves = legalMoves(board)
        if (moves.isEmpty()) {
            return if (board.sideToMove == Side.WHITE) GameStatus.BLACK_WINS else GameStatus.WHITE_WINS
        }
        return GameStatus.ONGOING
    }

    // -------------------------------------------------------------------------
    // Internal Quiet Move Generation
    // -------------------------------------------------------------------------

    private fun generateQuietMovesForPiece(board: Board, from: Pos, piece: Piece): List<Move> {
        return when (piece.kind) {
            PieceKind.MAN -> generateManQuietMoves(board, from, piece.side)
            PieceKind.KING -> generateKingQuietMoves(board, from, piece.side)
        }
    }

    private fun generateManQuietMoves(board: Board, from: Pos, side: Side): List<Move> {
        val moves = mutableListOf<Move>()
        val nextRow = from.row + side.forwardRowDelta
        for (dCol in intArrayOf(-1, 1)) {
            val target = Pos(nextRow, from.col + dCol)
            if (target.isInside && board[target] == null) {
                val promoted = target.row == side.promotionRow
                moves.add(
                    Move(
                        path = listOf(from, target),
                        captured = emptyList(),
                        promoted = promoted
                    )
                )
            }
        }
        return moves
    }

    private fun generateKingQuietMoves(board: Board, from: Pos, side: Side): List<Move> {
        val moves = mutableListOf<Move>()
        for ((dRow, dCol) in DIAGONAL_DIRECTIONS) {
            var r = from.row + dRow
            var c = from.col + dCol
            while (r in 0..7 && c in 0..7) {
                val target = Pos(r, c)
                if (board[target] != null) break
                moves.add(
                    Move(
                        path = listOf(from, target),
                        captured = emptyList(),
                        promoted = false
                    )
                )
                r += dRow
                c += dCol
            }
        }
        return moves
    }

    // -------------------------------------------------------------------------
    // Internal Capture & Multi-Capture Generation (Turkish Strike + Mid-Promotion)
    // -------------------------------------------------------------------------

    private fun generateCapturesForPiece(board: Board, startPos: Pos, startPiece: Piece): List<Move> {
        val results = mutableListOf<Move>()
        expandCaptureSequence(
            initialBoard = board,
            initialFrom = startPos,
            side = startPiece.side,
            currentPos = startPos,
            currentKind = startPiece.kind,
            pathSoFar = listOf(startPos),
            capturedSoFar = emptyList(),
            capturedSet = emptySet(),
            promotedSoFar = false,
            results = results
        )
        return results
    }

    /**
     * Checks whether [pos] is occupied during a capture sequence.
     * Note:
     * - [initialFrom] was vacated when the moving piece started its move.
     * - Pieces in [capturedSet] remain physically on the board until the entire capture
     *   sequence ends (Turkish strike), so their squares are STILL occupied and block rays!
     */
    private fun isSquareOccupiedDuringCapture(
        initialBoard: Board,
        initialFrom: Pos,
        pos: Pos
    ): Boolean {
        if (pos == initialFrom) return false
        return initialBoard[pos] != null
    }

    private fun expandCaptureSequence(
        initialBoard: Board,
        initialFrom: Pos,
        side: Side,
        currentPos: Pos,
        currentKind: PieceKind,
        pathSoFar: List<Pos>,
        capturedSoFar: List<Pos>,
        capturedSet: Set<Pos>,
        promotedSoFar: Boolean,
        results: MutableList<Move>
    ) {
        when (currentKind) {
            PieceKind.MAN -> {
                var foundAnyStep = false
                for ((dRow, dCol) in DIAGONAL_DIRECTIONS) {
                    val victimPos = Pos(currentPos.row + dRow, currentPos.col + dCol)
                    val landingPos = Pos(currentPos.row + 2 * dRow, currentPos.col + 2 * dCol)

                    if (!landingPos.isInside) continue
                    if (victimPos in capturedSet) continue

                    val victimPiece = if (victimPos == initialFrom) null else initialBoard[victimPos]
                    if (victimPiece == null || victimPiece.side == side) continue
                    if (isSquareOccupiedDuringCapture(initialBoard, initialFrom, landingPos)) continue

                    foundAnyStep = true
                    val crownsNow = landingPos.row == side.promotionRow
                    val nextKind = if (crownsNow) PieceKind.KING else PieceKind.MAN
                    val nextPromoted = promotedSoFar || crownsNow

                    expandCaptureSequence(
                        initialBoard = initialBoard,
                        initialFrom = initialFrom,
                        side = side,
                        currentPos = landingPos,
                        currentKind = nextKind,
                        pathSoFar = pathSoFar + landingPos,
                        capturedSoFar = capturedSoFar + victimPos,
                        capturedSet = capturedSet + victimPos,
                        promotedSoFar = nextPromoted,
                        results = results
                    )
                }

                if (!foundAnyStep && capturedSoFar.isNotEmpty()) {
                    results.add(
                        Move(
                            path = pathSoFar,
                            captured = capturedSoFar,
                            promoted = promotedSoFar
                        )
                    )
                }
            }

            PieceKind.KING -> {
                var foundAnyDirectionCapture = false

                for ((dRow, dCol) in DIAGONAL_DIRECTIONS) {
                    var r = currentPos.row + dRow
                    var c = currentPos.col + dCol

                    // 1. Scan empty squares before the first occupied square on this ray
                    while (r in 0..7 && c in 0..7) {
                        val scanPos = Pos(r, c)
                        if (isSquareOccupiedDuringCapture(initialBoard, initialFrom, scanPos)) {
                            break
                        }
                        r += dRow
                        c += dCol
                    }

                    if (r !in 0..7 || c !in 0..7) continue
                    val victimPos = Pos(r, c)

                    // Turkish strike: an already-captured piece still sits on the board and blocks the ray
                    if (victimPos in capturedSet) continue

                    val victimPiece = if (victimPos == initialFrom) null else initialBoard[victimPos]
                    if (victimPiece == null || victimPiece.side == side) continue

                    // 2. Collect all unobstructed empty landing squares beyond victimPos
                    val candidateLandings = mutableListOf<Pos>()
                    var lr = victimPos.row + dRow
                    var lc = victimPos.col + dCol
                    while (lr in 0..7 && lc in 0..7) {
                        val landPos = Pos(lr, lc)
                        if (isSquareOccupiedDuringCapture(initialBoard, initialFrom, landPos)) {
                            break
                        }
                        candidateLandings.add(landPos)
                        lr += dRow
                        lc += dCol
                    }

                    if (candidateLandings.isEmpty()) continue

                    foundAnyDirectionCapture = true
                    val nextCapturedList = capturedSoFar + victimPos
                    val nextCapturedSet = capturedSet + victimPos

                    // Russian Draughts Rule 6.1.5:
                    // If at least one landing square behind the captured piece allows a further capture,
                    // the king MUST land on a square from which continuation is possible.
                    // Otherwise, if no landing square allows a continuation, the king may stop on any of them.
                    val landingsWithContinuation = candidateLandings.filter { landing ->
                        kingHasFurtherCaptureFrom(
                            initialBoard = initialBoard,
                            initialFrom = initialFrom,
                            side = side,
                            fromPos = landing,
                            capturedSet = nextCapturedSet
                        )
                    }

                    if (landingsWithContinuation.isNotEmpty()) {
                        for (landing in landingsWithContinuation) {
                            expandCaptureSequence(
                                initialBoard = initialBoard,
                                initialFrom = initialFrom,
                                side = side,
                                currentPos = landing,
                                currentKind = PieceKind.KING,
                                pathSoFar = pathSoFar + landing,
                                capturedSoFar = nextCapturedList,
                                capturedSet = nextCapturedSet,
                                promotedSoFar = promotedSoFar,
                                results = results
                            )
                        }
                    } else {
                        for (landing in candidateLandings) {
                            results.add(
                                Move(
                                    path = pathSoFar + landing,
                                    captured = nextCapturedList,
                                    promoted = promotedSoFar
                                )
                            )
                        }
                    }
                }

                if (!foundAnyDirectionCapture && capturedSoFar.isNotEmpty()) {
                    results.add(
                        Move(
                            path = pathSoFar,
                            captured = capturedSoFar,
                            promoted = promotedSoFar
                        )
                    )
                }
            }
        }
    }

    private fun kingHasFurtherCaptureFrom(
        initialBoard: Board,
        initialFrom: Pos,
        side: Side,
        fromPos: Pos,
        capturedSet: Set<Pos>
    ): Boolean {
        for ((dRow, dCol) in DIAGONAL_DIRECTIONS) {
            var r = fromPos.row + dRow
            var c = fromPos.col + dCol
            while (r in 0..7 && c in 0..7) {
                val scanPos = Pos(r, c)
                if (isSquareOccupiedDuringCapture(initialBoard, initialFrom, scanPos)) {
                    break
                }
                r += dRow
                c += dCol
            }
            if (r !in 0..7 || c !in 0..7) continue
            val victimPos = Pos(r, c)
            if (victimPos in capturedSet) continue
            val victimPiece = if (victimPos == initialFrom) null else initialBoard[victimPos]
            if (victimPiece == null || victimPiece.side == side) continue

            val nextAfterVictim = Pos(victimPos.row + dRow, victimPos.col + dCol)
            if (nextAfterVictim.isInside &&
                !isSquareOccupiedDuringCapture(initialBoard, initialFrom, nextAfterVictim)
            ) {
                return true
            }
        }
        return false
    }
}
