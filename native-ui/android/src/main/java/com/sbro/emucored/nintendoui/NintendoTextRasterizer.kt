package com.sbro.emucored.nintendoui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import java.nio.ByteBuffer

object NintendoTextRasterizer {
    /**
     * Rasterizes arbitrary Unicode / RTL (Arabic, Persian) / CJK / Devanagari / Cyrillic text
     * cleanly to tightly-cropped RGBA bytes for OpenGL ES texture upload.
     */
    @JvmStatic
    fun rasterize(
        text: String,
        fontPx: Float,
        maxWidthPx: Int,
        bold: Boolean,
        alignMode: Int,
        outSize: IntArray
    ): ByteArray? {
        if (text.isEmpty() || maxWidthPx <= 0) {
            outSize[0] = 0
            outSize[1] = 0
            return null
        }

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.WHITE
            textSize = fontPx.coerceIn(10f, 128f)
            typeface = Typeface.create(
                Typeface.SANS_SERIF,
                if (bold) Typeface.BOLD else Typeface.NORMAL
            )
        }

        val isRtl = TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(text, 0, text.length)
        val layoutAlign = when (alignMode) {
            1 -> Layout.Alignment.ALIGN_CENTER
            2 -> if (isRtl) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_OPPOSITE
            else -> if (isRtl) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
        }

        val maxW = maxWidthPx.coerceIn(32, 2048)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, maxW)
            .setAlignment(layoutAlign)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setIncludePad(false)
            .build()

        var maxLineW = 1f
        for (i in 0 until layout.lineCount) {
            maxLineW = maxOf(maxLineW, layout.getLineWidth(i))
        }
        val bmpW = (maxLineW.toInt() + 6).coerceIn(2, maxW)
        val bmpH = (layout.height + 4).coerceIn(2, 512)

        // Re-center inside exact cropped width when aligned center/end
        val finalLayout = if (alignMode != 0 && bmpW < maxW) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, bmpW)
                .setAlignment(layoutAlign)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setIncludePad(false)
                .build()
        } else {
            layout
        }

        val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(2f, 2f)
        finalLayout.draw(canvas)

        val buf = ByteBuffer.allocate(bmpW * bmpH * 4)
        bitmap.copyPixelsToBuffer(buf)
        bitmap.recycle()

        outSize[0] = bmpW
        outSize[1] = bmpH
        return buf.array()
    }
}
