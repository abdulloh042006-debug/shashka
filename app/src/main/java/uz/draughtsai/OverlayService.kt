package uz.draughtsai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** Process-wide holder shared by MainActivity and [OverlayService]. */
object OverlayRepository {
    @Volatile
    private var instance: OverlayStateHolder? = null

    fun get(context: Context): OverlayStateHolder =
        instance ?: synchronized(this) {
            instance ?: OverlayStateHolder(PrefsOverlaySettingsStore(context.applicationContext)).also { instance = it }
        }
}

private class PrefsOverlaySettingsStore(context: Context) : OverlaySettingsStore {
    private val prefs = context.getSharedPreferences("overlay_window", Context.MODE_PRIVATE)

    override fun load(): OverlaySettings? {
        if (!prefs.contains("width")) return null
        return OverlaySettings(
            x = prefs.getInt("x", 24),
            y = prefs.getInt("y", 160),
            widthPx = prefs.getInt("width", 0),
            opacity = prefs.getFloat("opacity", OverlayStateHolder.DEFAULT_OPACITY),
            locked = prefs.getBoolean("locked", false),
            flipped = prefs.getBoolean("flipped", false),
            engineTimeMs = prefs.getLong("engine_ms", OverlayStateHolder.DEFAULT_ENGINE_TIME_MS)
        )
    }

    override fun save(settings: OverlaySettings) {
        prefs.edit()
            .putInt("x", settings.x)
            .putInt("y", settings.y)
            .putInt("width", settings.widthPx)
            .putFloat("opacity", settings.opacity)
            .putBoolean("locked", settings.locked)
            .putBoolean("flipped", settings.flipped)
            .putLong("engine_ms", settings.engineTimeMs)
            .apply()
    }
}

/**
 * Foreground service hosting the floating analysis window over other apps (M7).
 *
 * It only draws the position the user entered (shared with the main screen) and the engine's suggestion.
 * It never captures other apps' screens, never reads other apps and never performs automated clicks.
 */
class OverlayService : LifecycleService(), SavedStateRegistryOwner {

    companion object {
        const val ACTION_STOP = "uz.draughtsai.overlay.STOP"
        const val ACTION_UNLOCK = "uz.draughtsai.overlay.UNLOCK"
        private const val CHANNEL_ID = "overlay_window"
        private const val NOTIFICATION_ID = 7

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private lateinit var holder: OverlayStateHolder
    private lateinit var windowManager: WindowManager
    private var boardView: ComposeView? = null
    private var bubbleView: ComposeView? = null
    private var boardParams: WindowManager.LayoutParams? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private val engine = DraughtsAI()
    private var searchJob: Job? = null
    private var searchCancel = AtomicBoolean(false)
    private var lastNotifiedLocked: Boolean? = null

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).roundToInt()

    /** Height of the window that is not the square board: title bar + eval line + button row. */
    private val extraHeightPx get() = dp(OVERLAY_EXTRA_HEIGHT_DP)

    override fun onCreate() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        super.onCreate()
        holder = OverlayRepository.get(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UNLOCK -> holder.setLocked(false)
        }
        val started = runCatching { startInForeground(holder.state.value.settings.locked) }.isSuccess
        if (!started || !Settings.canDrawOverlays(this)) {
            // Android 15 may refuse a sticky restart while no overlay is visible; or the permission was revoked.
            stopSelf()
            return START_NOT_STICKY
        }
        if (boardView == null) showWindows()
        return START_STICKY
    }

    override fun onDestroy() {
        searchCancel.set(true)
        searchJob?.cancel()
        removeView(bubbleView)
        removeView(boardView)
        bubbleView = null
        boardView = null
        holder.overlayRunning.value = false
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation: keep the window inside the new screen bounds.
        val s = holder.state.value.settings
        applyGeometry(s.x, s.y, s.widthPx)
    }

    // ---- Notification ----------------------------------------------------------------------

    private fun startInForeground(locked: Boolean) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Shashka oynasi", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(locked), type)
        lastNotifiedLocked = locked
    }

    private fun updateNotification(locked: Boolean) {
        if (lastNotifiedLocked == locked) return
        lastNotifiedLocked = locked
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(locked))
    }

    private fun buildNotification(locked: Boolean): Notification {
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), piFlags
        )
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), piFlags
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Shashka oynasi ochiq")
            .setContentText(if (locked) "Qulflangan: teginishlar pastdagi ilovaga o'tadi" else "Tahlil oynasi ekran ustida")
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(0, "Yopish", stopPi)
        if (locked) {
            val unlockPi = PendingIntent.getService(
                this, 2, Intent(this, OverlayService::class.java).setAction(ACTION_UNLOCK), piFlags
            )
            builder.addAction(0, "Qulfni ochish", unlockPi)
        }
        return builder.build()
    }

    // ---- Windows ---------------------------------------------------------------------------

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun newComposeView(content: @Composable () -> Unit): ComposeView =
        ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent(content)
        }

    private fun showWindows() {
        val s = holder.state.value.settings
        val defaultWidth = minOf(dp(240), resources.displayMetrics.widthPixels)
        val params = WindowManager.LayoutParams(
            if (s.widthPx > 0) s.widthPx else defaultWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = s.x
            y = s.y
        }
        boardParams = params
        val view = newComposeView {
            OverlayWindowContent(
                holder = holder,
                onDragBy = { dx, dy -> moveBy(dx, dy) },
                onResizeBy = { dw -> resizeBy(dw) },
                onGestureEnd = { persistGeometry() },
                onOpenApp = {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                },
                onClose = { stopSelf() }
            )
        }
        boardView = view
        windowManager.addView(view, params)
        holder.overlayRunning.value = true
        applyGeometry(params.x, params.y, params.width)

        // React to lock / opacity changes.
        lifecycleScope.launch {
            holder.state.map { it.settings.locked to it.effectiveOpacity }.distinctUntilChanged().collect { (locked, alpha) ->
                applyLockAndOpacity(locked, alpha)
                updateNotification(locked)
            }
        }
        // Engine: one background search per position, newest position cancels the previous one.
        lifecycleScope.launch {
            holder.state.map { it.board to it.settings.engineTimeMs }.distinctUntilChanged().collect { (board, timeMs) ->
                analyze(board, timeMs)
            }
        }
    }

    private fun analyze(board: Board, timeMs: Long) {
        searchCancel.set(true)
        searchJob?.cancel()
        if (RussianDraughtsRules.gameStatus(board) != GameStatus.ONGOING) return
        val cancel = AtomicBoolean(false)
        searchCancel = cancel
        searchJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                engine.findBestMove(board = board, config = SearchConfig(maxDepth = 20, timeLimitMs = timeMs), cancelFlag = cancel)
            }
            if (!cancel.get()) {
                holder.setAnalysis(OverlayAnalysis(board, result.bestMove, result.formattedScore(), result.completedDepth))
            }
        }
    }

    private fun applyLockAndOpacity(locked: Boolean, alpha: Float) {
        val view = boardView ?: return
        val params = boardParams ?: return
        params.alpha = alpha
        params.flags = if (locked) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        windowManager.updateViewLayout(view, params)
        if (locked) showBubble() else hideBubble()
    }

    private fun showBubble() {
        if (bubbleView != null) {
            positionBubble()
            return
        }
        val size = dp(BUBBLE_SIZE_DP)
        val params = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        bubbleParams = params
        val view = newComposeView {
            UnlockBubble(onUnlock = { holder.setLocked(false) }, onClose = { stopSelf() })
        }
        bubbleView = view
        positionBubble()
        windowManager.addView(view, params)
    }

    private fun positionBubble() {
        val bp = boardParams ?: return
        val params = bubbleParams ?: return
        val size = dp(BUBBLE_SIZE_DP)
        // Top-right corner of the board window, kept on screen.
        val screenW = resources.displayMetrics.widthPixels
        params.x = (bp.x + bp.width - size).coerceIn(0, (screenW - size).coerceAtLeast(0))
        params.y = (bp.y - size / 2).coerceAtLeast(0)
        bubbleView?.let { if (it.isAttachedToWindow) windowManager.updateViewLayout(it, params) }
    }

    private fun hideBubble() {
        removeView(bubbleView)
        bubbleView = null
        bubbleParams = null
    }

    private fun removeView(view: View?) {
        if (view != null && view.isAttachedToWindow) {
            runCatching { windowManager.removeView(view) }
        }
    }

    private fun moveBy(dx: Float, dy: Float) {
        val p = boardParams ?: return
        applyGeometry((p.x + dx).roundToInt(), (p.y + dy).roundToInt(), p.width, persist = false)
    }

    private fun resizeBy(dw: Float) {
        val p = boardParams ?: return
        applyGeometry(p.x, p.y, (p.width + dw).roundToInt(), persist = false)
    }

    private fun persistGeometry() {
        val p = boardParams ?: return
        holder.setGeometry(p.x, p.y, p.width)
    }

    private fun applyGeometry(x: Int, y: Int, width: Int, persist: Boolean = true) {
        val view = boardView ?: return
        val p = boardParams ?: return
        val dm = resources.displayMetrics
        val r = OverlayGeometry.clamp(
            x = x, y = y, width = if (width > 0) width else dp(240),
            extraHeightPx = extraHeightPx,
            screenWidth = dm.widthPixels, screenHeight = dm.heightPixels,
            minWidthPx = dp(MIN_WIDTH_DP)
        )
        p.x = r.x
        p.y = r.y
        p.width = r.width
        if (view.isAttachedToWindow) windowManager.updateViewLayout(view, p)
        positionBubble()
        if (persist) holder.setGeometry(r.x, r.y, r.width)
    }
}

private const val MIN_WIDTH_DP = 160
private const val BUBBLE_SIZE_DP = 40
private const val OVERLAY_EXTRA_HEIGHT_DP = 96

/**
 * Drag using raw screen coordinates, so moving the window under the finger does not distort the deltas.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.rawDrag(onDelta: (Float, Float) -> Unit, onEnd: () -> Unit): Modifier = composed {
    val last = remember { FloatArray(2) }
    pointerInteropFilter { ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                last[0] = ev.rawX; last[1] = ev.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                onDelta(ev.rawX - last[0], ev.rawY - last[1])
                last[0] = ev.rawX; last[1] = ev.rawY
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onEnd()
        }
        true
    }
}

@Composable
private fun OverlayWindowContent(
    holder: OverlayStateHolder,
    onDragBy: (Float, Float) -> Unit,
    onResizeBy: (Float) -> Unit,
    onGestureEnd: () -> Unit,
    onOpenApp: () -> Unit,
    onClose: () -> Unit
) {
    val state by holder.state.collectAsState()
    val settings = state.settings
    var showOpacity by remember { mutableStateOf(false) }
    val analysis = state.currentAnalysis

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xF01E2430))
    ) {
        // Title bar = drag handle.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(Color(0xFF2A3140))
                .rawDrag(onDragBy, onGestureEnd)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("⠿ Shashka", color = Color(0xFFD4A359), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(
                if (state.board.sideToMove == Side.WHITE) "Oq yuradi" else "Qora yuradi",
                color = Color.LightGray, fontSize = 11.sp
            )
        }
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            MiniBoard(
                board = state.board,
                flipped = settings.flipped,
                selected = state.selected,
                bestMove = analysis?.bestMove,
                onTap = { holder.tapSquare(it) }
            )
        }
        Text(
            text = "Eng yaxshi: ${analysis?.bestMove?.toNotation() ?: "…"}   ${analysis?.scoreText ?: ""}" +
                (analysis?.let { " d=${it.depth}" } ?: ""),
            color = Color.White, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
        if (showOpacity) {
            Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("Ko'rinish: ${(settings.opacity * 100).roundToInt()}%", color = Color.LightGray, fontSize = 11.sp)
                Slider(
                    value = settings.opacity,
                    onValueChange = { holder.setOpacity(it) },
                    valueRange = OverlayStateHolder.MIN_OPACITY..OverlayStateHolder.MAX_OPACITY
                )
                Text(
                    "Qulflanganda ko'pi bilan 80% (Android 12+ shundan shaffofroq oynadan teginishni o'tkazadi)",
                    color = if (settings.opacity > OverlayStateHolder.MAX_LOCKED_OPACITY) Color(0xFFFFB74D) else Color.Gray,
                    fontSize = 10.sp
                )
                Text(
                    "Dvigatel vaqti: ${settings.engineTimeMs} ms (bosing)",
                    color = Color.LightGray, fontSize = 11.sp,
                    modifier = Modifier.clickable {
                        val next = when (settings.engineTimeMs) {
                            in 0L..300L -> 500L
                            in 301L..500L -> 800L
                            else -> 300L
                        }
                        holder.setEngineTimeMs(next)
                    }.padding(vertical = 4.dp)
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OverlayButton("↶", enabled = state.canUndo) { holder.undo() }
            OverlayButton("⇅") { holder.toggleFlip() }
            OverlayButton("◐") { showOpacity = !showOpacity }
            OverlayButton("🔒") {
                showOpacity = false
                holder.setLocked(true)
            }
            OverlayButton("⤢") { onOpenApp() }
            OverlayButton("✕") { onClose() }
            // Resize handle (bottom-right corner).
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .rawDrag({ dx, _ -> onResizeBy(dx) }, onGestureEnd),
                contentAlignment = Alignment.BottomEnd
            ) {
                Text("◢", color = Color.Gray, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun OverlayButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Color(0xFF2A3140))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (enabled) Color.White else Color.DarkGray, fontSize = 13.sp)
    }
}

@Composable
private fun UnlockBubble(onUnlock: () -> Unit, onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(CircleShape)
            .background(Color(0xFFD4A359))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onUnlock() }, onLongPress = { onClose() })
            },
        contentAlignment = Alignment.Center
    ) {
        Text("🔓", fontSize = 18.sp)
    }
}

@Composable
private fun MiniBoard(
    board: Board,
    flipped: Boolean,
    selected: Pos?,
    bestMove: Move?,
    onTap: (Pos) -> Unit
) {
    val legalFromSelected = remember(board, selected) {
        selected?.let { sel -> RussianDraughtsRules.legalMoves(board).filter { it.from == sel } } ?: emptyList()
    }
    androidx.compose.foundation.Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(flipped, onTap) {
                detectTapGestures { offset ->
                    val cell = size.width / 8f
                    val vc = (offset.x / cell).toInt().coerceIn(0, 7)
                    val vr = (offset.y / cell).toInt().coerceIn(0, 7)
                    onTap(if (flipped) Pos(7 - vr, 7 - vc) else Pos(vr, vc))
                }
            }
    ) {
        val cell = size.width / 8f
        fun center(p: Pos): Offset {
            val vr = if (flipped) 7 - p.row else p.row
            val vc = if (flipped) 7 - p.col else p.col
            return Offset((vc + 0.5f) * cell, (vr + 0.5f) * cell)
        }
        for (r in 0..7) for (c in 0..7) {
            val p = Pos(r, c)
            val vr = if (flipped) 7 - r else r
            val vc = if (flipped) 7 - c else c
            drawRect(
                color = if (p.isDarkSquare) Color(0xFF6E472B) else Color(0xFFE6D2B5),
                topLeft = Offset(vc * cell, vr * cell),
                size = androidx.compose.ui.geometry.Size(cell, cell)
            )
            if (p == selected) {
                drawRect(
                    color = Color(0xFFFFD54F),
                    topLeft = Offset(vc * cell, vr * cell),
                    size = androidx.compose.ui.geometry.Size(cell, cell),
                    style = Stroke(width = cell * 0.1f)
                )
            }
        }
        for (m in legalFromSelected) {
            drawCircle(Color(0xAA4CAF50), radius = cell * 0.18f, center = center(m.to))
        }
        for (p in Pos.PLAYABLE_SQUARES) {
            val piece = board[p] ?: continue
            val white = piece.side == Side.WHITE
            drawCircle(if (white) Color(0xFFF5F2EB) else Color(0xFF22252A), radius = cell * 0.38f, center = center(p))
            drawCircle(
                if (white) Color(0xFFB0A89B) else Color(0xFF555B66),
                radius = cell * 0.38f, center = center(p), style = Stroke(width = cell * 0.05f)
            )
            if (piece.isKing) drawCircle(Color(0xFFFFD54F), radius = cell * 0.15f, center = center(p))
        }
        // Best move arrow: polyline through the path + arrow head at the end.
        if (bestMove != null) {
            val green = Color(0xE600E676)
            val pts = bestMove.path.map { center(it) }
            for (i in 0 until pts.size - 1) {
                drawLine(green, pts[i], pts[i + 1], strokeWidth = cell * 0.09f)
            }
            val end = pts.last()
            val prev = pts[pts.size - 2]
            val dir = (end - prev).let { d ->
                val len = kotlin.math.sqrt(d.x * d.x + d.y * d.y).coerceAtLeast(1f)
                Offset(d.x / len, d.y / len)
            }
            val normal = Offset(-dir.y, dir.x)
            val head = cell * 0.32f
            val base = end - dir * head
            val path = Path().apply {
                moveTo(end.x, end.y)
                lineTo(base.x + normal.x * head * 0.6f, base.y + normal.y * head * 0.6f)
                lineTo(base.x - normal.x * head * 0.6f, base.y - normal.y * head * 0.6f)
                close()
            }
            drawPath(path, green)
        }
    }
}
