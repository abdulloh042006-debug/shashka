package uz.draughtsai

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Result of analyzing a user-selected screenshot of an 8x8 draughts board.
 * Always presented in an editable confirmation preview before analysis.
 */
data class ImportedBoardEstimate(
    val board: Board,
    val averageConfidence: Float,
    val lowConfidenceSquares: Set<Pos>
)

/**
 * Offline computer-vision helper for Milestone M5 (User-selected Screenshot Import).
 *
 * Strictly analyzes only bitmaps explicitly chosen by the user via Android's Photo Picker.
 * Detects the central square board region, samples the 32 playable dark squares, and estimates
 * whether each square is empty or contains a White/Black Man or King, along with a confidence
 * score so the user can review and correct the position before analysis.
 */
object ScreenshotImporter {

    /**
     * Analyzes a user-selected [bitmap] and returns a draft [Board] with confidence metrics.
     * If [flipped] is true, treats the board as viewed from Black's perspective (a1 at top-right).
     */
    fun analyzeBitmap(
        bitmap: Bitmap,
        sideToMove: Side = Side.WHITE,
        flipped: Boolean = false
    ): ImportedBoardEstimate {
        // 1. Locate square board region (central square crop if screenshot is portrait/landscape)
        val width = bitmap.width
        val height = bitmap.height
        val boardSize = min(width, height)
        val leftOffset = (width - boardSize) / 2
        val topOffset = (height - boardSize) / 2
        val cellSize = boardSize / 8f

        // 2. Measure reference background luminance of dark squares around cell corners
        var darkBgLumaSum = 0f
        var darkBgSamples = 0
        for (pos in Pos.PLAYABLE_SQUARES) {
            val cornerX = (leftOffset + (pos.col + 0.12f) * cellSize).toInt().coerceIn(0, width - 1)
            val cornerY = (topOffset + (pos.row + 0.12f) * cellSize).toInt().coerceIn(0, height - 1)
            darkBgLumaSum += luminance(bitmap.getPixel(cornerX, cornerY))
            darkBgSamples++
        }
        val bgLuma = if (darkBgSamples > 0) darkBgLumaSum / darkBgSamples else 0.35f

        var draftBoard = Board.empty(sideToMove)
        val lowConfidence = mutableSetOf<Pos>()
        var confidenceSum = 0f

        for (visualPos in Pos.PLAYABLE_SQUARES) {
            val logicalPos = if (flipped) {
                Pos(7 - visualPos.row, 7 - visualPos.col)
            } else {
                visualPos
            }

            val cx = leftOffset + (visualPos.col + 0.5f) * cellSize
            val cy = topOffset + (visualPos.row + 0.5f) * cellSize
            val radius = max(2f, cellSize * 0.28f)

            var ringLumaSum = 0f
            var ringLumaSqSum = 0f
            var centerLumaSum = 0f
            var ringCount = 0
            var centerCount = 0

            val step = max(1, (radius / 4f).toInt())
            val rInt = radius.toInt()
            for (dy in -rInt..rInt step step) {
                for (dx in -rInt..rInt step step) {
                    val distSq = dx * dx + dy * dy
                    if (distSq > rInt * rInt) continue
                    val px = (cx + dx).toInt().coerceIn(0, width - 1)
                    val py = (cy + dy).toInt().coerceIn(0, height - 1)
                    val luma = luminance(bitmap.getPixel(px, py))

                    if (distSq <= (rInt * 0.42f) * (rInt * 0.42f)) {
                        centerLumaSum += luma
                        centerCount++
                    } else {
                        ringLumaSum += luma
                        ringLumaSqSum += luma * luma
                        ringCount++
                    }
                }
            }

            val ringMean = if (ringCount > 0) ringLumaSum / ringCount else bgLuma
            val centerMean = if (centerCount > 0) centerLumaSum / centerCount else ringMean
            val variance = if (ringCount > 0) max(0f, ringLumaSqSum / ringCount - ringMean * ringMean) else 0f
            val deltaFromBg = abs(ringMean - bgLuma)

            // Decide if square is occupied or empty
            if (deltaFromBg < 0.11f && variance < 0.015f) {
                val conf = (1f - (deltaFromBg / 0.11f)).coerceIn(0.55f, 0.98f)
                confidenceSum += conf
            } else {
                val side = if (ringMean >= max(0.52f, bgLuma + 0.10f)) Side.WHITE else Side.BLACK
                // Crowned kings typically have a contrasting crown emblem in the center of the disc
                val centerContrast = abs(centerMean - ringMean)
                val kind = if (centerContrast > 0.16f) PieceKind.KING else PieceKind.MAN
                val conf = min(0.95f, 0.55f + deltaFromBg)

                draftBoard = draftBoard.setPiece(logicalPos, Piece(side, kind))
                confidenceSum += conf
                if (conf < 0.72f) {
                    lowConfidence.add(logicalPos)
                }
            }
        }

        return ImportedBoardEstimate(
            board = draftBoard,
            averageConfidence = (confidenceSum / 32f).coerceIn(0f, 1f),
            lowConfidenceSquares = lowConfidence
        )
    }

    private fun luminance(color: Int): Float {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b
    }
}
