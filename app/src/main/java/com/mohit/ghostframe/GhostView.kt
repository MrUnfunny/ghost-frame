package com.mohit.ghostframe

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Full-screen view that draws the reference with a movable / scalable / rotatable transform. */
class GhostView(ctx: Context) : View(ctx) {
    var bitmap: Bitmap? = null; private set
    var edges: Bitmap? = null
        set(v) { field = v; invalidate() }
    var outline = false
        set(v) { field = v; invalidate() }
    var adjustable = false
        set(v) { field = v; invalidate() }
    var flip = false
        set(v) { field = v; invalidate() }
    var onChanged: (() -> Unit)? = null
    /** In-view translucency, used while the window itself must stay opaque to receive touches. */
    var drawAlpha = 1f
        set(v) { field = v; paint.alpha = (v * 255).toInt().coerceIn(0, 255); invalidate() }

    private var cx = 0f; private var cy = 0f; private var s = 1f; private var r = 0f
    private var pending: FloatArray? = null
    private var ready = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#FFD34D"); strokeWidth = 5f
        pathEffect = DashPathEffect(floatArrayOf(28f, 18f), 0f)
    }
    private val m = Matrix()

    fun setBitmap(b: Bitmap?, saved: FloatArray?) {
        bitmap = b; edges = null; pending = saved; ready = false
        if (width > 0) applyPending()
        invalidate()
    }

    private fun fitScale(b: Bitmap) = min(width / b.width.toFloat(), height / b.height.toFloat())

    private fun applyPending() {
        val b = bitmap ?: return
        val t = pending
        if (t != null) {
            cx = t[0] * width; cy = t[1] * height; s = t[2] * fitScale(b); r = t[3]; flip = t[4] > 0.5f
        } else {
            cx = width / 2f; cy = height / 2f; s = fitScale(b); r = 0f
        }
        pending = null; ready = true
    }

    fun fit() {
        val b = bitmap ?: return
        cx = width / 2f; cy = height / 2f; s = fitScale(b); r = 0f
        invalidate(); onChanged?.invoke()
    }

    fun state(): FloatArray? {
        val b = bitmap ?: return null
        if (!ready || width == 0) return null
        return floatArrayOf(cx / width, cy / height, s / fitScale(b), r, if (flip) 1f else 0f)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (ready && ow > 0 && oh > 0) { cx *= w / ow.toFloat(); cy *= h / oh.toFloat() }
        else if (!ready) applyPending()
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        if (!ready) return
        val e = edges
        val src = if (outline && e != null) e else b
        val deg = Math.toDegrees(r.toDouble()).toFloat()
        m.setScale(b.width / src.width.toFloat(), b.height / src.height.toFloat())
        m.postTranslate(-b.width / 2f, -b.height / 2f)
        m.postScale(if (flip) -s else s, s)
        m.postRotate(deg)
        m.postTranslate(cx, cy)
        canvas.drawBitmap(src, m, paint)
        if (adjustable) {
            canvas.save()
            canvas.translate(cx, cy); canvas.rotate(deg)
            val hw = b.width * s / 2; val hh = b.height * s / 2
            canvas.drawRect(-hw, -hh, hw, hh, frame)
            canvas.restore()
        }
    }

    // ---- gestures (only while adjustable) ----
    private var n = 0
    private var x0 = 0f; private var y0 = 0f; private var d0 = 1f; private var a0 = 0f
    private var tcx = 0f; private var tcy = 0f; private var ts = 1f; private var tr = 0f
    private var p0 = 0; private var p1 = 1   // pointer indices in use

    private fun snap(e: MotionEvent, skip: Int = -1) {
        val ids = (0 until e.pointerCount).filter { it != skip }.take(2)
        n = ids.size
        tcx = cx; tcy = cy; ts = s; tr = r
        if (n >= 1) p0 = e.getPointerId(ids[0])
        if (n == 1) { x0 = e.getX(ids[0]); y0 = e.getY(ids[0]) }
        else if (n == 2) {
            p1 = e.getPointerId(ids[1])
            val (i, j) = ids
            x0 = (e.getX(i) + e.getX(j)) / 2; y0 = (e.getY(i) + e.getY(j)) / 2
            d0 = hypot(e.getX(j) - e.getX(i), e.getY(j) - e.getY(i)).coerceAtLeast(1f)
            a0 = atan2(e.getY(j) - e.getY(i), e.getX(j) - e.getX(i))
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!adjustable || bitmap == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> snap(e)
            MotionEvent.ACTION_POINTER_UP -> snap(e, e.actionIndex)
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(p0)
                if (n == 1 && i >= 0) {
                    cx = tcx + e.getX(i) - x0; cy = tcy + e.getY(i) - y0
                } else if (n == 2) {
                    val j = e.findPointerIndex(p1)
                    if (i >= 0 && j >= 0) {
                        val mx = (e.getX(i) + e.getX(j)) / 2; val my = (e.getY(i) + e.getY(j)) / 2
                        val d = hypot(e.getX(j) - e.getX(i), e.getY(j) - e.getY(i)).coerceAtLeast(1f)
                        val a = atan2(e.getY(j) - e.getY(i), e.getX(j) - e.getX(i))
                        val k = d / d0; val da = a - a0
                        val ox = tcx - x0; val oy = tcy - y0; val c = cos(da); val sn = sin(da)
                        s = (ts * k).coerceAtLeast(0.01f); r = tr + da
                        cx = mx + (ox * c - oy * sn) * k; cy = my + (ox * sn + oy * c) * k
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { n = 0; onChanged?.invoke() }
        }
        return true
    }
}
