package com.android.launcher37

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/**
 * 应用图标归一化管线。
 *
 * 输入：任意来源的 [Drawable]（普通 drawable / AdaptiveIconDrawable / vector / 等）。
 * 输出：归一化到 128×128 像素、20% 圆角、纯色卡片底（surfaceVariant→outline 渐变）。
 *
 * 透明边缘的图标自动加 backdrop（统计像素均值并提亮到 ≥150 亮度）。
 *
 * 缓存：所有结果走 [IconCache]（按 bitmap 字节数统计，16MB 封顶）。
 */
object IconNormalizer {

    private const val NORM_SIZE = 128
    private const val CORNER_RADIUS = NORM_SIZE * 0.20f
    private const val BACKDROP_MIN_BRIGHTNESS = 150
    private const val EMOJI_FILL = 0.70f
    private const val SPLIT_SLANT_TOP = NORM_SIZE * 0.625f
    private const val SPLIT_SLANT_BOTTOM = NORM_SIZE * 0.375f

    @JvmStatic
    fun normalizedIcon(c: Context, id: String): Drawable? {
        val key = "id:$id"
        IconCache.normalizedCache().get(key)?.let { return it }
        val n = normalizeDrawable(c, Store.icon(c, id))
        if (n != null) IconCache.normalizedCache().put(key, n)
        return n
    }

    @JvmStatic
    fun normalizedGlyphIcon(c: Context, resId: Int): Drawable? {
        val key = "glyph:$resId"
        IconCache.normalizedCache().get(key)?.let { return it }
        val d: Drawable = try {
            c.resources.getDrawable(resId, c.theme).mutate().apply {
                setTint(c.resources.getColor(R.color.foreground_secondary))
            }
        } catch (e: Exception) {
            return null
        }
        val out = Bitmap.createBitmap(NORM_SIZE, NORM_SIZE, Bitmap.Config.ARGB_8888)
        drawBgGradient(c, Canvas(out))
        val size = (NORM_SIZE * EMOJI_FILL).toInt()
        val off = (NORM_SIZE - size) / 2
        d.setBounds(off, off, off + size, off + size)
        d.draw(Canvas(out))
        val result = BitmapDrawable(c.resources, rounded(out))
        out.recycle()
        IconCache.normalizedCache().put(key, result)
        return result
    }

    @JvmStatic
    fun normalizedSplitIcon(c: Context, leftId: String, rightId: String): Drawable? {
        val key = "split:$leftId|$rightId"
        IconCache.normalizedCache().get(key)?.let { return it }
        val left = normalizedIcon(c, leftId)
        val right = normalizedIcon(c, rightId)
        if (left !is BitmapDrawable || right !is BitmapDrawable) {
            return left ?: right
        }
        val lb = left.bitmap
        val rb = right.bitmap
        val out = Bitmap.createBitmap(NORM_SIZE, NORM_SIZE, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        drawBgGradient(c, cv)
        val size = (NORM_SIZE * 0.80f).toInt()
        val top = (NORM_SIZE - size) / 2
        val leftClip = android.graphics.Path().apply {
            moveTo(0f, 0f); lineTo(SPLIT_SLANT_TOP, 0f)
            lineTo(SPLIT_SLANT_BOTTOM, NORM_SIZE.toFloat()); lineTo(0f, NORM_SIZE.toFloat()); close()
        }
        val rightClip = android.graphics.Path().apply {
            moveTo(SPLIT_SLANT_TOP, 0f); lineTo(NORM_SIZE.toFloat(), 0f)
            lineTo(NORM_SIZE.toFloat(), NORM_SIZE.toFloat()); lineTo(SPLIT_SLANT_BOTTOM, NORM_SIZE.toFloat()); close()
        }
        cv.save(); cv.clipPath(leftClip)
        cv.drawBitmap(lb, null, Rect(0, top, size, top + size), paint); cv.restore()
        cv.save(); cv.clipPath(rightClip)
        cv.drawBitmap(rb, null, Rect(NORM_SIZE - size, top, NORM_SIZE, top + size), paint); cv.restore()
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x99FFFFFF.toInt(); strokeWidth = 2f
        }
        cv.drawLine(SPLIT_SLANT_TOP, 0f, SPLIT_SLANT_BOTTOM, NORM_SIZE.toFloat(), line)
        val d = BitmapDrawable(c.resources, out)
        IconCache.normalizedCache().put(key, d)
        return d
    }

    @JvmStatic
    fun normalizedEmoji(c: Context, emoji: String): Drawable? {
        val key = "emoji:$emoji"
        IconCache.normalizedCache().get(key)?.let { return it }
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            textSize = 100f
        }
        val bounds = Rect()
        p.getTextBounds(emoji, 0, emoji.length, bounds)
        if (bounds.isEmpty) return null
        val scale = NORM_SIZE * EMOJI_FILL / maxOf(bounds.width(), bounds.height()).toFloat()
        p.textSize = 100f * scale
        p.getTextBounds(emoji, 0, emoji.length, bounds)
        val out = Bitmap.createBitmap(NORM_SIZE, NORM_SIZE, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        drawBgGradient(c, cv)
        cv.drawText(
            emoji,
            (NORM_SIZE - bounds.width()) / 2f - bounds.left,
            (NORM_SIZE - bounds.height()) / 2f - bounds.top,
            p
        )
        val d = BitmapDrawable(c.resources, out)
        IconCache.normalizedCache().put(key, d)
        return d
    }

    internal fun normalizeDrawable(c: Context, d: Drawable?): Drawable? {
        if (d == null) return null
        return try {
            if (d is AdaptiveIconDrawable) return normalizeAdaptive(c, d)
            val w = d.intrinsicWidth
            val h = d.intrinsicHeight
            if (w <= 0 || h <= 0) return d
            val bmp = renderFitCenter(d, w, h)
            val result = if (needsBackdrop(bmp)) withBackdrop(c, bmp) else rounded(bmp)
            bmp.recycle()
            BitmapDrawable(c.resources, result)
        } catch (e: Exception) {
            d
        }
    }

    private fun needsBackdrop(bmp: Bitmap): Boolean {
        val s = bmp.width
        val pos = intArrayOf(s / 4, s / 2, 3 * s / 4)
        for (p in pos) {
            if ((bmp.getPixel(p, 1) ushr 24) < 24 || (bmp.getPixel(p, s - 2) ushr 24) < 24
                || (bmp.getPixel(1, p) ushr 24) < 24 || (bmp.getPixel(s - 2, p) ushr 24) < 24
            ) return true
        }
        return false
    }

    private fun withBackdrop(c: Context, src: Bitmap): Bitmap {
        val px = IntArray(src.width * src.height)
        src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
        var r = 0; var g = 0; var b = 0; var n = 0
        var i = 0
        while (i < px.size) {
            if ((px[i] ushr 24) >= 24) {
                r += (px[i] shr 16) and 0xFF
                g += (px[i] shr 8) and 0xFF
                b += px[i] and 0xFF
                n++
            }
            i += 7
        }
        var avgR = if (n > 0) r / n else 230
        var avgG = if (n > 0) g / n else 232
        var avgB = if (n > 0) b / n else 237
        val max = maxOf(avgR, avgG, avgB)
        if (max < BACKDROP_MIN_BRIGHTNESS) {
            val k = BACKDROP_MIN_BRIGHTNESS / max.toFloat()
            avgR = minOf(255, (avgR * k).toInt())
            avgG = minOf(255, (avgG * k).toInt())
            avgB = minOf(255, (avgB * k).toInt())
        }
        val topR = minOf(255, (avgR * 1.15f + 8).toInt())
        val topG = minOf(255, (avgG * 1.15f + 8).toInt())
        val topB = minOf(255, (avgB * 1.15f + 8).toInt())
        val botR = (avgR * 0.82f).toInt()
        val botG = (avgG * 0.82f).toInt()
        val botB = (avgB * 0.82f).toInt()
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        val topColor = 0xFF000000.toInt() or (topR shl 16) or (topG shl 8) or topB
        val botColor = 0xFF000000.toInt() or (botR shl 16) or (botG shl 8) or botB
        bg.shader = LinearGradient(
            0f, 0f, 0f, src.height.toFloat(),
            topColor, botColor, Shader.TileMode.CLAMP
        )
        cv.drawRoundRect(RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()), CORNER_RADIUS, CORNER_RADIUS, bg)
        cv.drawBitmap(src, 0f, 0f, null)
        return out
    }

    private fun normalizeAdaptive(c: Context, d: AdaptiveIconDrawable): Drawable {
        val out = Bitmap.createBitmap(NORM_SIZE, NORM_SIZE, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        d.background?.let {
            it.setBounds(0, 0, NORM_SIZE, NORM_SIZE)
            it.draw(cv)
        }
        d.foreground?.let {
            val fw = it.intrinsicWidth
            val fh = it.intrinsicHeight
            val scaled = if (fw > 0 && fh > 0) renderFitCenter(it, fw, fh) else renderFullBounds(it)
            cv.drawBitmap(scaled, 0f, 0f, null)
            scaled.recycle()
        }
        val roundedOut = rounded(out)
        out.recycle()
        return BitmapDrawable(c.resources, roundedOut)
    }

    private fun renderFitCenter(d: Drawable, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(NORM_SIZE, NORM_SIZE, Bitmap.Config.ARGB_8888)
        val scale = minOf(NORM_SIZE / w.toFloat(), NORM_SIZE / h.toFloat())
        val dw = Math.round(w * scale)
        val dh = Math.round(h * scale)
        val ox = (NORM_SIZE - dw) / 2
        val oy = (NORM_SIZE - dh) / 2
        d.setBounds(ox, oy, ox + dw, oy + dh)
        d.draw(Canvas(bmp))
        return bmp
    }

    private fun renderFullBounds(d: Drawable): Bitmap {
        val bmp = Bitmap.createBitmap(NORM_SIZE, NORM_SIZE, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, NORM_SIZE, NORM_SIZE)
        d.draw(Canvas(bmp))
        return bmp
    }

    /**
     * 在画布上绘制 surfaceVariant→outline 渐变 + 圆角的图标背景卡（共享 NORM_SIZE × NORM_SIZE 尺寸）。
     */
    private fun drawBgGradient(c: Context, cv: Canvas) {
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, NORM_SIZE.toFloat(),
                c.resources.getColor(R.color.surface_variant),
                c.resources.getColor(R.color.divider),
                Shader.TileMode.CLAMP
            )
        }
        cv.drawRoundRect(
            RectF(0f, 0f, NORM_SIZE.toFloat(), NORM_SIZE.toFloat()),
            CORNER_RADIUS, CORNER_RADIUS, bg
        )
    }

    internal fun rounded(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.BitmapShader(
                src, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP
            )
        }
        Canvas(out).drawRoundRect(
            RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()),
            CORNER_RADIUS, CORNER_RADIUS, p
        )
        return out
    }
}
