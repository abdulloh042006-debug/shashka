package uz.draughtsai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RussianDraughtsRulesTest {

    @Test
    fun initialBoard_hasExactlySevenQuietOpeningMovesForWhite() {
        val board = Board.initial()
        val moves = RussianDraughtsRules.legalMoves(board)
        val notations = moves.map { it.toNotation() }.toSet()

        assertEquals(7, moves.size)
        assertEquals(
            setOf("a3-b4", "c3-b4", "c3-d4", "e3-d4", "e3-f4", "g3-f4", "g3-h4"),
            notations
        )
    }

    @Test
    fun menMoveForwardOnlyOnQuietMoves_andCaptureBothForwardAndBackward() {
        // White man at d4, Black man behind it at c3 (b2 empty)
        val board = Board.fromMap(
            pieces = mapOf(
                Pos.fromNotation("d4") to Piece(Side.WHITE, PieceKind.MAN),
                Pos.fromNotation("c3") to Piece(Side.BLACK, PieceKind.MAN)
            ),
            sideToMove = Side.WHITE
        )

        val moves = RussianDraughtsRules.legalMoves(board)
        // Compulsory backward capture d4:b2 must be the only legal move
        assertEquals(1, moves.size)
        assertEquals("d4:b2", moves.first().toNotation())
    }

    @Test
    fun compulsoryCapture_disallowsAllQuietMoves() {
        val board = Board.fromMap(
            pieces = mapOf(
                Pos.fromNotation("a3") to Piece(Side.WHITE, PieceKind.MAN),
                Pos.fromNotation("c3") to Piece(Side.WHITE, PieceKind.MAN),
                Pos.fromNotation("d4") to Piece(Side.BLACK, PieceKind.MAN)
            ),
            sideToMove = Side.WHITE
        )

        val moves = RussianDraughtsRules.legalMoves(board)
        assertEquals(1, moves.size)
        assertEquals("c3:e5", moves.first().toNotation())

        val illegalQuiet = Move(
            path = listOf(Pos.fromNotation("a3"), Pos.fromNotation("b4")),
            captured = emptyList()
        )
        assertFalse(RussianDraughtsRules.isLegalMove(board, illegalQuiet))
    }

    @Test
    fun midCapturePromotion_crownsImmediatelyAndContinuesAsFlyingKing() {
        // White man at d6 jumps e7 -> lands on promotion square f8 (crowns to King immediately),
        // then continues in the SAME turn as a flying king capturing c5 and landing on b4 or a3!
        val board = Board.fromMap(
            pieces = mapOf(
                Pos.fromNotation("d6") to Piece(Side.WHITE, PieceKind.MAN),
                Pos.fromNotation("e7") to Piece(Side.BLACK, PieceKind.MAN),
                Pos.fromNotation("c5") to Piece(Side.BLACK, PieceKind.MAN)
            ),
            sideToMove = Side.WHITE
        )

        val moves = RussianDraughtsRules.legalMoves(board)
        val notations = moves.map { it.toNotation() }.toSet()

        assertEquals(setOf("d6:f8:b4", "d6:f8:a3"), notations)
        assertTrue(moves.all { it.promoted })

        // Applying d6:f8:a3 should leave a White King on a3 and remove both Black pieces
        val chosen = moves.first { it.toNotation() == "d6:f8:a3" }
        val nextBoard = RussianDraughtsRules.applyMove(board, chosen)
        assertEquals(Piece(Side.WHITE, PieceKind.KING), nextBoard[Pos.fromNotation("a3")])
        assertNull(nextBoard[Pos.fromNotation("e7")])
        assertNull(nextBoard[Pos.fromNotation("c5")])
        assertEquals(Side.BLACK, nextBoard.sideToMove)
    }

    @Test
    fun turkishStrike_capturedPieceRemainsUntilTurnEnds_blockingSecondCapture() {
        // Classic Turkish Strike position:
        // White king at d2; Black men at e3, e5, c5, c3.
        val board = Board.fromMap(
            pieces = mapOf(
                Pos.fromNotation("d2") to Piece(Side.WHITE, PieceKind.KING),
                Pos.fromNotation("e3") to Piece(Side.BLACK, PieceKind.MAN),
                Pos.fromNotation("e5") to Piece(Side.BLACK, PieceKind.MAN),
                Pos.fromNotation("c5") to Piece(Side.BLACK, PieceKind.MAN),
                Pos.fromNotation("c3") to Piece(Side.BLACK, PieceKind.MAN)
            ),
            sideToMove = Side.WHITE
        )

        val moves = RussianDraughtsRules.legalMoves(board)
        assertTrue(moves.isNotEmpty())
        for (move in moves) {
            assertEquals(move.captured.size, move.captured.toSet().size)
            assertTrue(move.captureCount <= 4)
        }

        val clockwiseMoves = moves.filter {
            it.path.take(4) == listOf(
                Pos.fromNotation("d2"),
                Pos.fromNotation("f4"),
                Pos.fromNotation("d6"),
                Pos.fromNotation("b4")
            )
        }
        val clockwiseNotations = clockwiseMoves.map { it.toNotation() }.toSet()
        assertEquals(setOf("d2:f4:d6:b4:d2", "d2:f4:d6:b4:e1"), clockwiseNotations)
    }

    @Test
    fun flyingKing_mustChooseLandingSquareWithContinuationWhenAvailable() {
        val board = Board.fromMap(
            pieces = mapOf(
                Pos.fromNotation("a1") to Piece(Side.WHITE, PieceKind.KING),
                Pos.fromNotation("c3") to Piece(Side.BLACK, PieceKind.MAN),
                Pos.fromNotation("f4") to Piece(Side.BLACK, PieceKind.MAN)
            ),
            sideToMove = Side.WHITE
        )

        val moves = RussianDraughtsRules.legalMoves(board)
        val notations = moves.map { it.toNotation() }.toSet()
        assertEquals(setOf("a1:e5:g3", "a1:e5:h2"), notations)
    }

    @Test
    fun classicRussianCaptureChoice_doesNotForceMaximumCaptureCount() {
        val board = Board.fromMap(
            pieces = mapOf(
                Pos.fromNotation("c3") to Piece(Side.WHITE, PieceKind.MAN),
                Pos.fromNotation("b4") to Piece(Side.BLACK, PieceKind.MAN),
                Pos.fromNotation("d4") to Piece(Side.BLACK, PieceKind.MAN),
                Pos.fromNotation("f4") to Piece(Side.BLACK, PieceKind.MAN)
            ),
            sideToMove = Side.WHITE
        )

        val moves = RussianDraughtsRules.legalMoves(board)
        val notations = moves.map { it.toNotation() }.toSet()
        assertEquals(setOf("c3:a5", "c3:e5:g3"), notations)
    }
}
