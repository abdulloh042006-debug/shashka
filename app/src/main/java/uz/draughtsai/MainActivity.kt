package uz.draughtsai

import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val colorScheme = darkColorScheme(
                primary = Color(0xFFD4A359),
                secondary = Color(0xFF8AB4F8),
                background = Color(0xFF12151C),
                surface = Color(0xFF1C212C),
                onPrimary = Color(0xFF1A140B),
                onBackground = Color(0xFFECEFF4),
                onSurface = Color(0xFFECEFF4)
            )
            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DraughtsAiApp()
                }
            }
        }
    }
}

enum class AppMode(val title: String) {
    PLAY_AND_ANALYZE("Play & AI"),
    BOARD_EDITOR("Board Editor"),
    POSITIONS("Presets & FEN")
}

enum class EditorTool(val label: String) {
    MOVE_OR_SELECT("Move / Crown"),
    WHITE_MAN("White Man"),
    WHITE_KING("White King"),
    BLACK_MAN("Black Man"),
    BLACK_KING("Black King"),
    ERASE("Erase")
}

data class PresetPosition(
    val title: String,
    val description: String,
    val boardFactory: () -> Board
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DraughtsAiApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val aiEngine = remember { DraughtsAI() }

    // Board & History State
    var board by remember { mutableStateOf(Board.initial()) }
    val undoStack = remember { mutableStateListOf<Board>() }
    val redoStack = remember { mutableStateListOf<Board>() }
    val moveLog = remember { mutableStateListOf<String>() }

    // Selection & UI State
    var selectedMode by remember { mutableStateOf(AppMode.PLAY_AND_ANALYZE) }
    var selectedSquare by remember { mutableStateOf<Pos?>(null) }
    var lastMove by remember { mutableStateOf<Move?>(null) }
    var boardFlipped by remember { mutableStateOf(false) }

    // Editor State
    var editorTool by remember { mutableStateOf(EditorTool.WHITE_MAN) }
    var editorSelectedFrom by remember { mutableStateOf<Pos?>(null) }

    // AI State
    var autoAiBlack by remember { mutableStateOf(false) }
    var searchDepth by remember { mutableIntStateOf(7) }
    var isSearching by remember { mutableStateOf(false) }
    var latestSearchResult by remember { mutableStateOf<SearchResult?>(null) }
    var activeSearchJob by remember { mutableStateOf<Job?>(null) }
    var activeCancelFlag by remember { mutableStateOf(AtomicBoolean(false)) }

    // Optional User-Invoked Floating Mini Analysis HUD (in-app)
    var showFloatingHud by remember { mutableStateOf(false) }
    var showHudInfoDialog by remember { mutableStateOf(false) }

    // Screenshot Import Confirmation Preview State
    var importedEstimate by remember { mutableStateOf<ImportedBoardEstimate?>(null) }
    var statusMessage by remember { mutableStateOf("Classic Russian Draughts (8x8) — White to move") }

    // FEN Dialog State
    var fenInputText by remember { mutableStateOf("") }
    var showFenDialog by remember { mutableStateOf(false) }

    val legalMoves = remember(board) { RussianDraughtsRules.legalMoves(board) }
    val selectedSquareMoves = remember(board, selectedSquare) {
        selectedSquare?.let { sq -> legalMoves.filter { it.from == sq } } ?: emptyList()
    }
    val gameStatus = remember(board) { RussianDraughtsRules.gameStatus(board) }

    fun pushBoardChange(newBoard: Board, moveNotation: String? = null, executedMove: Move? = null) {
        activeCancelFlag.set(true)
        activeSearchJob?.cancel()
        isSearching = false

        undoStack.add(board)
        redoStack.clear()
        board = newBoard
        selectedSquare = null
        editorSelectedFrom = null
        lastMove = executedMove
        latestSearchResult = null
        if (moveNotation != null) {
            moveLog.add(moveNotation)
        }
    }

    fun triggerAiAnalysis(applyMoveWhenDone: Boolean) {
        activeCancelFlag.set(true)
        activeSearchJob?.cancel()

        val cancelFlag = AtomicBoolean(false)
        activeCancelFlag = cancelFlag
        isSearching = true
        val snapshot = board
        val depth = searchDepth

        activeSearchJob = scope.launch {
            val result = withContext(Dispatchers.Default) {
                aiEngine.findBestMove(
                    board = snapshot,
                    config = SearchConfig(maxDepth = depth, timeLimitMs = 2_500L),
                    cancelFlag = cancelFlag
                )
            }
            if (!cancelFlag.get()) {
                latestSearchResult = result
                isSearching = false
                if (applyMoveWhenDone && result.bestMove != null && board == snapshot) {
                    val move = result.bestMove
                    val next = RussianDraughtsRules.applyMove(snapshot, move)
                    pushBoardChange(next, move.toNotation(), move)
                    statusMessage = "AI played ${move.toNotation()} (depth ${result.completedDepth})"
                } else if (result.bestMove != null) {
                    statusMessage = "Best: ${result.bestMove.toNotation()} (${result.formattedScore()}, d=${result.completedDepth})"
                }
            }
        }
    }

    // Automatically analyze or play when in Play & AI mode
    LaunchedEffect(board, autoAiBlack, selectedMode) {
        if (selectedMode == AppMode.PLAY_AND_ANALYZE &&
            RussianDraughtsRules.gameStatus(board) == GameStatus.ONGOING
        ) {
            if (autoAiBlack && board.sideToMove == Side.BLACK) {
                triggerAiAnalysis(applyMoveWhenDone = true)
            } else {
                triggerAiAnalysis(applyMoveWhenDone = false)
            }
        }
    }

    // Android Photo Picker for explicit user screenshot import (Milestone M5)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val estimate = withContext(Dispatchers.IO) {
                    try {
                        val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            val src = ImageDecoder.createSource(context.contentResolver, uri)
                            ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
                                decoder.isMutableRequired = false
                                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                        }
                        ScreenshotImporter.analyzeBitmap(
                            bitmap = bmp,
                            sideToMove = board.sideToMove,
                            flipped = boardFlipped
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                if (estimate != null) {
                    importedEstimate = estimate
                } else {
                    statusMessage = "Could not decode selected screenshot."
                }
            }
        }
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Draughts AI — Русские шашки",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { boardFlipped = !boardFlipped }
                        ) {
                            Text("Flip")
                        }
                        OutlinedButton(
                            onClick = {
                                if (!showFloatingHud) {
                                    showHudInfoDialog = true
                                } else {
                                    showFloatingHud = false
                                }
                            }
                        ) {
                            Text(if (showFloatingHud) "Hide HUD" else "HUD")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                TabRow(selectedTabIndex = selectedMode.ordinal) {
                    AppMode.entries.forEach { mode ->
                        Tab(
                            selected = selectedMode == mode,
                            onClick = {
                                selectedMode = mode
                                selectedSquare = null
                                editorSelectedFrom = null
                            },
                            text = { Text(mode.title) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Piece count and turn banner
                StatusSummaryBar(
                    board = board,
                    gameStatus = gameStatus,
                    hasCompulsoryCapture = legalMoves.firstOrNull()?.isCapture == true,
                    isSearching = isSearching
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Interactive 8x8 Draughts Board
                DraughtsBoardCanvas(
                    board = board,
                    flipped = boardFlipped,
                    selectedSquare = if (selectedMode == AppMode.BOARD_EDITOR) editorSelectedFrom else selectedSquare,
                    candidateMoves = if (selectedMode == AppMode.PLAY_AND_ANALYZE) selectedSquareMoves else emptyList(),
                    bestAiMove = if (selectedMode == AppMode.PLAY_AND_ANALYZE) latestSearchResult?.bestMove else null,
                    lastMove = lastMove,
                    onSquareTap = { tappedPos ->
                        if (!tappedPos.isDarkSquare) return@DraughtsBoardCanvas

                        when (selectedMode) {
                            AppMode.PLAY_AND_ANALYZE, AppMode.POSITIONS -> {
                                // Check if tappedPos completes a legal move from selectedSquare
                                val matchingMoves = selectedSquareMoves.filter { it.to == tappedPos }
                                if (matchingMoves.isNotEmpty()) {
                                    val chosenMove = matchingMoves.maxByOrNull { it.captureCount } ?: matchingMoves.first()
                                    val nextBoard = RussianDraughtsRules.applyMove(board, chosenMove)
                                    pushBoardChange(nextBoard, chosenMove.toNotation(), chosenMove)
                                    statusMessage = "Played ${chosenMove.toNotation()}"
                                    return@DraughtsBoardCanvas
                                }

                                // Also check intermediate landing squares for multi-capture convenience
                                val intermediateMatch = selectedSquareMoves.firstOrNull { tappedPos in it.landings }
                                if (intermediateMatch != null && selectedSquareMoves.size == 1) {
                                    val nextBoard = RussianDraughtsRules.applyMove(board, intermediateMatch)
                                    pushBoardChange(nextBoard, intermediateMatch.toNotation(), intermediateMatch)
                                    statusMessage = "Played ${intermediateMatch.toNotation()}"
                                    return@DraughtsBoardCanvas
                                }

                                // Otherwise select own piece
                                val pieceAtTapped = board[tappedPos]
                                if (pieceAtTapped != null && pieceAtTapped.side == board.sideToMove) {
                                    selectedSquare = tappedPos
                                    val movesHere = legalMoves.filter { it.from == tappedPos }
                                    statusMessage = if (movesHere.isEmpty()) {
                                        if (legalMoves.firstOrNull()?.isCapture == true) {
                                            "Mandatory capture required with another piece!"
                                        } else {
                                            "No legal moves for ${tappedPos.toNotation()}"
                                        }
                                    } else {
                                        "Selected ${tappedPos.toNotation()} (${movesHere.size} legal move(s))"
                                    }
                                } else {
                                    selectedSquare = null
                                }
                            }

                            AppMode.BOARD_EDITOR -> {
                                when (editorTool) {
                                    EditorTool.WHITE_MAN -> {
                                        pushBoardChange(board.setPiece(tappedPos, Piece(Side.WHITE, PieceKind.MAN)))
                                    }
                                    EditorTool.WHITE_KING -> {
                                        pushBoardChange(board.setPiece(tappedPos, Piece(Side.WHITE, PieceKind.KING)))
                                    }
                                    EditorTool.BLACK_MAN -> {
                                        pushBoardChange(board.setPiece(tappedPos, Piece(Side.BLACK, PieceKind.MAN)))
                                    }
                                    EditorTool.BLACK_KING -> {
                                        pushBoardChange(board.setPiece(tappedPos, Piece(Side.BLACK, PieceKind.KING)))
                                    }
                                    EditorTool.ERASE -> {
                                        pushBoardChange(board.removePiece(tappedPos))
                                    }
                                    EditorTool.MOVE_OR_SELECT -> {
                                        val from = editorSelectedFrom
                                        if (from == null) {
                                            if (board[tappedPos] != null) {
                                                editorSelectedFrom = tappedPos
                                                statusMessage = "Editor: tap destination to reposition, or tap same square to crown/uncrown"
                                            }
                                        } else if (from == tappedPos) {
                                            pushBoardChange(board.togglePieceKind(tappedPos))
                                            statusMessage = "Toggled Man/King at ${tappedPos.toNotation()}"
                                        } else {
                                            pushBoardChange(board.repositionPiece(from, tappedPos))
                                            statusMessage = "Repositioned ${from.toNotation()} -> ${tappedPos.toNotation()}"
                                        }
                                    }
                                }
                            }
                        }
                    }
                )

                // If multiple capture routes share the same start square, show explicit route buttons
                if (selectedMode == AppMode.PLAY_AND_ANALYZE && selectedSquareMoves.size > 1) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "Available legal routes from ${selectedSquare?.toNotation()}:",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                selectedSquareMoves.forEach { moveOption ->
                                    OutlinedButton(
                                        onClick = {
                                            val next = RussianDraughtsRules.applyMove(board, moveOption)
                                            pushBoardChange(next, moveOption.toNotation(), moveOption)
                                            statusMessage = "Played ${moveOption.toNotation()}"
                                        }
                                    ) {
                                        Text(moveOption.toNotation())
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Quick History & Screenshot Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (undoStack.isNotEmpty()) {
                                val prev = undoStack.removeAt(undoStack.lastIndex)
                                redoStack.add(board)
                                board = prev
                                selectedSquare = null
                                lastMove = null
                            }
                        },
                        enabled = undoStack.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Undo")
                    }
                    OutlinedButton(
                        onClick = {
                            if (redoStack.isNotEmpty()) {
                                val next = redoStack.removeAt(redoStack.lastIndex)
                                undoStack.add(board)
                                board = next
                                selectedSquare = null
                            }
                        },
                        enabled = redoStack.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Redo")
                    }
                    OutlinedButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.weight(1.3f)
                    ) {
                        Text("Import Image")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Mode-Specific Panels
                when (selectedMode) {
                    AppMode.PLAY_AND_ANALYZE -> {
                        PlayAndAnalyzePanel(
                            board = board,
                            searchResult = latestSearchResult,
                            isSearching = isSearching,
                            searchDepth = searchDepth,
                            onDepthChange = { searchDepth = it },
                            autoAiBlack = autoAiBlack,
                            onAutoAiBlackChange = { autoAiBlack = it },
                            onAnalyzeNow = { triggerAiAnalysis(applyMoveWhenDone = false) },
                            onPlayBestMove = {
                                val best = latestSearchResult?.bestMove
                                if (best != null && RussianDraughtsRules.isLegalMove(board, best)) {
                                    val next = RussianDraughtsRules.applyMove(board, best)
                                    pushBoardChange(next, best.toNotation(), best)
                                    statusMessage = "Played best move ${best.toNotation()}"
                                } else {
                                    triggerAiAnalysis(applyMoveWhenDone = true)
                                }
                            },
                            onResetGame = {
                                moveLog.clear()
                                pushBoardChange(Board.initial())
                                statusMessage = "Reset to standard Russian Draughts starting position"
                            },
                            moveLog = moveLog
                        )
                    }

                    AppMode.BOARD_EDITOR -> {
                        BoardEditorPanel(
                            board = board,
                            currentTool = editorTool,
                            onSelectTool = {
                                editorTool = it
                                editorSelectedFrom = null
                            },
                            onToggleSideToMove = {
                                pushBoardChange(board.withSideToMove(board.sideToMove.opposite()))
                            },
                            onClearBoard = {
                                moveLog.clear()
                                pushBoardChange(Board.empty(board.sideToMove))
                                statusMessage = "Cleared board"
                            },
                            onResetInitial = {
                                moveLog.clear()
                                pushBoardChange(Board.initial())
                                statusMessage = "Reset to initial 12v12 board"
                            }
                        )
                    }

                    AppMode.POSITIONS -> {
                        PresetsAndFenPanel(
                            board = board,
                            onLoadBoard = { loaded, title ->
                                moveLog.clear()
                                pushBoardChange(loaded)
                                statusMessage = "Loaded: $title"
                                selectedMode = AppMode.PLAY_AND_ANALYZE
                            },
                            onOpenFenDialog = {
                                fenInputText = board.toFen()
                                showFenDialog = true
                            }
                        )
                    }
                }
            }

            // Optional User-Invoked Floating Mini Analysis Panel (Milestone M6)
            if (showFloatingHud) {
                Card(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .width(210.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xE61E2430)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Analysis HUD",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "✕",
                                modifier = Modifier.clickable { showFloatingHud = false },
                                color = Color.LightGray
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        val res = latestSearchResult
                        Text(
                            text = "Turn: ${board.sideToMove}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "Best: ${res?.bestMove?.toNotation() ?: "—"}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Eval: ${res?.formattedScore() ?: "..."} (d=${res?.completedDepth ?: 0})",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    // Transparent explanation dialog before enabling Floating HUD (Milestone M6 requirement)
    if (showHudInfoDialog) {
        AlertDialog(
            onDismissRequest = { showHudInfoDialog = false },
            title = { Text("Enable Compact Analysis HUD?") },
            text = {
                Text(
                    "This compact floating panel displays live AI evaluation and best-move " +
                        "recommendations strictly for positions you have manually entered or " +
                        "explicitly imported into Draughts AI. It never captures other apps' " +
                        "screens or performs automated clicks."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showHudInfoDialog = false
                        showFloatingHud = true
                    }
                ) {
                    Text("Enable HUD")
                }
            },
            dismissButton = {
                TextButton(onClick = { showHudInfoDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // FEN Import / Export Dialog (Milestone M4)
    if (showFenDialog) {
        AlertDialog(
            onDismissRequest = { showFenDialog = false },
            title = { Text("Position FEN Import / Export") },
            text = {
                Column {
                    Text(
                        text = "32 dark squares (w/W/b/B/.) followed by side to move (W/B):",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = fenInputText,
                        onValueChange = { fenInputText = it },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        try {
                            val parsed = Board.fromFen(fenInputText)
                            pushBoardChange(parsed)
                            statusMessage = "Imported position from FEN"
                            showFenDialog = false
                        } catch (e: Exception) {
                            statusMessage = "Invalid FEN: ${e.message}"
                        }
                    }
                ) {
                    Text("Load FEN")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFenDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Screenshot Import Editable Confirmation Dialog (Milestone M5 requirement)
    importedEstimate?.let { estimate ->
        AlertDialog(
            onDismissRequest = { importedEstimate = null },
            title = { Text("Confirm Imported Position") },
            text = {
                Column {
                    Text(
                        text = "Confidence: ${(estimate.averageConfidence * 100).toInt()}% • " +
                            "White: ${estimate.board.countPieces(Side.WHITE)} • " +
                            "Black: ${estimate.board.countPieces(Side.BLACK)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Please verify the detected pieces below. After importing, you can fine-tune any square in the Board Editor before analysis.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = estimate.board.toFen(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pushBoardChange(estimate.board)
                        importedEstimate = null
                        selectedMode = AppMode.BOARD_EDITOR
                        statusMessage = "Screenshot imported — verify in Editor or switch to Play & AI"
                    }
                ) {
                    Text("Import & Edit")
                }
            },
            dismissButton = {
                TextButton(onClick = { importedEstimate = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun StatusSummaryBar(
    board: Board,
    gameStatus: GameStatus,
    hasCompulsoryCapture: Boolean,
    isSearching: Boolean
) {
    val whiteMen = board.countPieces(Side.WHITE, PieceKind.MAN)
    val whiteKings = board.countPieces(Side.WHITE, PieceKind.KING)
    val blackMen = board.countPieces(Side.BLACK, PieceKind.MAN)
    val blackKings = board.countPieces(Side.BLACK, PieceKind.KING)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "⚪ White: $whiteMen" + if (whiteKings > 0) " (+$whiteKings♛)" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (board.sideToMove == Side.WHITE) FontWeight.Bold else FontWeight.Normal
                )
                Text(
                    text = when (gameStatus) {
                        GameStatus.WHITE_WINS -> "WHITE WINS!"
                        GameStatus.BLACK_WINS -> "BLACK WINS!"
                        GameStatus.ONGOING -> if (hasCompulsoryCapture) "CAPTURE REQUIRED" else "${board.sideToMove} TO MOVE"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (hasCompulsoryCapture) Color(0xFFFFB74D) else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "⚫ Black: $blackMen" + if (blackKings > 0) " (+$blackKings♛)" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (board.sideToMove == Side.BLACK) FontWeight.Bold else FontWeight.Normal
                )
            }
            if (isSearching) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DraughtsBoardCanvas(
    board: Board,
    flipped: Boolean,
    selectedSquare: Pos?,
    candidateMoves: List<Move>,
    bestAiMove: Move?,
    lastMove: Move?,
    onSquareTap: (Pos) -> Unit
) {
    val lightSquareColor = Color(0xFFE6D2B5)
    val darkSquareColor = Color(0xFF6E472B)
    val selectedBorderColor = Color(0xFFFFD54F)
    val landingHighlightColor = Color(0xAA4CAF50)
    val captureVictimHighlightColor = Color(0x99E53935)
    val lastMoveHighlightColor = Color(0x5542A5F5)
    val aiArrowColor = Color(0xCC00E676)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .border(2.dp, Color(0xFF8D6E63), RoundedCornerShape(10.dp))
                .pointerInput(flipped, board, selectedSquare, candidateMoves) {
                    detectTapGestures { offset ->
                        val cellW = size.width / 8f
                        val cellH = size.height / 8f
                        val visCol = (offset.x / cellW).toInt().coerceIn(0, 7)
                        val visRow = (offset.y / cellH).toInt().coerceIn(0, 7)
                        val actualPos = if (flipped) {
                            Pos(7 - visRow, 7 - visCol)
                        } else {
                            Pos(visRow, visCol)
                        }
                        onSquareTap(actualPos)
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cellW = size.width / 8f
                val cellH = size.height / 8f

                fun toVisualCenter(pos: Pos): Offset {
                    val vr = if (flipped) 7 - pos.row else pos.row
                    val vc = if (flipped) 7 - pos.col else pos.col
                    return Offset((vc + 0.5f) * cellW, (vr + 0.5f) * cellH)
                }

                // 1. Draw 64 squares
                for (vr in 0..7) {
                    for (vc in 0..7) {
                        val r = if (flipped) 7 - vr else vr
                        val c = if (flipped) 7 - vc else vc
                        val pos = Pos(r, c)
                        val topLeft = Offset(vc * cellW, vr * cellH)
                        val cellSize = Size(cellW, cellH)

                        drawRect(
                            color = if (pos.isDarkSquare) darkSquareColor else lightSquareColor,
                            topLeft = topLeft,
                            size = cellSize
                        )

                        if (lastMove != null && (pos == lastMove.from || pos == lastMove.to)) {
                            drawRect(
                                color = lastMoveHighlightColor,
                                topLeft = topLeft,
                                size = cellSize
                            )
                        }

                        if (pos == selectedSquare) {
                            drawRect(
                                color = selectedBorderColor,
                                topLeft = topLeft,
                                size = cellSize,
                                style = Stroke(width = cellW * 0.09f)
                            )
                        }
                    }
                }

                // 2. Highlight candidate landings and captured victims
                val victimSquares = candidateMoves.flatMap { it.captured }.toSet()
                for (victim in victimSquares) {
                    val center = toVisualCenter(victim)
                    drawCircle(
                        color = captureVictimHighlightColor,
                        radius = cellW * 0.44f,
                        center = center
                    )
                }

                for (move in candidateMoves) {
                    for (stepPos in move.landings) {
                        val isFinal = stepPos == move.to
                        val center = toVisualCenter(stepPos)
                        drawCircle(
                            color = landingHighlightColor,
                            radius = if (isFinal) cellW * 0.22f else cellW * 0.13f,
                            center = center
                        )
                    }
                }

                // 3. Draw Pieces (Men & Kings)
                for (pos in Pos.PLAYABLE_SQUARES) {
                    val piece = board[pos] ?: continue
                    val center = toVisualCenter(pos)
                    val radius = cellW * 0.38f

                    // Drop shadow
                    drawCircle(
                        color = Color(0x66000000),
                        radius = radius,
                        center = center + Offset(cellW * 0.03f, cellH * 0.04f)
                    )

                    val baseColor = if (piece.side == Side.WHITE) Color(0xFFF5F2EB) else Color(0xFF22252A)
                    val rimColor = if (piece.side == Side.WHITE) Color(0xFFB0A89B) else Color(0xFF555B66)

                    drawCircle(
                        color = baseColor,
                        radius = radius,
                        center = center
                    )
                    drawCircle(
                        color = rimColor,
                        radius = radius,
                        center = center,
                        style = Stroke(width = cellW * 0.045f)
                    )
                    drawCircle(
                        color = rimColor.copy(alpha = 0.6f),
                        radius = radius * 0.72f,
                        center = center,
                        style = Stroke(width = cellW * 0.025f)
                    )

                    // Crown emblem for Flying Kings
                    if (piece.isKing) {
                        val crownColor = if (piece.side == Side.WHITE) Color(0xFFD9822B) else Color(0xFFFFD54F)
                        drawCircle(
                            color = crownColor,
                            radius = radius * 0.36f,
                            center = center
                        )
                        drawCircle(
                            color = baseColor,
                            radius = radius * 0.15f,
                            center = center
                        )
                    }
                }

                // 4. Draw AI Best Move path indicator
                if (bestAiMove != null && bestAiMove.path.size >= 2) {
                    for (i in 0 until bestAiMove.path.size - 1) {
                        val start = toVisualCenter(bestAiMove.path[i])
                        val end = toVisualCenter(bestAiMove.path[i + 1])
                        drawLine(
                            color = aiArrowColor,
                            start = start,
                            end = end,
                            strokeWidth = cellW * 0.07f
                        )
                        drawCircle(
                            color = aiArrowColor,
                            radius = cellW * 0.10f,
                            center = end
                        )
                    }
                }
            }
        }

        // File coordinate labels (a..h)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            val files = if (flipped) ('h' downTo 'a') else ('a'..'h')
            for (f in files) {
                Text(
                    text = f.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
private fun PlayAndAnalyzePanel(
    board: Board,
    searchResult: SearchResult?,
    isSearching: Boolean,
    searchDepth: Int,
    onDepthChange: (Int) -> Unit,
    autoAiBlack: Boolean,
    onAutoAiBlackChange: (Boolean) -> Unit,
    onAnalyzeNow: () -> Unit,
    onPlayBestMove: () -> Unit,
    onResetGame: () -> Unit,
    moveLog: List<String>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "AI Engine Analysis",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (searchResult != null) {
                            "Best: ${searchResult.bestMove?.toNotation() ?: "None"} • " +
                                "Score: ${searchResult.formattedScore()} • " +
                                "Depth: ${searchResult.completedDepth} • " +
                                "${searchResult.nodesSearched} nodes (${searchResult.elapsedMs} ms)"
                        } else {
                            "Calculating legal moves..."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (searchResult != null && searchResult.principalVariation.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "PV: " + searchResult.principalVariation.joinToString(" → ") { it.toNotation() },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color.LightGray
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPlayBestMove,
                    enabled = !isSearching && RussianDraughtsRules.gameStatus(board) == GameStatus.ONGOING,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Play Best Move")
                }
                OutlinedButton(
                    onClick = onAnalyzeNow,
                    enabled = !isSearching,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Re-Analyze")
                }
                OutlinedButton(
                    onClick = onResetGame
                ) {
                    Text("New")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Auto-Play AI as Black",
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked = autoAiBlack,
                    onCheckedChange = onAutoAiBlackChange
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Search Depth: $searchDepth",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(110.dp)
                )
                Slider(
                    value = searchDepth.toFloat(),
                    onValueChange = { onDepthChange(it.toInt()) },
                    valueRange = 2f..10f,
                    steps = 7,
                    modifier = Modifier.weight(1f)
                )
            }

            if (moveLog.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Move History:",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    moveLog.chunked(2).forEachIndexed { index, pair ->
                        val whiteMove = pair.getOrNull(0) ?: ""
                        val blackMove = pair.getOrNull(1) ?: ""
                        Text(
                            text = "${index + 1}. $whiteMove $blackMove",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardEditorPanel(
    board: Board,
    currentTool: EditorTool,
    onSelectTool: (EditorTool) -> Unit,
    onToggleSideToMove: () -> Unit,
    onClearBoard: () -> Unit,
    onResetInitial: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Manual Position Editor (Playable Dark Squares Only)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                EditorTool.entries.forEach { tool ->
                    FilterChip(
                        selected = currentTool == tool,
                        onClick = { onSelectTool(tool) },
                        label = { Text(tool.label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onToggleSideToMove,
                    modifier = Modifier.weight(1.2f)
                ) {
                    Text("Turn: ${board.sideToMove}")
                }
                OutlinedButton(
                    onClick = onClearBoard,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear")
                }
                OutlinedButton(
                    onClick = onResetInitial,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Standard")
                }
            }
        }
    }
}

@Composable
private fun PresetsAndFenPanel(
    board: Board,
    onLoadBoard: (Board, String) -> Unit,
    onOpenFenDialog: () -> Unit
) {
    val presets = remember {
        listOf(
            PresetPosition(
                title = "Turkish Strike (Турецкий удар)",
                description = "Captured pieces remain on the board during multi-capture and block the king from capturing or passing through the same piece twice.",
                boardFactory = {
                    Board.fromMap(
                        pieces = mapOf(
                            Pos.fromNotation("d2") to Piece(Side.WHITE, PieceKind.KING),
                            Pos.fromNotation("e3") to Piece(Side.BLACK, PieceKind.MAN),
                            Pos.fromNotation("e5") to Piece(Side.BLACK, PieceKind.MAN),
                            Pos.fromNotation("c5") to Piece(Side.BLACK, PieceKind.MAN),
                            Pos.fromNotation("c3") to Piece(Side.BLACK, PieceKind.MAN)
                        ),
                        sideToMove = Side.WHITE
                    )
                }
            ),
            PresetPosition(
                title = "Mid-Capture Promotion to Flying King",
                description = "White man at d6 jumps e7 to f8, promotes immediately to a King, and continues capturing c5 along the long diagonal.",
                boardFactory = {
                    Board.fromMap(
                        pieces = mapOf(
                            Pos.fromNotation("d6") to Piece(Side.WHITE, PieceKind.MAN),
                            Pos.fromNotation("e7") to Piece(Side.BLACK, PieceKind.MAN),
                            Pos.fromNotation("c5") to Piece(Side.BLACK, PieceKind.MAN)
                        ),
                        sideToMove = Side.WHITE
                    )
                }
            ),
            PresetPosition(
                title = "Flying King Continuation Rule",
                description = "White king at a1 captures c3 and must land on e5 (not d4/f6) to continue capturing g3.",
                boardFactory = {
                    Board.fromMap(
                        pieces = mapOf(
                            Pos.fromNotation("a1") to Piece(Side.WHITE, PieceKind.KING),
                            Pos.fromNotation("c3") to Piece(Side.BLACK, PieceKind.MAN),
                            Pos.fromNotation("f4") to Piece(Side.BLACK, PieceKind.MAN)
                        ),
                        sideToMove = Side.WHITE
                    )
                }
            ),
            PresetPosition(
                title = "Classic Russian Capture Choice",
                description = "White can choose between capturing 1 piece (c3:a5) or 2 pieces (c3:e5:g3); classic Russian rules allow choosing either route.",
                boardFactory = {
                    Board.fromMap(
                        pieces = mapOf(
                            Pos.fromNotation("c3") to Piece(Side.WHITE, PieceKind.MAN),
                            Pos.fromNotation("b4") to Piece(Side.BLACK, PieceKind.MAN),
                            Pos.fromNotation("d4") to Piece(Side.BLACK, PieceKind.MAN),
                            Pos.fromNotation("f4") to Piece(Side.BLACK, PieceKind.MAN)
                        ),
                        sideToMove = Side.WHITE
                    )
                }
            )
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Tactical Presets & FEN Save/Load",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Button(onClick = onOpenFenDialog) {
                    Text("FEN Import/Export")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            presets.forEach { preset ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF252B38))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = preset.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = preset.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { onLoadBoard(preset.boardFactory(), preset.title) }
                        ) {
                            Text("Load")
                        }
                    }
                }
            }
        }
    }
}
