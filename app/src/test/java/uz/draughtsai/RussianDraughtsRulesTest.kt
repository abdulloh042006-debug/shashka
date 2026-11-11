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
        val board = position(Side.WHITE, Square(4, 3) to Piece.WHITE_MAN)
        val moves = RussianDraughtsRules.legalMoves(board)
        assertEquals(2, moves.size)
        assertTrue(moves.all { it.from == Square(4, 3) && it.to.row == 3 })
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
    fun manMustContinueAnAvailableMultipleCapture() {
        val board = position(
            Side.WHITE,
            Square(5, 0) to Piece.WHITE_MAN,
            Square(4, 1) to Piece.BLACK_MAN,
            Square(2, 3) to Piece.BLACK_MAN
        )
        val moves = RussianDraughtsRules.legalMoves(board)
        assertTrue(moves.any {
            it.from == Square(5, 0) &&
                it.landings == listOf(Square(3, 2), Square(1, 4)) &&
                it.captured == listOf(Square(4, 1), Square(2, 3))
        })
    }

    @Test
    fun flyingKingCanChooseAnyEmptyLandingBeyondCapturedPiece() {
        val board = position(
            Side.WHITE,
            Square(5, 0) to Piece.WHITE_KING,
            Square(3, 2) to Piece.BLACK_MAN
        )
        val landings = RussianDraughtsRules.legalMoves(board).map { it.to }.toSet()
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
        val sequence = moves.firstOrNull {
            it.from == Square(2, 1) &&
                it.landings.firstOrNull() == Square(0, 3) &&
                it.captured.size == 2
        }
        assertNotNull(sequence)

        val result = RussianDraughtsRules.applyMove(board, sequence!!)
        assertEquals(Piece.WHITE_KING, result[Square(2, 5)])
        assertEquals(Piece.EMPTY, result[Square(1, 2)])
        assertEquals(Piece.EMPTY, result[Square(1, 4)])
        assertEquals(Side.BLACK, result.sideToMove)
    }

    @Test
    fun kingKeepsTerminalCaptureBranchWhenAnotherLandingCanContinue() {
        val board = position(
            Side.WHITE,
            Square(7, 0) to Piece.WHITE_KING,
            Square(5, 2) to Piece.BLACK_MAN,
            Square(2, 1) to Piece.BLACK_MAN
        )

        val moves = RussianDraughtsRules.legalMoves(board)

        // Landing on c4 allows another capture of b6; landing on d5 ends
        // the turn. Both complete routes are legal choices in Russian Draughts.
        assertTrue(moves.any {
            it.from == Square(7, 0) &&
                it.landings == listOf(Square(4, 3), Square(1, 0)) &&
                it.captured == listOf(Square(5, 2), Square(2, 1))
        })
        assertTrue(moves.any {
            it.from == Square(7, 0) &&
                it.landings == listOf(Square(3, 4)) &&
                it.captured == listOf(Square(5, 2))
        })
    }

    @Test
    fun classicRussianRulesDoNotRequireTheLongestCaptureSequence() {
        val board = position(
            Side.WHITE,
            Square(6, 1) to Piece.WHITE_MAN,
            Square(6, 7) to Piece.WHITE_MAN,
            Square(5, 2) to Piece.BLACK_MAN,
            Square(3, 2) to Piece.BLACK_MAN,
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
    fun applyingCaptureRemovesCapturedPieceAndChangesTurn() {
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
    fun kingMovesAlongClearDiagonalButStopsAtOwnPiece() {
        val kingSquare = Square(5, 0)
        val board = position(
            Side.WHITE,
            kingSquare to Piece.WHITE_KING,
            Square(3, 2) to Piece.WHITE_MAN
        )
        val kingMoves = RussianDraughtsRules.legalMoves(board)
            .filter { it.from == kingSquare }
        assertFalse(kingMoves.any { it.to == Square(2, 3) })
        assertTrue(kingMoves.any { it.to == Square(4, 1) })
    }

    @Test
    fun turkishStrikeLeavesCapturedPiecesBlockingTheRayUntilTurnEnds() {
        // Russian Draughts Federation example: black king a5:e1:g3:e5.
        // The captured c3 checker remains on the board and blocks d4 capture.
        val board = position(
            Side.BLACK,
            Square(3, 0) to Piece.BLACK_KING,
            Square(5, 2) to Piece.WHITE_MAN,
            Square(6, 5) to Piece.WHITE_MAN,
            Square(4, 5) to Piece.WHITE_MAN,
            Square(4, 3) to Piece.WHITE_MAN
        )
        val move = RussianDraughtsRules.legalMoves(board).firstOrNull {
            it.from == Square(3, 0) &&
                it.landings == listOf(Square(7, 4), Square(5, 6), Square(3, 4))
        }
        assertNotNull(move)
        assertEquals(
            listOf(Square(5, 2), Square(6, 5), Square(4, 5)),
            move!!.captured
        )
        assertFalse(move.captured.contains(Square(4, 3)))
    }

    @Test
    fun quietMoveToLastRowPromotesMan() {
        val board = position(
            Side.WHITE,
            Square(1, 2) to Piece.WHITE_MAN
        )
        val move = RussianDraughtsRules.legalMoves(board)
            .single { it.to == Square(0, 3) }
        val result = RussianDraughtsRules.applyMove(board, move)
        assertEquals(Piece.WHITE_KING, result[Square(0, 3)])
    }
}
