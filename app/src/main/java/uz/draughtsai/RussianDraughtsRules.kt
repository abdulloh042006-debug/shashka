package uz.draughtsai

/**
 * One complete turn. A capture move contains every landing square in order
 * and every captured square in the same order.
 */
data class DraughtsMove(
    val from: Square,
    val landings: List<Square>,
    val captured: List<Square> = emptyList()
) {
    init {
        require(landings.isNotEmpty()) { "A move must have at least one landing square." }
        require(captured.isEmpty() || captured.size == landings.size) {
            "Each capture landing must correspond to exactly one captured piece."
        }
    }

    val to: Square get() = landings.last()
    val isCapture: Boolean get() = captured.isNotEmpty()
}

private data class CaptureStep(
    val victim: Square,
    val landing: Square
)

/**
 * Classic Russian Draughts rules for an 8x8 board.
 *
 * Important implementation detail: captured pieces remain on the board until
 * the whole multiple-capture sequence is complete. They block lines and cannot
 * be captured a second time during the same move ("Turkish strike" rule).
 */
object RussianDraughtsRules {
    private val diagonals = listOf(
        -1 to -1,
        -1 to 1,
        1 to -1,
        1 to 1
    )

    /** Returns every legal complete move for the side to move. */
    fun legalMoves(board: Board): List<DraughtsMove> {
        val captures = mutableListOf<DraughtsMove>()

        for (index in board.cells.indices) {
            val piece = board.cells[index]
            if (piece.side != board.sideToMove) continue
            captures += captureMovesForPiece(board, Square.fromIndex(index), piece)
        }

        // Capturing is compulsory: quiet moves are not returned if any capture exists.
        if (captures.isNotEmpty()) return captures

        return quietMoves(board)
    }

    /** True only when the exact complete move is currently legal. */
    fun isLegalMove(board: Board, move: DraughtsMove): Boolean =
        move in legalMoves(board)

    /**
     * Applies a complete legal move and switches the side to move.
     * Editor repositioning should use Board.movePieceForEditing instead.
     */
    fun applyMove(board: Board, move: DraughtsMove): Board {
        require(isLegalMove(board, move)) { "The supplied move is not legal in this position." }

        val movingPiece = board[move.from]
        val updated = board.cells.toMutableList()
        updated[move.from.index] = Piece.EMPTY

        // Captured pieces are removed only after the complete sequence.
        move.captured.forEach { updated[it.index] = Piece.EMPTY }

        val promotionRow = if (movingPiece.side == Side.WHITE) 0 else 7
        val reachesPromotionRow = !movingPiece.isKing &&
            move.landings.any { it.row == promotionRow }

        val finalPiece = if (reachesPromotionRow) {
            if (movingPiece.side == Side.WHITE) Piece.WHITE_KING else Piece.BLACK_KING
        } else {
            movingPiece
        }

        updated[move.to.index] = finalPiece
        return Board(updated, board.sideToMove.opponent())
    }

    private fun quietMoves(board: Board): List<DraughtsMove> {
        val result = mutableListOf<DraughtsMove>()

        for (index in board.cells.indices) {
            val piece = board.cells[index]
            if (piece.side != board.sideToMove) continue

            val from = Square.fromIndex(index)
            if (piece.isKing) {
                for ((dr, dc) in diagonals) {
                    var row = from.row + dr
                    var col = from.col + dc
                    while (inside(row, col)) {
                        val target = Square(row, col)
                        if (board[target] != Piece.EMPTY) break
                        result += DraughtsMove(from, listOf(target))
                        row += dr
                        col += dc
                    }
                }
            } else {
                val forward = if (piece.side == Side.WHITE) -1 else 1
                for (dc in intArrayOf(-1, 1)) {
                    val row = from.row + forward
                    val col = from.col + dc
                    if (inside(row, col)) {
                        val target = Square(row, col)
                        if (board[target] == Piece.EMPTY) {
                            result += DraughtsMove(from, listOf(target))
                        }
                    }
                }
            }
        }

        return result
    }

    private fun captureMovesForPiece(
        board: Board,
        from: Square,
        initialPiece: Piece
    ): List<DraughtsMove> {
        val result = mutableListOf<DraughtsMove>()
        val cells = board.cells.toMutableList()
        cells[from.index] = Piece.EMPTY

        fun search(
            position: Square,
            movingPiece: Piece,
            capturedIndexes: Set<Int>,
            landings: List<Square>,
            capturedSquares: List<Square>,
            currentCells: List<Piece>
        ) {
            val options = captureOptions(
                currentCells,
                position,
                movingPiece,
                capturedIndexes
            )

            if (options.isEmpty()) {
                if (capturedSquares.isNotEmpty()) {
                    result += DraughtsMove(from, landings, capturedSquares)
                }
                return
            }

            for (option in options) {
                val nextCells = currentCells.toMutableList()
                nextCells[position.index] = Piece.EMPTY

                val promotionRow = if (movingPiece.side == Side.WHITE) 0 else 7
                val becomesKing = !movingPiece.isKing &&
                    option.landing.row == promotionRow
                val nextPiece = if (becomesKing) {
                    if (movingPiece.side == Side.WHITE) Piece.WHITE_KING else Piece.BLACK_KING
                } else {
                    movingPiece
                }

                // Do not remove the victim here. It remains as an obstacle until
                // the entire capture sequence ends.
                nextCells[option.landing.index] = nextPiece

                search(
                    position = option.landing,
                    movingPiece = nextPiece,
                    capturedIndexes = capturedIndexes + option.victim.index,
                    landings = landings + option.landing,
                    capturedSquares = capturedSquares + option.victim,
                    currentCells = nextCells
                )
            }
        }

        search(
            position = from,
            movingPiece = initialPiece,
            capturedIndexes = emptySet(),
            landings = emptyList(),
            capturedSquares = emptyList(),
            currentCells = cells
        )

        return result
    }

    private fun captureOptions(
        cells: List<Piece>,
        from: Square,
        piece: Piece,
        capturedIndexes: Set<Int>
    ): List<CaptureStep> {
        val result = mutableListOf<CaptureStep>()
        val enemy = piece.side!!.opponent()

        if (!piece.isKing) {
            // A man captures in all four diagonal directions, even backwards.
            for ((dr, dc) in diagonals) {
                val victimRow = from.row + dr
                val victimCol = from.col + dc
                val landingRow = from.row + 2 * dr
                val landingCol = from.col + 2 * dc

                if (!inside(victimRow, victimCol) || !inside(landingRow, landingCol)) {
                    continue
                }

                val victim = Square(victimRow, victimCol)
                val landing = Square(landingRow, landingCol)

                if (
                    cells[victim.index].side == enemy &&
                    victim.index !in capturedIndexes &&
                    cells[landing.index] == Piece.EMPTY
                ) {
                    result += CaptureStep(victim, landing)
                }
            }
            return result
        }

        // A flying king sees the first occupied square on each diagonal.
        // If it is an uncaptured enemy, every empty square beyond it is a
        // possible landing square, until the next occupied square.
        for ((dr, dc) in diagonals) {
            var row = from.row + dr
            var col = from.col + dc

            while (inside(row, col) && cells[Square(row, col).index] == Piece.EMPTY) {
                row += dr
                col += dc
            }

            if (!inside(row, col)) continue

            val victim = Square(row, col)
            if (cells[victim.index].side != enemy || victim.index in capturedIndexes) {
                continue
            }

            row += dr
            col += dc
            while (inside(row, col)) {
                val landing = Square(row, col)
                if (cells[landing.index] != Piece.EMPTY) break
                result += CaptureStep(victim, landing)
                row += dr
                col += dc
            }
        }

        return result
    }

    private fun inside(row: Int, col: Int): Boolean =
        row in 0..7 && col in 0..7
}
