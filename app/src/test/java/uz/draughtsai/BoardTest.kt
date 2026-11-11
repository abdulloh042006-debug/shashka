package uz.draughtsai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardTest {

    @Test
    fun initialBoard_has12WhiteAnd12BlackMenOnDarkSquares() {
        val board = Board.initial()
        assertEquals(Side.WHITE, board.sideToMove)
        assertEquals(32, Pos.PLAYABLE_SQUARES.size)
        assertEquals(12, board.countPieces(Side.WHITE, PieceKind.MAN))
        assertEquals(0, board.countPieces(Side.WHITE, PieceKind.KING))
        assertEquals(12, board.countPieces(Side.BLACK, PieceKind.MAN))
        assertEquals(0, board.countPieces(Side.BLACK, PieceKind.KING))

        // Verify a1 (row=7, col=0) is a dark square with a White man
        val a1 = Pos.fromNotation("a1")
        assertTrue(a1.isDarkSquare)
        assertEquals(Piece(Side.WHITE, PieceKind.MAN), board[a1])

        // Verify h8 (row=0, col=7) is a dark square with a Black man
        val h8 = Pos.fromNotation("h8")
        assertTrue(h8.isDarkSquare)
        assertEquals(Piece(Side.BLACK, PieceKind.MAN), board[h8])
    }

    @Test(expected = IllegalArgumentException::class)
    fun setPiece_onLightSquare_throwsIllegalArgumentException() {
        val board = Board.empty()
        val a8Light = Pos.fromNotation("a8") // row=0, col=0 -> (0+0)%2 == 0 (light square)
        assertFalse(a8Light.isDarkSquare)
        board.setPiece(a8Light, Piece(Side.WHITE, PieceKind.MAN))
    }

    @Test
    fun boardImmutability_andEditorOperations() {
        val empty = Board.empty()
        val c3 = Pos.fromNotation("c3")
        val d4 = Pos.fromNotation("d4")

        val withMan = empty.setPiece(c3, Piece(Side.WHITE, PieceKind.MAN))
        assertNull(empty[c3])
        assertEquals(Piece(Side.WHITE, PieceKind.MAN), withMan[c3])

        val crowned = withMan.togglePieceKind(c3)
        assertEquals(Piece(Side.WHITE, PieceKind.KING), crowned[c3])

        val repositioned = crowned.repositionPiece(c3, d4)
        assertNull(repositioned[c3])
        assertEquals(Piece(Side.WHITE, PieceKind.KING), repositioned[d4])
    }

    @Test
    fun fenSerialization_roundTripPreservesExactBoard() {
        val original = Board.fromMap(
            pieces = mapOf(
                Pos.fromNotation("c3") to Piece(Side.WHITE, PieceKind.MAN),
                Pos.fromNotation("a1") to Piece(Side.WHITE, PieceKind.KING),
                Pos.fromNotation("f6") to Piece(Side.BLACK, PieceKind.MAN),
                Pos.fromNotation("h8") to Piece(Side.BLACK, PieceKind.KING)
            ),
            sideToMove = Side.BLACK
        )
        val fen = original.toFen()
        val restored = Board.fromFen(fen)
        assertEquals(original, restored)
        assertNotNull(restored[Pos.fromNotation("a1")])
        assertTrue(restored[Pos.fromNotation("a1")]!!.isKing)
        assertEquals(Side.BLACK, restored.sideToMove)
    }
}
