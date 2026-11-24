package com.myid.wallet.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class CropCorners(
    var topLeft: android.graphics.PointF = android.graphics.PointF(.08f, .18f),
    var topRight: android.graphics.PointF = android.graphics.PointF(.92f, .18f),
    var bottomRight: android.graphics.PointF = android.graphics.PointF(.92f, .82f),
    var bottomLeft: android.graphics.PointF = android.graphics.PointF(.08f, .82f)
) {
    fun confidence(bitmap: Bitmap): Float {
        val span = maxOf(
            abs(topLeft.x - topRight.x),
            abs(bottomLeft.x - bottomRight.x),
            abs(topLeft.y - bottomLeft.y),
            abs(topRight.y - bottomRight.y)
        )
        return (span.coerceIn(0f, 1f))
    }
}

object CardImageProcessor {
    fun decode(context: Context, uri: Uri): Bitmap? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val sampled = decodeSampled(bytes)
        val orientation = context.contentResolver.openInputStream(uri)?.use {
            exifRotationDegrees(ExifInterface(it))
        } ?: 0
        return rotate(sampled, orientation)
    }

    fun decodeFile(file: File): Bitmap? {
        val bytes = file.readBytes()
        val sampled = decodeSampled(bytes)
        val orientation = exifRotationDegrees(ExifInterface(file.absolutePath))
        return rotate(sampled, orientation)
    }

    private fun exifRotationDegrees(exif: ExifInterface): Int =
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

    private fun decodeSampled(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val maxSide = 1600
        var scale = 1
        while (bounds.outWidth / scale > maxSide || bounds.outHeight / scale > maxSide) scale *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = scale }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: error("Could not decode selected image.")
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (result !== bitmap) bitmap.recycle()
        return result
    }

    fun detectCorners(bitmap: Bitmap): CropCorners {
        val edgeBitmap = toGrayscale(bitmap)
        val w = edgeBitmap.width
        val h = edgeBitmap.height
        val pixels = IntArray(w * h)
        edgeBitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val rowScores = FloatArray(h)
        val colScores = FloatArray(w)
        val threshold = 34
        for (y in 2 until h - 2) {
            var score = 0f
            for (x in 2 until w - 2) {
                val c = android.graphics.Color.red(pixels[y * w + x])
                val right = android.graphics.Color.red(pixels[y * w + x + 2])
                val down = android.graphics.Color.red(pixels[(y + 2) * w + x])
                val gx = abs(c - right)
                val gy = abs(c - down)
                val edge = gx + gy
                if (edge > threshold) {
                    score += edge
                    colScores[x] += edge
                }
            }
            rowScores[y] = score
        }
        edgeBitmap.recycle()

        val marginX = (w * .05f).toInt()
        val marginY = (h * .05f).toInt()
        fun peak(values: FloatArray, from: Int, to: Int): Int {
            var best = from
            for (i in from until to) if (values[i] > values[best]) best = i
            return best
        }
        val top = peak(rowScores, marginY, h / 2)
        val bottom = peak(rowScores, h / 2, h - marginY)
        val left = peak(colScores, marginX, w / 2)
        val right = peak(colScores, w / 2, w - marginX)
        val x1 = (left.toFloat() / w).coerceIn(.03f, .35f)
        val x2 = (right.toFloat() / w).coerceIn(.65f, .97f)
        val y1 = (top.toFloat() / h).coerceIn(.03f, .35f)
        val y2 = (bottom.toFloat() / h).coerceIn(.65f, .97f)
        val area = (x2 - x1) * (y2 - y1)
        if (area < .18f) return CropCorners()
        return CropCorners(
            android.graphics.PointF(x1, y1),
            android.graphics.PointF(x2, y1),
            android.graphics.PointF(x2, y2),
            android.graphics.PointF(x1, y2)
        )
    }

    private fun toGrayscale(source: Bitmap): Bitmap {
        val small = if (source.width > 600 || source.height > 600) {
            val scale = 600f / max(source.width, source.height)
            Bitmap.createScaledBitmap(source, (source.width * scale).toInt(), (source.height * scale).toInt(), true)
        } else source
        val gray = Bitmap.createBitmap(small.width, small.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(gray)
        val paint = Paint()
        val matrix = ColorMatrix().apply { setSaturation(0f) }
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(small, 0f, 0f, paint)
        if (small !== source) small.recycle()
        return gray
    }

    fun warp(bitmap: Bitmap, corners: CropCorners): Bitmap {
        val tl = android.graphics.PointF(corners.topLeft.x * bitmap.width, corners.topLeft.y * bitmap.height)
        val tr = android.graphics.PointF(corners.topRight.x * bitmap.width, corners.topRight.y * bitmap.height)
        val br = android.graphics.PointF(corners.bottomRight.x * bitmap.width, corners.bottomRight.y * bitmap.height)
        val bl = android.graphics.PointF(corners.bottomLeft.x * bitmap.width, corners.bottomLeft.y * bitmap.height)
        val width = max(
            ((tr.x - tl.x) * (tr.x - tl.x) + (tr.y - tl.y) * (tr.y - tl.y)).toDouble(),
            ((br.x - bl.x) * (br.x - bl.x) + (br.y - bl.y) * (br.y - bl.y)).toDouble()
        ).let { kotlin.math.sqrt(it).toInt().coerceIn(320, 1600) }
        val height = max(
            ((bl.x - tl.x) * (bl.x - tl.x) + (bl.y - tl.y) * (bl.y - tl.y)).toDouble(),
            ((br.x - tr.x) * (br.x - tr.x) + (br.y - tr.y) * (br.y - tr.y)).toDouble()
        ).let { kotlin.math.sqrt(it).toInt().coerceIn(200, 1000) }
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val h = solveHomography(
            listOf(
                android.graphics.PointF(0f, 0f),
                android.graphics.PointF(width.toFloat(), 0f),
                android.graphics.PointF(width.toFloat(), height.toFloat()),
                android.graphics.PointF(0f, height.toFloat())
            ),
            listOf(tl, tr, br, bl)
        )
        val src = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(src, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val dst = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val denominator = h[6] * x + h[7] * y + h[8]
                val sx = ((h[0] * x + h[1] * y + h[2]) / denominator).toInt()
                val sy = ((h[3] * x + h[4] * y + h[5]) / denominator).toInt()
                dst[y * width + x] = if (sx in 0 until bitmap.width && sy in 0 until bitmap.height)
                    src[sy * bitmap.width + sx] else 0
            }
        }
        output.setPixels(dst, 0, width, 0, 0, width, height)
        return output
    }

    private fun solveHomography(src: List<android.graphics.PointF>, dst: List<android.graphics.PointF>): FloatArray {
        val a = Array(8) { FloatArray(9) }
        for (i in 0 until 4) {
            val (x, y) = src[i].x to src[i].y
            val (u, v) = dst[i].x to dst[i].y
            a[i * 2][0] = x; a[i * 2][1] = y; a[i * 2][2] = 1f
            a[i * 2][3] = 0f; a[i * 2][4] = 0f; a[i * 2][5] = 0f
            a[i * 2][6] = -u * x; a[i * 2][7] = -u * y; a[i * 2][8] = u
            a[i * 2 + 1][0] = 0f; a[i * 2 + 1][1] = 0f; a[i * 2 + 1][2] = 0f
            a[i * 2 + 1][3] = x; a[i * 2 + 1][4] = y; a[i * 2 + 1][5] = 1f
            a[i * 2 + 1][6] = -v * x; a[i * 2 + 1][7] = -v * y; a[i * 2 + 1][8] = v
        }
        val solution = gauss(a)
        return floatArrayOf(
            solution[0], solution[1], solution[2],
            solution[3], solution[4], solution[5],
            solution[6], solution[7], 1f
        )
    }

    private fun gauss(a: Array<FloatArray>): FloatArray {
        val n = 8
        val m = a.map { it.copyOf(9) }.toTypedArray()
        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) if (abs(m[row][col]) > abs(m[pivot][col])) pivot = row
            val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
            val d = m[col][col]
            if (abs(d) < 1e-6f) continue
            for (j in col until 9) m[col][j] /= d
            for (row in 0 until n) if (row != col) {
                val factor = m[row][col]
                for (j in col until 9) m[row][j] -= factor * m[col][j]
            }
        }
        return FloatArray(n) { m[it][8] }
    }
}
