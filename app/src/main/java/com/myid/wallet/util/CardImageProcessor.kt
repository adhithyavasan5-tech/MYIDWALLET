package com.myid.wallet.util

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.min

data class CropCorners(
    val topLeft: android.graphics.PointF = android.graphics.PointF(.08f, .18f),
    val topRight: android.graphics.PointF = android.graphics.PointF(.92f, .18f),
    val bottomRight: android.graphics.PointF = android.graphics.PointF(.92f, .82f),
    val bottomLeft: android.graphics.PointF = android.graphics.PointF(.08f, .82f)
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
    private const val TAG = "CardImageProcessor"
    private const val MAX_SOURCE_BYTES = 100L * 1024 * 1024

    fun decode(context: Context, uri: Uri, sourceType: String = "image"): Bitmap? {
        return decodeUri(context, uri, maxSide = 1600, sourceType = sourceType)
    }

    fun decodeFile(file: File): Bitmap? {
        return decodeFile(file, maxSide = 2400)
    }

    private fun decodeFile(file: File, maxSide: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(file)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val size = info.size
                val scale = min(1f, maxSide.toFloat() / maxOf(size.width, size.height))
                if (scale < 1f) {
                    decoder.setTargetSize(
                        (size.width * scale).toInt().coerceAtLeast(1),
                        (size.height * scale).toInt().coerceAtLeast(1)
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        val exif = try {
            ExifInterface(file.absolutePath)
        } catch (e: IOException) {
            Log.w(TAG, "EXIF metadata unavailable: ${e.javaClass.simpleName}: using default orientation.")
            null
        }
        return exif?.let { applyExifOrientation(bitmap, it) } ?: bitmap
    }

    fun decodeFull(context: Context, uri: Uri, sourceType: String = "image"): Bitmap? =
        decodeUri(context, uri, maxSide = 2400, sourceType = sourceType)

    private fun decodeUri(context: Context, uri: Uri, maxSide: Int, sourceType: String): Bitmap? {
        val resolver = context.contentResolver
        val scheme = uri.scheme ?: "unknown"
        val mimeType = try {
            resolver.getType(uri) ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
        val cachedSource = File.createTempFile("myid_image_", ".tmp", context.cacheDir)
        try {
            resolver.openInputStream(uri)?.use { input ->
                cachedSource.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var totalBytes = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        totalBytes += count
                        if (totalBytes > MAX_SOURCE_BYTES) {
                            throw IOException("The selected image is too large to open safely.")
                        }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IOException("The image provider could not open this image.")

            val bitmap = decodeFile(cachedSource, maxSide)
                ?: throw IOException("The selected file is not a supported or readable image.")
            Log.i(TAG, "Image decode succeeded: source=$sourceType scheme=$scheme mime=$mimeType")
            return bitmap
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Image decode failed: source=$sourceType scheme=$scheme mime=$mimeType " +
                    "exception=${e.javaClass.simpleName}: image stream could not be read or decoded."
            )
            throw e
        } finally {
            cachedSource.delete()
        }
    }

    private fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        while (width / sample > maxSide || height / sample > maxSide) sample *= 2
        return sample
    }

    private fun applyExifOrientation(bitmap: Bitmap, exif: ExifInterface): Bitmap {
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        val matrix = Matrix()
        val values = when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
                floatArrayOf(-1f, 0f, bitmap.width.toFloat(), 0f, 1f, 0f, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 ->
                floatArrayOf(-1f, 0f, bitmap.width.toFloat(), 0f, -1f, bitmap.height.toFloat(), 0f, 0f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL ->
                floatArrayOf(1f, 0f, 0f, 0f, -1f, bitmap.height.toFloat(), 0f, 0f, 1f)
            ExifInterface.ORIENTATION_TRANSPOSE ->
                floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_ROTATE_90 ->
                floatArrayOf(0f, -1f, bitmap.height.toFloat(), 1f, 0f, 0f, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_TRANSVERSE ->
                floatArrayOf(0f, -1f, bitmap.height.toFloat(), -1f, 0f, bitmap.width.toFloat(), 0f, 0f, 1f)
            ExifInterface.ORIENTATION_ROTATE_270 ->
                floatArrayOf(0f, 1f, 0f, -1f, 0f, bitmap.width.toFloat(), 0f, 0f, 1f)
            else -> return bitmap
        }
        matrix.setValues(values)
        return transformed(bitmap, matrix)
    }

    private fun transformed(bitmap: Bitmap, matrix: Matrix): Bitmap {
        val result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (result !== bitmap) bitmap.recycle()
        return result
    }

    fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return transformed(bitmap, matrix)
    }

    fun rotateCorners(corners: CropCorners, degrees: Int): CropCorners {
        val turns = ((degrees / 90) % 4 + 4) % 4
        var result = corners
        repeat(turns) {
            fun clockwise(point: android.graphics.PointF) =
                android.graphics.PointF(1f - point.y, point.x)
            result = CropCorners(
                topLeft = clockwise(result.bottomLeft),
                topRight = clockwise(result.topLeft),
                bottomRight = clockwise(result.topRight),
                bottomLeft = clockwise(result.bottomRight)
            )
        }
        return result
    }

    fun imageToViewMatrix(
        imageWidth: Int,
        imageHeight: Int,
        viewWidth: Float,
        viewHeight: Float,
        zoom: Float,
        pan: android.graphics.PointF
    ): Matrix {
        val scale = min(viewWidth / imageWidth, viewHeight / imageHeight) * zoom
        val matrix = Matrix()
        matrix.setScale(scale, scale)
        matrix.postTranslate(
            (viewWidth - imageWidth * scale) / 2f + pan.x,
            (viewHeight - imageHeight * scale) / 2f + pan.y
        )
        return matrix
    }

    fun viewToImagePoint(matrix: Matrix, x: Float, y: Float): android.graphics.PointF? {
        val inverse = Matrix()
        if (!matrix.invert(inverse)) return null
        val point = floatArrayOf(x, y)
        inverse.mapPoints(point)
        return android.graphics.PointF(point[0], point[1])
    }

    fun moveCorner(
        corners: CropCorners,
        index: Int,
        x: Float,
        y: Float,
        imageWidth: Int,
        imageHeight: Int
    ): CropCorners? {
        val point = android.graphics.PointF(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        val moved = when (index) {
            0 -> corners.copy(topLeft = point)
            1 -> corners.copy(topRight = point)
            2 -> corners.copy(bottomRight = point)
            3 -> corners.copy(bottomLeft = point)
            else -> return null
        }
        return moved.takeIf { isValid(it, imageWidth, imageHeight) }
    }

    fun isValid(corners: CropCorners, imageWidth: Int, imageHeight: Int): Boolean {
        val points = listOf(corners.topLeft, corners.topRight, corners.bottomRight, corners.bottomLeft)
        if (points.any { it.x !in 0f..1f || it.y !in 0f..1f }) return false
        val pixelPoints = points.map { android.graphics.PointF(it.x * imageWidth, it.y * imageHeight) }
        val crossProducts = pixelPoints.indices.map { index ->
            val a = pixelPoints[index]
            val b = pixelPoints[(index + 1) % 4]
            val c = pixelPoints[(index + 2) % 4]
            (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        }
        if (crossProducts.any { it == 0f } ||
            !(crossProducts.all { it > 0f } || crossProducts.all { it < 0f })
        ) return false
        val area = abs(pixelPoints.indices.sumOf { index ->
            val a = pixelPoints[index]
            val b = pixelPoints[(index + 1) % 4]
            (a.x * b.y - b.x * a.y).toDouble()
        }) / 2.0
        if (area < imageWidth.toDouble() * imageHeight * 0.04) return false
        return pixelPoints.indices.all { index ->
            val a = pixelPoints[index]
            val b = pixelPoints[(index + 1) % 4]
            kotlin.math.hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) >= 24.0
        }
    }

    fun warp(bitmap: Bitmap, corners: CropCorners): Bitmap {
        require(isValid(corners, bitmap.width, bitmap.height)) { "Crop corners must form a valid quadrilateral." }
        val tl = android.graphics.PointF(corners.topLeft.x * bitmap.width, corners.topLeft.y * bitmap.height)
        val tr = android.graphics.PointF(corners.topRight.x * bitmap.width, corners.topRight.y * bitmap.height)
        val br = android.graphics.PointF(corners.bottomRight.x * bitmap.width, corners.bottomRight.y * bitmap.height)
        val bl = android.graphics.PointF(corners.bottomLeft.x * bitmap.width, corners.bottomLeft.y * bitmap.height)
        fun distance(a: android.graphics.PointF, b: android.graphics.PointF) =
            kotlin.math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
        var width = ((distance(tl, tr) + distance(bl, br)) / 2.0).toInt().coerceAtLeast(1)
        var height = ((distance(tl, bl) + distance(tr, br)) / 2.0).toInt().coerceAtLeast(1)
        val maxPixels = bitmap.width.toLong() * bitmap.height
        val outputPixels = width.toLong() * height
        if (outputPixels > maxPixels) {
            val scale = kotlin.math.sqrt(maxPixels.toDouble() / outputPixels)
            width = (width * scale).toInt().coerceAtLeast(1)
            height = (height * scale).toInt().coerceAtLeast(1)
        }
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val source = floatArrayOf(tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y)
        val destination = floatArrayOf(
            0f, 0f, width.toFloat(), 0f,
            width.toFloat(), height.toFloat(), 0f, height.toFloat()
        )
        val transform = Matrix()
        check(transform.setPolyToPoly(source, 0, destination, 0, 4)) {
            "Could not compute the perspective crop."
        }
        Canvas(output).drawBitmap(
            bitmap,
            transform,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        )
        return output
    }
}
