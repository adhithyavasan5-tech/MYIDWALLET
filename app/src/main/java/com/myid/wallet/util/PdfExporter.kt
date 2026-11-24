package com.myid.wallet.util

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

object PdfExporter {
    /**
     * Creates a PDF in app cache. Caller must share it via FileProvider or copy it
     * to a user-selected destination. This starter uses a temporary cache file.
     */
    fun createPdf(context: Context, images: List<ByteArray>, title: String): File {
        require(images.isNotEmpty()) { "No card sides selected." }
        val pdf = PdfDocument()
        try {
            images.forEachIndexed { index, bytes ->
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: error("Could not decode document image.")
                val pageInfo = PdfDocument.PageInfo.Builder(595, 842, index + 1).create()
                val page = pdf.startPage(pageInfo)
                val canvas = page.canvas
                val maxW = 535f
                val maxH = 760f
                val scale = minOf(maxW / bitmap.width, maxH / bitmap.height)
                val width = bitmap.width * scale
                val height = bitmap.height * scale
                val left = (595f - width) / 2f
                val top = (842f - height) / 2f
                val dest = android.graphics.RectF(left, top, left + width, top + height)
                canvas.drawBitmap(bitmap, null, dest, android.graphics.Paint( android.graphics.Paint.FILTER_BITMAP_FLAG))
                pdf.finishPage(page)
                bitmap.recycle()
            }
            val output = File(context.cacheDir, "${title.replace(Regex("[^A-Za-z0-9_-]"), "_")}_${System.currentTimeMillis()}.pdf")
            FileOutputStream(output).use { pdf.writeTo(it) }
            return output
        } finally {
            pdf.close()
        }
    }
}
