package com.mohit.ghostframe

import android.graphics.Bitmap
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Sobel edge magnitudes of a reference image, rendered as coloured outlines with a dark halo. */
class EdgeMap(val src: Bitmap) {
    val w: Int
    val h: Int
    private val mag: FloatArray
    private var mx = 1f
    private val hist = IntArray(1024)

    init {
        val k = min(1f, 900f / max(src.width, src.height))
        w = max(2, (src.width * k).roundToInt()); h = max(2, (src.height * k).roundToInt())
        val sc = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h); sc.getPixels(px, 0, w, 0, 0, w, h)
        if (sc !== src) sc.recycle()
        val gray = FloatArray(w * h)
        for (i in px.indices) {
            val c = px[i]; val a = (c ushr 24) / 255f
            val l = ((c shr 16) and 255) * .299f + ((c shr 8) and 255) * .587f + (c and 255) * .114f
            gray[i] = l * a + 128f * (1 - a)   // transparent areas become flat grey
        }
        val tmp = FloatArray(w * h); val bl = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            tmp[i] = (gray[if (x > 0) i - 1 else i] + 2 * gray[i] + gray[if (x < w - 1) i + 1 else i]) / 4
        }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            bl[i] = (tmp[if (y > 0) i - w else i] + 2 * tmp[i] + tmp[if (y < h - 1) i + w else i]) / 4
        }
        mag = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val gx = -bl[i - w - 1] - 2 * bl[i - 1] - bl[i + w - 1] + bl[i - w + 1] + 2 * bl[i + 1] + bl[i + w + 1]
            val gy = -bl[i - w - 1] - 2 * bl[i - w] - bl[i - w + 1] + bl[i + w - 1] + 2 * bl[i + w] + bl[i + w + 1]
            val m = hypot(gx, gy); mag[i] = m; if (m > mx) mx = m
        }
        for (m in mag) hist[min(1023, (m / mx * 1023).toInt())]++
    }

    fun render(detail: Int, color: Int): Bitmap {
        val keep = 0.03f + detail / 100f * 0.22f   // keep the strongest 3%..25% of pixels
        val target = mag.size * keep
        var acc = 0; var bin = 1023
        while (bin > 0) { acc += hist[bin]; if (acc >= target) break; bin-- }
        val cut = max(1f, bin / 1023f * mx); val lo = cut * 0.6f
        val la = FloatArray(mag.size) { val m = mag[it]; if (m <= lo) 0f else if (m >= cut) 1f else (m - lo) / (cut - lo) }
        val cr = (color shr 16) and 255; val cg = (color shr 8) and 255; val cb = color and 255
        val out = IntArray(mag.size)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            var hm = 0f
            for (dy in -1..1) {
                val yy = y + dy; if (yy < 0 || yy >= h) continue
                for (dx in -1..1) {
                    val xx = x + dx; if (xx < 0 || xx >= w) continue
                    val v = la[yy * w + xx]; if (v > hm) hm = v
                }
            }
            val a = la[i]; val ha = hm * 0.55f
            val oa = a + ha * (1 - a)
            if (oa <= 0.004f) continue
            val f = a / oa
            out[i] = ((oa * 255).toInt() shl 24) or ((cr * f).toInt() shl 16) or ((cg * f).toInt() shl 8) or (cb * f).toInt()
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}
