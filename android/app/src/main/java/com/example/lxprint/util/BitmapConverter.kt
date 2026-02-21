package com.example.lxprint.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

data class PrintBitmapData(
    val bitmap: ByteArray,
    val printLength: Int,
)

object BitmapConverter {

    private const val IMAGE_WIDTH = 384
    private const val ROW_BYTES = IMAGE_WIDTH / 8  // 48
    private const val PADDED_ROW_BYTES = 96  // printer head is 768px = 96 bytes
    private const val PACKET_SIZE = 100
    private const val LINE_HEADER_SIZE = 3  // 0x55 + 2-byte line index
    private const val DATA_PER_PACKET = PADDED_ROW_BYTES + LINE_HEADER_SIZE  // 99, fits in 100

    fun textToBitmap(text: String, textSizePx: Float = 190f, padding: Int = 4): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = textSizePx
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
        }

        val lines = text.split("\n")
        val lineHeight = paint.fontSpacing
        val fm = paint.fontMetrics
        val height = (-fm.ascent + lineHeight * (lines.size - 1) + fm.descent).toInt().coerceAtLeast(1)

        val bmp = Bitmap.createBitmap(IMAGE_WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        for ((i, line) in lines.withIndex()) {
            val y = -fm.ascent + lineHeight * i
            canvas.drawText(line, IMAGE_WIDTH / 2f, y, paint)
        }

        // Scan for ink bounds (first/last rows containing non-white pixels)
        val pixels = IntArray(IMAGE_WIDTH * height)
        bmp.getPixels(pixels, 0, IMAGE_WIDTH, 0, 0, IMAGE_WIDTH, height)
        val white = Color.WHITE

        var topRow = 0
        findTop@ for (y in 0 until height) {
            for (x in 0 until IMAGE_WIDTH) {
                if (pixels[y * IMAGE_WIDTH + x] != white) {
                    topRow = y; break@findTop
                }
            }
        }
        var bottomRow = height - 1
        findBottom@ for (y in height - 1 downTo topRow) {
            for (x in 0 until IMAGE_WIDTH) {
                if (pixels[y * IMAGE_WIDTH + x] != white) {
                    bottomRow = y; break@findBottom
                }
            }
        }

        // Crop to ink bounds + padding
        val cropTop = (topRow - padding).coerceAtLeast(0)
        val cropBottom = (bottomRow + padding).coerceAtMost(height - 1)
        val croppedHeight = (cropBottom - cropTop + 1).coerceAtLeast(1)

        val cropped = Bitmap.createBitmap(bmp, 0, cropTop, IMAGE_WIDTH, croppedHeight)
        if (cropped !== bmp) bmp.recycle()
        return cropped
    }

    fun textToBitmapData(text: String, textSizePx: Float = 190f, padding: Int = 4): PrintBitmapData {
        val bmp = textToBitmap(text, textSizePx, padding)
        val height = bmp.height

        val pixels = IntArray(IMAGE_WIDTH * height)
        bmp.getPixels(pixels, 0, IMAGE_WIDTH, 0, 0, IMAGE_WIDTH, height)
        bmp.recycle()

        // Convert to 1-bit packed MSB-first
        val totalBytes = (IMAGE_WIDTH * height) / 8
        val packed = ByteArray(totalBytes)
        for (i in 0 until totalBytes) {
            var byte = 0
            for (j in 0 until 8) {
                val pixelIdx = i * 8 + j
                val pixel = pixels[pixelIdx]
                // Luminance: dark pixel -> bit=1 (black), light pixel -> bit=0 (white)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                byte = byte shl 1
                if (luminance < 128) {
                    byte = byte or 1
                }
            }
            packed[i] = byte.toByte()
        }

        val printLength = (packed.size + PADDED_ROW_BYTES - 1) / PADDED_ROW_BYTES

        return PrintBitmapData(bitmap = packed, printLength = printLength)
    }

    fun generatePrintLines(data: PrintBitmapData): List<ByteArray> {
        val lines = mutableListOf<ByteArray>()
        for (i in 0 until data.printLength) {
            val line = ByteArray(PACKET_SIZE)
            line[0] = 0x55
            line[1] = (i shr 8).toByte()
            line[2] = (i and 0xFF).toByte()

            // Copy PADDED_ROW_BYTES (96) of image data into positions 3..98
            // For a 384px-wide image, this packs 2 pixel rows per print line
            val srcOffset = i * PADDED_ROW_BYTES
            val srcEnd = minOf(srcOffset + PADDED_ROW_BYTES, data.bitmap.size)
            if (srcOffset < data.bitmap.size) {
                data.bitmap.copyInto(line, destinationOffset = 3, startIndex = srcOffset, endIndex = srcEnd)
            }

            lines.add(line)
        }
        return lines
    }

    fun computeFullWidthFontSize(text: String): Float {
        val refSize = 100f
        val paint = Paint().apply { textSize = refSize; typeface = Typeface.MONOSPACE }
        val lines = text.split("\n")
        val maxWidth = lines.maxOfOrNull { paint.measureText(it) } ?: return refSize
        if (maxWidth <= 0f) return refSize
        return refSize * IMAGE_WIDTH / maxWidth
    }

    fun generateLastLine(printLength: Int): ByteArray {
        val line = ByteArray(PACKET_SIZE)
        line[0] = 0x55
        line[1] = (printLength shr 8).toByte()
        line[2] = (printLength and 0xFF).toByte()
        return line
    }
}
