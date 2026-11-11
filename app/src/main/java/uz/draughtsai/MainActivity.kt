package uz.draughtsai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DraughtsAiScreen() }
    }
}

@Composable
private fun DraughtsAiScreen() {
    var board by remember { mutableStateOf(Board.initial()) }
    var selectedSide by remember { mutableStateOf(Side.WHITE) }
    var editMode by remember { mutableStateOf(true) }
    var selectedSquare by remember { mutableStateOf<Square?>(null) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var analysis by remember { mutableStateOf<SearchResult?>(null) }
    val scope = rememberCoroutineScope()

    var status by remember {
        mutableStateOf("Donalarni joylashtiring yoki holatni tahrirlang, so‘ng AI tahlilini ishga tushiring.")
    }

    val background = Color(0xFF10151D)
    val gold = Color(0xFFD6B36A)

    MaterialTheme(colorScheme = darkColorScheme(primary = gold)) {
        Surface(Modifier.fillMaxSize(), color = background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "SHASHKA AI",
                    color = Color(0xFFF1D18A),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    "Classic Russian Draughts · Offline Analysis",
                    color = Color(0xFFABB6C5),
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = editMode,
                        onClick = {
                            editMode = true
                            selectedSquare = null
                        },
                        label = { Text("Edit position") }
                    )
                    FilterChip(
                        selected = !editMode,
                        onClick = {
                            editMode = false
                            selectedSquare = null
                        },
                        label = { Text("Reposition") }
                    )
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Turn: ${board.sideToMove}", color = Color.White)
                    Text(
                        if (editMode) "Editor" else "Reposition mode",
                        color = Color(0xFFB6C2D1)
                    )
                }
                Spacer(Modifier.height(8.dp))

                Column(
                    Modifier.fillMaxWidth().aspectRatio(1f).border(2.dp, gold)
                ) {
                    for (row in 0..7) {
                        Row(Modifier.weight(1f)) {
                            for (col in 0..7) {
                                val square = Square(row, col)
                                val piece = board[square]
                                val playable = square.isPlayable

                                Box(
                                    Modifier.weight(1f)
                                        .fillMaxHeight()
                                        .background(
                                            if (playable) Color(0xFF806044)
                                            else Color(0xFFE7D4B5)
                                        )
                                        .then(
                                            if (selectedSquare == square) {
                                                Modifier.border(3.dp, gold)
                                            } else Modifier
                                        )
                                        .clickable(enabled = !isAnalyzing) {
                                            if (!playable) {
                                                status = "Only dark squares can contain pieces."
                                            } else if (editMode) {
                                                val nextPiece = when (piece) {
                                                    Piece.EMPTY -> Piece.man(selectedSide)
                                                    Piece.WHITE_MAN -> Piece.WHITE_KING
                                                    Piece.WHITE_KING -> Piece.BLACK_MAN
                                                    Piece.BLACK_MAN -> Piece.BLACK_KING
                                                    Piece.BLACK_KING -> Piece.EMPTY
                                                }
                                                board = board.withPiece(square, nextPiece)
                                                analysis = null
                                                status = "Square ${'a' + col}${8 - row}: ${nextPiece.name}"
                                            } else {
                                                val source = selectedSquare
                                                if (source == null) {
                                                    if (piece != Piece.EMPTY) {
                                                        selectedSquare = square
                                                        status = "Selected ${'a' + col}${8 - row}."
                                                    } else {
                                                        status = "Select a piece first."
                                                    }
                                                } else if (source == square) {
                                                    selectedSquare = null
                                                    status = "Selection cleared."
                                                } else if (piece == Piece.EMPTY) {
                                                    board = board.movePieceForEditing(source, square)
                                                    selectedSquare = null
                                                    analysis = null
                                                    status = "Repositioned in editor. Legal game rules are not checked."
                                                } else {
                                                    selectedSquare = square
                                                    status = "Selection changed."
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (piece != Piece.EMPTY) {
                                        val isWhite = piece.side == Side.WHITE
                                        Box(
                                            Modifier.fillMaxSize(0.76f)
                                                .background(
                                                    if (isWhite) Color(0xFFF4F0E8)
                                                    else Color(0xFF20242B),
                                                    CircleShape
                                                )
                                                .border(
                                                    2.dp,
                                                    if (isWhite) Color(0xFFB7B1A6)
                                                    else Color(0xFF090B0E),
                                                    CircleShape
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (piece.isKing) {
                                                Text(
                                                    "♛",
                                                    color = if (isWhite) Color.DarkGray else gold,
                                                    fontSize = 20.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { selectedSide = Side.WHITE },
                        modifier = Modifier.weight(1f),
                        enabled = !isAnalyzing
                    ) { Text("Place white") }
                    OutlinedButton(
                        onClick = { selectedSide = Side.BLACK },
                        modifier = Modifier.weight(1f),
                        enabled = !isAnalyzing
                    ) { Text("Place black") }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            board = board.withTurn(Side.WHITE)
                            analysis = null
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isAnalyzing
                    ) { Text("White turn") }
                    OutlinedButton(
                        onClick = {
                            board = board.withTurn(Side.BLACK)
                            analysis = null
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isAnalyzing
                    ) { Text("Black turn") }
                }

                Button(
                    onClick = {
                        board = Board.initial()
                        selectedSquare = null
                        selectedSide = Side.WHITE
                        analysis = null
                        status = "Starting position restored."
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isAnalyzing
                ) { Text("Reset board") }

                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val positionSnapshot = board
                        isAnalyzing = true
                        analysis = null
                        status = "AI positionni tahlil qilmoqda..."
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.Default) {
                                    RussianDraughtsAi().search(
                                        positionSnapshot,
                                        SearchLimits(
                                            maxDepth = 10,
                                            timeLimitMillis = 1_500,
                                            quiescenceDepth = 10,
                                            maxNodes = 400_000
                                        )
                                    )
                                }
                                if (board == positionSnapshot) {
                                    analysis = result
                                    status = if (result.bestMove == null) {
                                        "Bu holatda yurish yo‘q. Navbatdagi tomon yutqazgan."
                                    } else {
                                        "Tahlil yakunlandi."
                                    }
                                } else {
                                    status = "Tahlil paytida pozitsiya o‘zgardi. Yangi holatni qayta tahlil qiling."
                                }
                            } catch (error: Exception) {
                                status = "Tahlil xatosi: ${error.message ?: "noma’lum xato"}"
                            } finally {
                                isAnalyzing = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isAnalyzing
                ) {
                    Text(if (isAnalyzing) "Analyzing..." else "Analyze best move")
                }

                analysis?.let { result ->
                    Spacer(Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF1B2634)
                        )
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(
                                "AI tavsiyasi",
                                color = Color(0xFFF1D18A),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                result.bestMove?.let(::formatMove) ?: "Legal move topilmadi",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Baholash: ${formatScore(result.score)} · chuqurlik: ${result.completedDepth}",
                                color = Color(0xFFCFD8E3),
                                fontSize = 12.sp
                            )
                            Text(
                                "Qidirilgan tugunlar: ${result.nodes} · vaqt: ${result.elapsedMillis} ms",
                                color = Color(0xFFABB6C5),
                                fontSize = 12.sp
                            )
                            if (result.principalVariation.isNotEmpty()) {
                                Text(
                                    "Asosiy variant: " + result.principalVariation.joinToString("  ") { formatMove(it) },
                                    color = Color(0xFFCFD8E3),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(status, color = Color(0xFFB6C2D1), fontSize = 12.sp)
                Text(
                    "Offline tahlil: faqat siz kiritgan yoki tahrirlagan pozitsiya. Avtomatik yurish yoki boshqa ilovani kuzatish yo‘q.",
                    color = Color(0xFF8793A3),
                    fontSize = 11.sp
                )
            }
        }
    }
}

private fun formatMove(move: DraughtsMove): String {
    val start = formatSquare(move.from)
    val separator = if (move.isCapture) " × " else " → "
    return start + separator + move.landings.joinToString(separator) { formatSquare(it) }
}

private fun formatSquare(square: Square): String =
    "${'a' + square.col}${8 - square.row}"

private fun formatScore(score: Int): String {
    return when {
        score >= 90_000 -> "MATE +"
        score <= -90_000 -> "MATE −"
        else -> {
            val value = score / 100.0
            val sign = if (value > 0) "+" else ""
            sign + String.format(java.util.Locale.US, "%.2f", value)
        }
    }
}
