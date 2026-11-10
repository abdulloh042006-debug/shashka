package uz.draughtsai

import org.junit.Assert.*
import org.junit.Test

class RussianDraughtsRulesTest {
    private fun position(
        turn: Side = Side.WHITE,
        vararg pieces: Pair<Square, Piece>
    ): Board {
        val cells = MutableList(64) { Piece.EMPTY }
        pieces.forEach { (square, piece) -> cells[square.index] = piece }
        return Board(cells, turn)
    }

    @Test
    fun manMovesForwardOnly() {
        val board = position(
            Side.WHITE,
            Square(4, 3) to Piece.WHITE_MAN
        )
        val moves = RussianDraughtsRules.legalMoves(board)

        assertEquals(2, moves.size)
        assertTrue(moves.all { it.to.row == 3 })
    }

    @Test
    fun manCanCaptureBackward() {
        val board = position(
            Side.WHITE,
            Square(4, 3) to Piece.WHITE_MAN,
            Square(5, 4) to Piece.BLACK_MAN
        )
        val moves = RussianDraughtsRules.legalMoves(board)

        assertEquals(1, moves.size)
        assertTrue(moves.single().isCapture)
        assertEquals(Square(6, 5), moves.single().to)
        assertEquals(listOf(Square(5, 4)), moves.single().captured)
    }

    @Test
    fun captureIsMandatoryAndSuppressesQuietMoves() {
        val board = position(
            Side.WHITE,
            Square(4, 3) to Piece.WHITE_MAN,
            Square(5, 4) to Piece.BLACK_MAN,
            Square(6, 1) to Piece.WHITE_MAN
        )
        val moves = RussianDraughtsRules.legalMoves(board)

        assertTrue(moves.isNotEmpty())
        assertTrue(moves.all { it.isCapture })
    }

    @Test
    fun manMustContinueAAvailableMultipleCapture() {
        val board = position(
            Side.WHITE,
            Square(5, 0) to Piece.WHITE_MAN,
            Square(4, 1) to Piece.BLACK_MAN,
            Square(2, 3) to Piece.BLACK_MAN
        )
        val moves = RussianDraughtsRules.legalMoves(board)

        assertTrue(
            moves.any {
                it.from == Square(5, 0) &&
                    it.landings == listOf(Square(3, 2), Square(1, 4)) &&
                    it.captured == listOf(Square(4, 1), Square(2, 3))
            }
        )
    }

    @Test
    fun flyingKingCanChooseAnyEmptyLandingBeyondCapturedPiece() {
        val board = position(
            Side.WHITE,
            Square(5, 0) to Piece.WHITE_KING,
            Square(3, 2) to Piece.BLACK_MAN
        )
        val moves = RussianDraughtsRules.legalMoves(board)
        val landings = moves.map { it.to }.toSet()

        assertEquals(
            setOf(Square(2, 3), Square(1, 4), Square(0, 5)),
            landings
        )
    }

    @Test
    fun manPromotesDuringCaptureAndContinuesAsKing() {
        val board = position(
            Side.WHITE,
            Square(2, 1) to Piece.WHITE_MAN,
            Square(1, 2) to Piece.BLACK_MAN,
            Square(1, 4) to Piece.BLACK_MAN
        )
        val moves = RussianDraughtsRules.legalMoves(board)

        val promotingSequence = moves.firstOrNull {
            it.from == Square(2, 1) &&
                it.landings.firstOrNull() == Square(0, 3) &&
                it.captured.size == 2
        }
        assertNotNull(promotingSequence)

        val result = RussianDraughtsRules.applyMove(board, promotingSequence!!)
        assertEquals(Piece.WHITE_KING, result[Square(2, 5)])
        assertEquals(Piece.EMPTY, result[Square(1, 2)])
        assertEquals(Piece.EMPTY, result[Square(1, 4)])
        assertEquals(Side.BLACK, result.sideToMove)
    }

    @Test
    fun classicRussianRulesDoNotRequireTheLongestCaptureSequence() {
        val board = position(
            Side.WHITE,
            Square(6, 1) to Piece.WHITE_MAN,
            Square(6, 7) to Piece.WHITE_MAN,
            Square(5, 2) to Piece.BLACK_MAN,
            Square(3, 4) to Piece.BLACK_MAN,
            Square(5, 6) to Piece.BLACK_MAN
        )
        val moves = RussianDraughtsRules.legalMoves(board)

        assertTrue(moves.any {
            it.from == Square(6, 1) && it.captured.size == 2
        })
        assertTrue(moves.any {
            it.from == Square(6, 7) && it.captured.size == 1
        })
    }

    @Test
    fun applyingMoveRemovesCapturedPieceAndChangesTurn() {
        val board = position(
            Side.WHITE,
            Square(4, 3) to Piece.WHITE_MAN,
            Square(3, 2) to Piece.BLACK_MAN
        )
        val move = RussianDraughtsRules.legalMoves(board).single()
        val result = RussianDraughtsRules.applyMove(board, move)

        assertEquals(Piece.WHITE_MAN, result[move.to])
        assertEquals(Piece.EMPTY, result[Square(3, 2)])
        assertEquals(Side.BLACK, result.sideToMove)
    }

    @Test
    fun kingMovesAlongClearDiagonalButStopsAtOccupiedSquare() {
        val board = position(
            Side.WHITE,
            Square(5, 0) to Piece.WHITE_KING,
            Square(3, 2) to Piece.WHITE_MAN
        )
        val moves = RussianDraughtsRules.legalMoves(board)

        assertFalse(moves.any { it.to == Square(2, 3) })
        assertTrue(moves.any { it.to == Square(4, 1) })
    }

    @Test
    fun alreadyCapturedPieceCannotBeCapturedAgainInSameSequence() {
        // The captured checker remains on the board and blocks the king's ray.
        // The generator may not treat that same checker as a fresh victim.
        val board = position(
            Side.WHITE,
            Square(5, 0) to Piece.WHITE_KING,
            Square(3, 2) to Piece.BLACK_MAN,
            Square(1, 4) to Piece.BLACK_MAN
        )
        val moves = RussianDraughtsRules.legalMoves(board)

        assertTrue(moves.all { move ->
            move.captured.distinct().size == move.captured.size
        })
    }
}
