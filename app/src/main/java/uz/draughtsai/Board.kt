package uz.draughtsai

enum class Side {
    WHITE, BLACK;

    fun opponent(): Side = if (this == WHITE) BLACK else WHITE
}

enum class Piece(val side: Side?) {
    EMPTY(null),
    WHITE_MAN(Side.WHITE),
    BLACK_MAN(Side.BLACK),
    WHITE_KING(Side.WHITE),
    BLACK_KING(Side.BLACK);

    val isKing: Boolean
        get() = this == WHITE_KING || this == BLACK_KING

    companion object {
        fun man(side: Side): Piece =
            if (side == Side.WHITE) WHITE_MAN else BLACK_MAN
    }
}

data class Square(val row: Int, val col: Int) {
    init {
        require(row in 0..7) { "Row must be between 0 and 7." }
        require(col in 0..7) { "Column must be between 0 and 7." }
    }

    val index: Int get() = row * 8 + col
    val isPlayable: Boolean get() = (row + col) % 2 == 1

    companion object {
        fun fromIndex(index: Int): Square {
            require(index in 0..63) { "Index must be between 0 and 63." }
            return Square(index / 8, index % 8)
        }
    }
}

/**
 * Immutable board snapshot. This stores positions only.
 * Legal move generation belongs to the separate rules engine.
 */
data class Board(
    val cells: List<Piece> = initialCells(),
    val sideToMove: Side = Side.WHITE
) {
    init {
        require(cells.size == 64) { "A draughts board must contain 64 cells." }
        require(cells.indices.all { index ->
            Square.fromIndex(index).isPlayable || cells[index] == Piece.EMPTY
        }) { "Pieces may only occupy playable dark squares." }
    }

    operator fun get(square: Square): Piece = cells[square.index]

    fun withPiece(square: Square, piece: Piece): Board {
        require(square.isPlayable || piece == Piece.EMPTY) {
            "A piece cannot be placed on a light square."
        }
        val updated = cells.toMutableList()
        updated[square.index] = piece
        return copy(cells = updated)
    }

    fun withTurn(side: Side): Board = copy(sideToMove = side)

    fun movePieceForEditing(from: Square, to: Square): Board {
        require(from.isPlayable && to.isPlayable) {
            "Pieces can only be moved between playable squares."
        }
        val movingPiece = this[from]
        require(movingPiece != Piece.EMPTY) { "The source square is empty." }
        require(this[to] == Piece.EMPTY) { "The destination square is occupied." }

        val updated = cells.toMutableList()
        updated[from.index] = Piece.EMPTY
        updated[to.index] = movingPiece
        return copy(cells = updated)
    }

    companion object {
        fun initial(): Board = Board()
    }
}

private fun initialCells(): List<Piece> = List(64) { index ->
    val square = Square.fromIndex(index)
    when {
        !square.isPlayable -> Piece.EMPTY
        square.row < 3 -> Piece.BLACK_MAN
        square.row > 4 -> Piece.WHITE_MAN
        else -> Piece.EMPTY
    }
}
