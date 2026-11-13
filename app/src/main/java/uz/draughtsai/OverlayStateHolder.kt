package uz.draughtsai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Settings of the floating window that survive service restarts (M7).
 * Position/size are in pixels of the current screen; [opacity] is the user's requested visible opacity.
 */
data class OverlaySettings(
    val x: Int = 24,
    val y: Int = 160,
    val widthPx: Int = 0, // 0 = not chosen yet, the service picks a default
    val opacity: Float = OverlayStateHolder.DEFAULT_OPACITY,
    val locked: Boolean = false,
    val flipped: Boolean = false,
    val engineTimeMs: Long = OverlayStateHolder.DEFAULT_ENGINE_TIME_MS
)

/** Persistence for [OverlaySettings]; SharedPreferences on device, in-memory in JVM tests. */
interface OverlaySettingsStore {
    fun load(): OverlaySettings?
    fun save(settings: OverlaySettings)
}

data class OverlayAnalysis(
    val board: Board,
    val bestMove: Move?,
    val scoreText: String,
    val depth: Int
)

data class OverlayState(
    val board: Board = Board.initial(),
    val history: List<Board> = emptyList(),
    val selected: Pos? = null,
    val lastMove: Move? = null,
    val analysis: OverlayAnalysis? = null,
    val settings: OverlaySettings = OverlaySettings()
) {
    /** Opacity actually applied to the board window. */
    val effectiveOpacity: Float get() = OverlayStateHolder.effectiveOpacity(settings.opacity, settings.locked)
    val canUndo: Boolean get() = history.isNotEmpty()

    /** Analysis only if it belongs to the position currently shown. */
    val currentAnalysis: OverlayAnalysis? get() = analysis?.takeIf { it.board == board }
}

/**
 * Single source of truth for the position shared between the main screen and the floating window,
 * plus the window's own settings. Pure Kotlin so it can be unit-tested on the JVM.
 *
 * Scope: the position only ever comes from the user (editor, FEN, user-picked screenshot, or moves
 * entered on the mini board). Nothing here reads other apps or acts on them.
 */
class OverlayStateHolder(private val store: OverlaySettingsStore) {

    companion object {
        const val MIN_OPACITY = 0.2f
        const val MAX_OPACITY = 1.0f
        /** Android 12+ (InputManager.getMaximumObscuringOpacityForTouch) blocks pass-through touches above this. */
        const val MAX_LOCKED_OPACITY = 0.8f
        const val DEFAULT_OPACITY = 0.85f
        const val MIN_ENGINE_TIME_MS = 300L
        const val MAX_ENGINE_TIME_MS = 800L
        const val DEFAULT_ENGINE_TIME_MS = 500L
        const val MAX_HISTORY = 200

        fun clampOpacity(value: Float): Float = value.coerceIn(MIN_OPACITY, MAX_OPACITY)

        fun effectiveOpacity(requested: Float, locked: Boolean): Float {
            val clamped = clampOpacity(requested)
            return if (locked) minOf(clamped, MAX_LOCKED_OPACITY) else clamped
        }
    }

    private val _state = MutableStateFlow(OverlayState(settings = sanitize(store.load() ?: OverlaySettings())))
    val state: StateFlow<OverlayState> = _state.asStateFlow()

    /** True while the floating-window service is showing its window. */
    val overlayRunning = MutableStateFlow(false)

    // ---- Position sync ---------------------------------------------------------------------

    /** The main screen reports its current position. No-op when it is already the shared one. */
    fun syncFromApp(board: Board) {
        _state.update { s ->
            if (s.board == board) s
            else s.copy(board = board, history = pushHistory(s.history, s.board), selected = null, lastMove = null)
        }
    }

    /**
     * A tap on the mini board (overlay). Selects an own piece, or plays a legal move from the selected piece.
     * Ignored while locked. Returns the move played, if any.
     */
    fun tapSquare(pos: Pos): Move? {
        val s = _state.value
        if (s.settings.locked || !pos.isDarkSquare) return null
        val legal = RussianDraughtsRules.legalMoves(s.board)
        val from = s.selected
        if (from != null) {
            val fromMoves = legal.filter { it.from == from }
            val exact = fromMoves.filter { it.to == pos }
            val move = exact.maxByOrNull { it.captureCount }
                ?: fromMoves.singleOrNull()?.takeIf { pos in it.landings }
            if (move != null) {
                playMove(move)
                return move
            }
        }
        val piece = s.board[pos]
        val newSelected = if (piece != null && piece.side == s.board.sideToMove && pos != from) pos else null
        _state.update { it.copy(selected = newSelected) }
        return null
    }

    /** Plays [move] if it is legal in the current position. */
    fun playMove(move: Move): Boolean {
        var played = false
        _state.update { s ->
            if (s.settings.locked || !RussianDraughtsRules.isLegalMove(s.board, move)) {
                s
            } else {
                played = true
                s.copy(
                    board = RussianDraughtsRules.applyMove(s.board, move),
                    history = pushHistory(s.history, s.board),
                    selected = null,
                    lastMove = move
                )
            }
        }
        return played
    }

    fun undo(): Boolean {
        var done = false
        _state.update { s ->
            if (s.settings.locked || s.history.isEmpty()) s
            else {
                done = true
                s.copy(board = s.history.last(), history = s.history.dropLast(1), selected = null, lastMove = null)
            }
        }
        return done
    }

    /** Stores an engine result; dropped if the position has changed meanwhile. */
    fun setAnalysis(analysis: OverlayAnalysis) {
        _state.update { s -> if (s.board == analysis.board) s.copy(analysis = analysis) else s }
    }

    // ---- Window settings -------------------------------------------------------------------

    fun setLocked(locked: Boolean) = updateSettings { it.copy(locked = locked) }

    fun toggleFlip() = updateSettings { it.copy(flipped = !it.flipped) }

    fun setOpacity(value: Float) = updateSettings { it.copy(opacity = clampOpacity(value)) }

    fun setEngineTimeMs(ms: Long) = updateSettings {
        it.copy(engineTimeMs = ms.coerceIn(MIN_ENGINE_TIME_MS, MAX_ENGINE_TIME_MS))
    }

    fun setGeometry(x: Int, y: Int, widthPx: Int) = updateSettings { it.copy(x = x, y = y, widthPx = widthPx) }

    private fun updateSettings(change: (OverlaySettings) -> OverlaySettings) {
        val before = _state.value.settings
        _state.update { s -> s.copy(settings = sanitize(change(s.settings)), selected = null) }
        val after = _state.value.settings
        if (after != before) store.save(after)
    }

    private fun sanitize(s: OverlaySettings): OverlaySettings = s.copy(
        opacity = clampOpacity(s.opacity),
        engineTimeMs = s.engineTimeMs.coerceIn(MIN_ENGINE_TIME_MS, MAX_ENGINE_TIME_MS),
        widthPx = s.widthPx.coerceAtLeast(0)
    )

    private fun pushHistory(history: List<Board>, board: Board): List<Board> =
        (history + board).takeLast(MAX_HISTORY)
}

/** Pure geometry helpers for the floating window (snapping inside the screen, resize limits). */
object OverlayGeometry {
    data class Rect(val x: Int, val y: Int, val width: Int)

    /**
     * Keeps a window of [width] x ([width] + [extraHeightPx]) fully inside the screen.
     * Width is limited to [minWidthPx]..screen width.
     */
    fun clamp(
        x: Int,
        y: Int,
        width: Int,
        extraHeightPx: Int,
        screenWidth: Int,
        screenHeight: Int,
        minWidthPx: Int
    ): Rect {
        val maxW = screenWidth.coerceAtLeast(1)
        val w = width.coerceIn(minOf(minWidthPx, maxW), maxW)
        val h = w + extraHeightPx
        val cx = x.coerceIn(0, (screenWidth - w).coerceAtLeast(0))
        val cy = y.coerceIn(0, (screenHeight - h).coerceAtLeast(0))
        return Rect(cx, cy, w)
    }
}
