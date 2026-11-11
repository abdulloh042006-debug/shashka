package uz.draughtsai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DraughtsAITest {

    @Test
    fun aiSelectsLegalMove_onInitialBoard() {
        val ai = DraughtsAI()
        val board = Board.initial()
        val result = ai.findBestMove(board, SearchConfig(maxDepth = 5, timeLimitMs = 1_500L))

        assertNotNull(result.bestMove)
        assertTrue(RussianDraughtsRules.isLegalMove(board, result.bestMove!!))
        assertTrue(result.completedDepth >= 1)
        assertTrue(result.principalVariation.isNotEmpty())
    }

    @Test
    fun aiFindsWinningMultiCapture_overSingleCapture() {
        val board = Board.fromMap(
            pieces = mapOf(
                Pos.fromNotation("c3") to Piece(Side.WHITE, PieceKind.MAN),
                Pos.fromNotation("b4") to Piece(Side.BLACK, PieceKind.MAN),
                Pos.fromNotation("d4") to Piece(Side.BLACK, PieceKind.MAN),
                Pos.fromNotation("f4") to Piece(Side.BLACK, PieceKind.MAN)
            ),
            sideToMove = Side.WHITE
        )

        val ai = DraughtsAI()
        val result = ai.findBestMove(board, SearchConfig(maxDepth = 4, timeLimitMs = 1_000L))

        assertNotNull(result.bestMove)
        assertEquals("c3:e5:g3", result.bestMove!!.toNotation())
        assertEquals(2, result.bestMove!!.captureCount)
    }
}

