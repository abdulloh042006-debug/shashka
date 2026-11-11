package uz.draughtsai

/**
 * Represents the player side in Classic Russian Draughts.
 * White moves first and advances toward decreasing row indices (row 7 -> row 0).
 * Black moves second and advances toward increasing row indices (row 0 -> row 7).
 */
enum class Side {
    WHITE,
    BLACK;

    fun opposite(): Side = if (this == WHITE) BLACK else WHITE

    /** Forward row direction for men of this side. */
    val forwardRowDelta: Int
        get() = if (this == WHITE) -1 else 1

    /** Promotion row index for men of this side. */
    val promotionRow: Int
        get() = if (this == WHITE) 0 else 7
}

/**
 * Piece rank in Russian Draughts: regular Man (шашка) or flying King (дамка).
 */
enum class PieceKind {
    MAN,
    KING
}

/**
 * Immutable piece on a playable dark square.
 */
data class Piece(
    val side: Side,
    val kind: PieceKind = PieceKind.MAN
) {
    val isKing: Boolean get() = kind == PieceKind.KING
    val isMan: Boolean get() = kind == PieceKind.MAN

    fun promote(): Piece = if (isKing) this else copy(kind = PieceKind.KING)
    fun toggleKind(): Piece = copy(kind = if (isKing) PieceKind.MAN else PieceKind.KING)

    fun toChar(): Char = when (side) {
        Side.WHITE -> if (isKing) 'W' else 'w'
        Side.BLACK -> if (isKing) 'B' else 'b'
    }

    companion object {
        fun fromChar(c: Char): Piece? = when (c) {
            'w' -> Piece(Side.WHITE, PieceKind.MAN)
            'W' -> Piece(Side.WHITE, PieceKind.KING)
            'b' -> Piece(Side.BLACK, PieceKind.MAN)
            'B' -> Piece(Side.BLACK, PieceKind.KING)
            else -> null
        }
    }
}

/**
 * Coordinates on the 8x8 draughts board.
 * [row] is 0..7 from top (rank 8) to bottom (rank 1).
 * [col] is 0..7 from left (file 'a') to right (file 'h').
 * Playable dark squares satisfy `(row + col) % 2 == 1` (e.g., a1 is row=7, col=0).
 */
data class Pos(val row: Int, val col: Int) {
    val isInside: Boolean
        get() = row in 0..7 && col in 0..7

    val isDarkSquare: Boolean
        get() = isInside && ((row + col) and 1) == 1

    /** Converts this coordinate to standard draughts notation (e.g. "c3"). */
    fun toNotation(): String {
        require(isInside) { "Position out of bounds: ($row, $col)" }
        val file = ('a'.code + col).toChar()
        val rank = 8 - row
        return "$file$rank"
    }

    companion object {
        fun fromNotation(notation: String): Pos {
            val cleaned = notation.trim().lowercase()
            require(cleaned.length == 2) { "Invalid square notation: '$notation'" }
            val col = cleaned[0] - 'a'
            val rank = cleaned[1] - '0'
            val row = 8 - rank
            val pos = Pos(row, col)
            require(pos.isInside) { "Square out of 8x8 bounds: '$notation'" }
            return pos
        }

        /** All 32 playable dark squares in row-major order. */
        val PLAYABLE_SQUARES: List<Pos> = buildList(32) {
            for (r in 0..7) {
                for (c in 0..7) {
                    val p = Pos(r, c)
                    if (p.isDarkSquare) add(p)
                }
            }
        }
    }
}

/**
 * Represents one complete legal turn in Russian Draughts.
 *
 * @property path Ordered list of squares visited by the moving piece, starting with [from]
 *                and followed by every landing square in order.
 * @property captured Ordered list of enemy piece coordinates captured during this turn.
 * @property promoted True if a Man reached the promotion row during or at the end of this turn.
 */
data class Move(
    val path: List<Pos>,
    val captured: List<Pos> = emptyList(),
    val promoted: Boolean = false
) {
    init {
        require(path.size >= 2) { "A move path must contain at least start and end squares." }
    }

    val from: Pos get() = path.first()
    val to: Pos get() = path.last()
    val landings: List<Pos> get() = path.drop(1)
    val isCapture: Boolean get() = captured.isNotEmpty()
    val captureCount: Int get() = captured.size

    /**
     * Formats the move in standard Russian Draughts notation:
     * - Quiet move: `c3-d4`
     * - Capture sequence: `c3:e5:g3`
     */
    fun toNotation(): String {
        val separator = if (isCapture) ":" else "-"
        return path.joinToString(separator) { it.toNotation() }
    }

    override fun toString(): String = toNotation()
}

/**
 * Immutable 8x8 board state for Classic Russian Draughts.
 * Only playable dark squares may contain pieces.
 */
class Board private constructor(
    private val cells: Array<Piece?>,
    val sideToMove: Side
) {
    /** Returns the piece at [pos], or null if empty or out of bounds. */
    operator fun get(pos: Pos): Piece? {
        if (!pos.isInside) return null
        return cells[(pos.row shl 3) or pos.col]
    }

    /** Returns the piece at ([row], [col]), or null if empty or out of bounds. */
    fun get(row: Int, col: Int): Piece? {
        if (row !in 0..7 || col !in 0..7) return null
        return cells[(row shl 3) or col]
    }

    /**
     * Places or removes a piece at [pos] (Board Editor operation).
     * Throws [IllegalArgumentException] if attempting to place a piece on a non-playable light square
     * or outside the board.
     */
    fun setPiece(pos: Pos, piece: Piece?): Board {
        require(pos.isInside) { "Position out of bounds: $pos" }
        if (piece != null) {
            require(pos.isDarkSquare) { "Pieces can only be placed on dark playable squares: $pos" }
        }
        val copy = cells.copyOf()
        copy[(pos.row shl 3) or pos.col] = piece
        return Board(copy, sideToMove)
    }

    /** Removes any piece at [pos]. */
    fun removePiece(pos: Pos): Board = setPiece(pos, null)

    /** Promotes/demotes a piece at [pos] in the editor. */
    fun togglePieceKind(pos: Pos): Board {
        val existing = this[pos] ?: return this
        return setPiece(pos, existing.toggleKind())
    }

    /**
     * Editor-only repositioning of a piece from [from] to [to] without rule enforcement.
     */
    fun repositionPiece(from: Pos, to: Pos): Board {
        require(from.isInside && to.isDarkSquare) { "Invalid editor reposition from $from to $to" }
        if (from == to) return this
        val moving = this[from] ?: return this
        val copy = cells.copyOf()
        copy[(from.row shl 3) or from.col] = null
        copy[(to.row shl 3) or to.col] = moving
        return Board(copy, sideToMove)
    }

    /** Returns a copy of this board with [newSide] set to move next. */
    fun withSideToMove(newSide: Side): Board {
        if (newSide == sideToMove) return this
        return Board(cells.copyOf(), newSide)
    }

    /** Returns all pieces currently on the board as (Pos, Piece) pairs. */
    fun allPieces(): List<Pair<Pos, Piece>> = buildList {
        for (pos in Pos.PLAYABLE_SQUARES) {
            val p = this@Board[pos]
            if (p != null) add(pos to p)
        }
    }

    /** Returns all pieces belonging to [side]. */
    fun piecesOf(side: Side): List<Pair<Pos, Piece>> = buildList {
        for (pos in Pos.PLAYABLE_SQUARES) {
            val p = this@Board[pos]
            if (p != null && p.side == side) add(pos to p)
        }
    }

    /** Counts pieces matching [side] and optional [kind]. */
    fun countPieces(side: Side, kind: PieceKind? = null): Int {
        var count = 0
        for (pos in Pos.PLAYABLE_SQUARES) {
            val p = this[pos] ?: continue
            if (p.side == side && (kind == null || p.kind == kind)) {
                count++
            }
        }
        return count
    }

    /**
     * Serializes the board into a compact FEN-like string:
     * `<32 dark squares in row-major order (w/W/b/B/.)> <W|B>`
     */
    fun toFen(): String {
        val sb = StringBuilder(34)
        for (pos in Pos.PLAYABLE_SQUARES) {
            val piece = this[pos]
            sb.append(piece?.toChar() ?: '.')
        }
        sb.append(' ')
        sb.append(if (sideToMove == Side.WHITE) 'W' else 'B')
        return sb.toString()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Board) return false
        return sideToMove == other.sideToMove && cells.contentEquals(other.cells)
    }

    override fun hashCode(): Int {
        var result = cells.contentHashCode()
        result = 31 * result + sideToMove.hashCode()
        return result
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("  a b c d e f g h\n")
        for (r in 0..7) {
            sb.append(8 - r).append(' ')
            for (c in 0..7) {
                val pos = Pos(r, c)
                val ch = when {
                    !pos.isDarkSquare -> ' '
                    else -> this[pos]?.toChar() ?: '.'
                }
                sb.append(ch).append(' ')
            }
            sb.append(8 - r).append('\n')
        }
        sb.append("  a b c d e f g h  [Turn: $sideToMove]")
        return sb.toString()
    }

    companion object {
        /** Empty 8x8 board with no pieces. */
        fun empty(sideToMove: Side = Side.WHITE): Board {
            return Board(arrayOfNulls(64), sideToMove)
        }

        /**
         * Classic Russian Draughts initial position:
         * 12 Black men on rows 0..2, 12 White men on rows 5..7, White to move.
         */
        fun initial(): Board {
            val cells = arrayOfNulls<Piece>(64)
            for (pos in Pos.PLAYABLE_SQUARES) {
                when (pos.row) {
                    in 0..2 -> cells[(pos.row shl 3) or pos.col] = Piece(Side.BLACK, PieceKind.MAN)
                    in 5..7 -> cells[(pos.row shl 3) or pos.col] = Piece(Side.WHITE, PieceKind.MAN)
                }
            }
            return Board(cells, Side.WHITE)
        }

        /**
         * Creates a custom board from a map of positions or notation pairs.
         */
        fun fromMap(
            pieces: Map<Pos, Piece>,
            sideToMove: Side = Side.WHITE
        ): Board {
            var board = empty(sideToMove)
            for ((pos, piece) in pieces) {
                board = board.setPiece(pos, piece)
            }
            return board
        }

        /**
         * Parses a board from [toFen] format.
         */
        fun fromFen(fen: String): Board {
            val parts = fen.trim().split(Regex("\\s+"))
            require(parts.isNotEmpty() && parts[0].length == 32) {
                "Invalid FEN: expected 32 playable square characters."
            }
            val cells = arrayOfNulls<Piece>(64)
            val squares = parts[0]
            for (i in 0 until 32) {
                val pos = Pos.PLAYABLE_SQUARES[i]
                val piece = Piece.fromChar(squares[i])
                if (squares[i] != '.' && piece == null) {
                    throw IllegalArgumentException("Invalid piece character '${squares[i]}' at index $i")
                }
                cells[(pos.row shl 3) or pos.col] = piece
            }
            val side = if (parts.size > 1 && parts[1].uppercase() == "B") Side.BLACK else Side.WHITE
            return Board(cells, side)
        }
    }
}
