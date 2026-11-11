package uz.draughtsai

import org.junit.Assert.*
import org.junit.Test

class RussianDraughtsAiTest {
    private fun position(
        turn: Side = Side.WHITE,
        vararg pieces: Pair<Square, Piece>
    ): Board {
        val cells = MutableList(64) { Piece.EMPTY }
        pieces.forEach { (square, piece) -> cells[square.index] = piece }
        return Board(cells, turn)
    }

    @Test
    fun searchAlwaysReturnsALegalMoveFromTheRootPosition() {
        val board = Board.initial()
        val result = RussianDraughtsAi().search(
            board,
            SearchLimits(maxDepth = 3, timeLimitMillis = 5_000)
        )

        assertNotNull(result.bestMove)
        assertTrue(RussianDraughtsRules.isLegalMove(board, result.bestMove!!))
        assertTrue(result.completedDepth >= 1)
        assertTrue(result.nodes > 0)
    }

    @Test
    fun forcedCaptureIsNeverReplacedByAQuietMove() {
        val board = position(
            Side.WHITE,
            Square(4, 3) to Piece.WHITE_MAN,
            Square(5, 4) to Piece.BLACK_MAN,
            Square(6, 1) to Piece.WHITE_MAN
        )
        val result = RussianDraughtsAi().search(
            board,
            SearchLimits(maxDepth = 2, timeLimitMillis = 2_000)
        )

        assertNotNull(result.bestMove)
        assertTrue(result.bestMove!!.isCapture)
        assertEquals(listOf(Square(5, 4)), result.bestMove!!.captured)
    }

    @Test
    fun searchReportsNoMoveWhenSideToMoveHasNoLegalMove() {
        val board = position(
            Side.WHITE,
            Square(0, 1) to Piece.WHITE_MAN,
            Square(1, 2) to Piece.BLACK_MAN
        )
        val result = RussianDraughtsAi().search(
            board,
            SearchLimits(maxDepth = 2, timeLimitMillis = 1_000)
        )

        assertNull(result.bestMove)
        assertEquals(0, result.completedDepth)
    }

    @Test
    fun repeatedSearchesAreDeterministicForFixedLimits() {
        val board = Board.initial()
        val limits = SearchLimits(maxDepth = 3, timeLimitMillis = 5_000)
        val first = RussianDraughtsAi().search(board, limits)
        val second = RussianDraughtsAi().search(board, limits)

        assertEquals(first.bestMove, second.bestMove)
        assertEquals(first.score, second.score)
        assertEquals(first.completedDepth, second.completedDepth)
    }

    @Test
    fun lowNodeLimitStillReturnsTheBestMoveFromLastCompletedIteration() {
        val board = Board.initial()
        val result = RussianDraughtsAi().search(
            board,
            SearchLimits(maxDepth = 12, timeLimitMillis = 10_000, maxNodes = 20)
        )

        assertNotNull(result.bestMove)
        assertTrue(RussianDraughtsRules.isLegalMove(board, result.bestMove!!))
    }
}
