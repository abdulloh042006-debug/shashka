package uz.draughtsai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardTest {
    @Test
    fun initialPositionHasTwelvePiecesPerSide() {
        val board = Board.initial()
        assertEquals(12, board.cells.count { it.side == Side.WHITE })
        assertEquals(12, board.cells.count { it.side == Side.BLACK })
    }

    @Test
    fun playableSquaresFollowAlternatingPattern() {
        assertTrue(Square(0, 1).isPlayable)
        assertFalse(Square(0, 0).isPlayable)
        assertTrue(Square(7, 6).isPlayable)
    }

    @Test(expected = IllegalArgumentException::class)
    fun pieceCannotBePlacedOnLightSquare() {
        Board.initial().withPiece(Square(0, 0), Piece.WHITE_MAN)
    }

    @Test
    fun updatingBoardDoesNotMutateOriginalSnapshot() {
        val original = Board.initial()
        val changed = original.withPiece(Square(3, 0), Piece.WHITE_MAN)
        assertEquals(Piece.EMPTY, original[Square(3, 0)])
        assertEquals(Piece.WHITE_MAN, changed[Square(3, 0)])
    }

    @Test
    fun editingMoveMovesPieceAndEmptiesSource() {
        val board = Board.initial().withPiece(Square(3, 0), Piece.WHITE_MAN)
        val moved = board.movePieceForEditing(Square(3, 0), Square(4, 1))
        assertEquals(Piece.EMPTY, moved[Square(3, 0)])
        assertEquals(Piece.WHITE_MAN, moved[Square(4, 1)])
    }

    @Test
    fun sideToMoveCanBeChanged() {
        assertEquals(Side.BLACK, Board.initial().withTurn(Side.BLACK).sideToMove)
    }
}
