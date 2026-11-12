package uz.draughtsai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayStateHolderTest {

    private class MemoryStore(var saved: OverlaySettings? = null) : OverlaySettingsStore {
        var saveCount = 0
        override fun load(): OverlaySettings? = saved
        override fun save(settings: OverlaySettings) {
            saved = settings
            saveCount++
        }
    }

    private fun sq(n: String) = Pos.fromNotation(n)

    @Test
    fun syncFromAppSharesPositionAndIgnoresDuplicates() {
        val holder = OverlayStateHolder(MemoryStore())
        val after = RussianDraughtsRules.applyMove(Board.initial(), Move(listOf(sq("c3"), sq("d4"))))

        holder.syncFromApp(after)
        assertEquals(after, holder.state.value.board)
        assertEquals(1, holder.state.value.history.size)

        holder.syncFromApp(after) // same position reported again: no new history entry
        assertEquals(1, holder.state.value.history.size)
    }

    @Test
    fun tapSelectsOwnPieceThenPlaysLegalMove() {
        val holder = OverlayStateHolder(MemoryStore())

        assertNull(holder.tapSquare(sq("c3")))
        assertEquals(sq("c3"), holder.state.value.selected)

        val played = holder.tapSquare(sq("d4"))
        assertEquals("c3-d4", played?.toNotation())
        assertEquals(Side.BLACK, holder.state.value.board.sideToMove)
        assertNull(holder.state.value.board[sq("c3")])
        assertEquals(played, holder.state.value.lastMove)
        assertNull(holder.state.value.selected)
    }

    @Test
    fun illegalTargetDoesNotMoveAndOpponentPieceIsNotSelectable() {
        val holder = OverlayStateHolder(MemoryStore())
        holder.tapSquare(sq("c3"))
        assertNull(holder.tapSquare(sq("c5"))) // not a legal destination
        assertEquals(Board.initial(), holder.state.value.board)

        holder.tapSquare(sq("f6")) // black piece, white to move
        assertNull(holder.state.value.selected)
    }

    @Test
    fun playMoveRejectsIllegalMove() {
        val holder = OverlayStateHolder(MemoryStore())
        assertFalse(holder.playMove(Move(listOf(sq("c3"), sq("c5")))))
        assertEquals(Board.initial(), holder.state.value.board)
    }

    @Test
    fun undoRestoresPreviousPosition() {
        val holder = OverlayStateHolder(MemoryStore())
        assertFalse(holder.undo())
        holder.tapSquare(sq("c3"))
        holder.tapSquare(sq("d4"))
        assertTrue(holder.state.value.canUndo)

        assertTrue(holder.undo())
        assertEquals(Board.initial(), holder.state.value.board)
        assertFalse(holder.state.value.canUndo)
    }

    @Test
    fun lockedWindowIgnoresBoardInputButAppSyncStillWorks() {
        val holder = OverlayStateHolder(MemoryStore())
        holder.tapSquare(sq("c3"))
        holder.tapSquare(sq("d4"))
        holder.setLocked(true)

        assertNull(holder.tapSquare(sq("f6")))
        assertNull(holder.state.value.selected)
        assertFalse(holder.undo())

        holder.syncFromApp(Board.initial())
        assertEquals(Board.initial(), holder.state.value.board)

        holder.setLocked(false)
        holder.tapSquare(sq("c3"))
        assertEquals(sq("c3"), holder.state.value.selected)
    }

    @Test
    fun opacityIsClampedAndCappedAt80PercentWhileLocked() {
        val holder = OverlayStateHolder(MemoryStore())
        holder.setOpacity(0.05f)
        assertEquals(0.2f, holder.state.value.settings.opacity, 0.0001f)
        holder.setOpacity(1.5f)
        assertEquals(1.0f, holder.state.value.effectiveOpacity, 0.0001f)

        holder.setLocked(true)
        assertEquals(0.8f, holder.state.value.effectiveOpacity, 0.0001f)
        assertEquals(1.0f, holder.state.value.settings.opacity, 0.0001f) // user's choice is kept

        holder.setOpacity(0.5f)
        assertEquals(0.5f, holder.state.value.effectiveOpacity, 0.0001f)

        holder.setOpacity(0.95f)
        holder.setLocked(false)
        assertEquals(0.95f, holder.state.value.effectiveOpacity, 0.0001f)
    }

    @Test
    fun settingsAreSavedAndRestored() {
        val store = MemoryStore()
        val first = OverlayStateHolder(store)
        first.setGeometry(x = 100, y = 300, widthPx = 520)
        first.setOpacity(0.6f)
        first.setLocked(true)
        first.toggleFlip()
        first.setEngineTimeMs(5_000L)

        val restored = OverlayStateHolder(store).state.value.settings
        assertEquals(100, restored.x)
        assertEquals(300, restored.y)
        assertEquals(520, restored.widthPx)
        assertEquals(0.6f, restored.opacity, 0.0001f)
        assertTrue(restored.locked)
        assertTrue(restored.flipped)
        assertEquals(OverlayStateHolder.MAX_ENGINE_TIME_MS, restored.engineTimeMs)
    }

    @Test
    fun corruptSavedValuesAreSanitizedOnLoad() {
        val store = MemoryStore(OverlaySettings(opacity = 7f, engineTimeMs = 1L, widthPx = -5))
        val s = OverlayStateHolder(store).state.value.settings
        assertEquals(1.0f, s.opacity, 0.0001f)
        assertEquals(OverlayStateHolder.MIN_ENGINE_TIME_MS, s.engineTimeMs)
        assertEquals(0, s.widthPx)
    }

    @Test
    fun unchangedSettingsAreNotRewritten() {
        val store = MemoryStore()
        val holder = OverlayStateHolder(store)
        holder.setOpacity(0.5f)
        val count = store.saveCount
        holder.setOpacity(0.5f)
        assertEquals(count, store.saveCount)
    }

    @Test
    fun staleAnalysisIsDropped() {
        val holder = OverlayStateHolder(MemoryStore())
        val start = Board.initial()
        holder.tapSquare(sq("c3"))
        holder.tapSquare(sq("d4"))
        holder.setAnalysis(OverlayAnalysis(start, null, "0.00", 3))
        assertNull(holder.state.value.analysis)

        val current = holder.state.value.board
        holder.setAnalysis(OverlayAnalysis(current, null, "+0.10", 5))
        assertEquals(5, holder.state.value.currentAnalysis?.depth)

        holder.undo()
        assertNull(holder.state.value.currentAnalysis)
    }

    @Test
    fun geometrySnapsInsideScreenAndLimitsWidth() {
        val r = OverlayGeometry.clamp(
            x = -50, y = 5_000, width = 50, extraHeightPx = 100,
            screenWidth = 1080, screenHeight = 2000, minWidthPx = 400
        )
        assertEquals(400, r.width)
        assertEquals(0, r.x)
        assertEquals(2000 - 400 - 100, r.y)

        val wide = OverlayGeometry.clamp(
            x = 900, y = 0, width = 5_000, extraHeightPx = 100,
            screenWidth = 1080, screenHeight = 2000, minWidthPx = 400
        )
        assertEquals(1080, wide.width)
        assertEquals(0, wide.x)
    }
}
