package com.mohit.ghostframe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

object RefStore {
    private const val MAX = 2048
    private fun file(ctx: Context) = File(ctx.filesDir, "reference.png")

    fun load(ctx: Context): Bitmap? =
        file(ctx).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** Decodes (respecting EXIF rotation), downsizes, stores, and bumps the version. */
    fun saveFromUri(ctx: Context, uri: Uri) {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            val k = minOf(1f, MAX.toFloat() / max(info.size.width, info.size.height))
            dec.setTargetSize(
                (info.size.width * k).roundToInt().coerceAtLeast(1),
                (info.size.height * k).roundToInt().coerceAtLeast(1)
            )
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        save(ctx, bmp)
    }

    fun save(ctx: Context, bmp: Bitmap) {
        file(ctx).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Prefs(ctx).apply { transform = null; refVersion = System.currentTimeMillis() }
    }

    fun samplePose(): Bitmap {
        val b = Bitmap.createBitmap(900, 1600, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        fun line(w: Float, vararg pts: Float) {
            p.style = Paint.Style.STROKE; p.strokeWidth = w
            val path = Path().apply {
                moveTo(pts[0], pts[1]); var i = 2
                while (i < pts.size) { lineTo(pts[i], pts[i + 1]); i += 2 }
            }
            c.drawPath(path, p)
        }
        p.style = Paint.Style.FILL; c.drawCircle(470f, 250f, 92f, p)
        line(70f, 462f, 330f, 458f, 400f)
        p.style = Paint.Style.FILL
        c.drawPath(Path().apply { moveTo(320f, 420f); lineTo(610f, 405f); lineTo(565f, 870f); lineTo(385f, 860f); close() }, p)
        line(60f, 320f, 420f, 610f, 405f, 565f, 870f, 385f, 860f, 320f, 420f)
        line(64f, 330f, 430f, 215f, 650f, 380f, 810f)      // hand on hip
        line(60f, 600f, 420f, 735f, 260f, 560f, 165f)      // hand behind head
        line(84f, 400f, 860f, 360f, 1185f, 385f, 1500f)    // standing leg
        line(80f, 550f, 865f, 650f, 1170f, 590f, 1490f)    // relaxed leg
        line(56f, 385f, 1500f, 300f, 1520f)
        line(56f, 590f, 1490f, 680f, 1510f)
        return b
    }
}
