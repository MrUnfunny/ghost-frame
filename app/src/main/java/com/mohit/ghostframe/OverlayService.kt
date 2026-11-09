package com.mohit.ghostframe

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

class OverlayService : Service() {
    companion object {
        const val ACTION_START = "com.mohit.ghostframe.START"
        const val ACTION_STOP = "com.mohit.ghostframe.STOP"
        const val ACTION_RELOAD = "com.mohit.ghostframe.RELOAD"
        @Volatile var running = false

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, OverlayService::class.java).setAction(action)
            when {
                action == ACTION_START -> ctx.startForegroundService(i)
                running -> ctx.startService(i)
            }
        }
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private var ghost: GhostView? = null
    private lateinit var ghostLp: WindowManager.LayoutParams
    private lateinit var control: LinearLayout
    private lateinit var controlLp: WindowManager.LayoutParams
    private lateinit var bubble: TextView
    private lateinit var panel: LinearLayout
    private lateinit var moveBtn: TextView
    private lateinit var modeBtn: TextView
    private lateinit var hideBtn: TextView
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var edgeMap: EdgeMap? = null
    private var refVersion = -1L
    private var adjusting = false
    private var edgeJob = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startFg()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow \"Display over other apps\" for Ghost Frame first", Toast.LENGTH_LONG).show()
            stopSelf(); return START_NOT_STICKY
        }
        if (ghost == null) createWindows()
        applyPrefs()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        saveTransform()
        ghost?.let { runCatching { wm.removeView(it) } }
        if (::control.isInitialized) runCatching { wm.removeView(control) }
        ghost = null
        exec.shutdownNow()
        super.onDestroy()
    }

    // ---------------- notification ----------------
    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("overlay", "Overlay running", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "overlay")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Ghost Frame is showing")
            .setContentText("Tap the floating bubble for controls")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        running = true
    }

    // ---------------- windows ----------------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun createWindows() {
        wm = getSystemService(WindowManager::class.java)
        prefs = Prefs(this)

        val g = GhostView(this)
        g.onChanged = { saveTransform() }
        ghostLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
            title = "GhostFrameOverlay"
        }
        wm.addView(g, ghostLp)
        ghost = g

        buildControls()
        wm.addView(control, controlLp)
    }

    private fun circle(fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(fill); setStroke(dp(1), 0x55FFFFFF)
    }

    private fun btn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        background = circle(0xD915171A.toInt())
        layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
        setOnClickListener { onClick() }
    }

    private val wrap get() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; layoutParams = wrap; views.forEach { addView(it) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildControls() {
        bubble = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            background = circle(0xE615171A.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
            elevation = dp(4).toFloat()
        }
        moveBtn = btn("Move") { toggleAdjust() }
        hideBtn = btn("Hide") {
            val g = ghost ?: return@btn
            g.visibility = if (g.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
            refreshLabels()
        }
        modeBtn = btn("Edge") { prefs.outline = !prefs.outline; applyPrefs() }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = wrap
            visibility = View.GONE
            addView(row(moveBtn, hideBtn))
            addView(row(btn("−") { stepOpacity(-0.1f) }, btn("+") { stepOpacity(0.1f) }))
            addView(row(modeBtn, btn("Flip") { ghost?.let { it.flip = !it.flip; saveTransform() } }))
            addView(row(btn("Fit") { ghost?.fit() }, btn("New") { openApp(pick = true) }))
            addView(row(btn("Cam") { openCamera() }, btn("✕") { stopSelf() }.apply { background = circle(0xD9B3261E.toInt()) }))
        }
        control = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bubble); addView(panel)
        }
        controlLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.bubbleX; y = prefs.bubbleY
            title = "GhostFrameControls"
        }

        var downX = 0f; var downY = 0f; var lpX = 0; var lpY = 0; var moved = false
        val slop = dp(8)
        bubble.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; lpX = controlLp.x; lpY = controlLp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) { controlLp.x = lpX + dx.toInt(); controlLp.y = lpY + dy.toInt(); wm.updateViewLayout(control, controlLp) }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) { prefs.bubbleX = controlLp.x; prefs.bubbleY = controlLp.y }
                    else togglePanel()
                }
            }
            true
        }
    }

    private fun togglePanel() {
        val open = panel.visibility != View.VISIBLE
        panel.visibility = if (open) View.VISIBLE else View.GONE
        if (open) {
            // keep the panel on screen
            val screenH = resources.displayMetrics.heightPixels
            val need = dp(62) + dp(58) * 5
            if (controlLp.y + need > screenH) { controlLp.y = (screenH - need).coerceAtLeast(0) }
        }
        wm.updateViewLayout(control, controlLp)
    }

    private fun toggleAdjust() {
        adjusting = !adjusting
        ghost?.adjustable = adjusting
        ghostLp.flags = if (adjusting) ghostLp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else ghostLp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (adjusting) ghost?.visibility = View.VISIBLE
        applyAlpha()
        if (adjusting) Toast.makeText(this, "Drag to move · pinch to resize & rotate · tap Done", Toast.LENGTH_SHORT).show()
        refreshLabels()
    }

    private fun stepOpacity(d: Float) {
        prefs.opacity = (prefs.opacity + d).coerceIn(0.1f, maxPassThroughAlpha(this))
        applyPrefs()
    }

    private fun refreshLabels() {
        val pct = (minOf(prefs.opacity, maxPassThroughAlpha(this)) * 100).roundToInt()
        bubble.text = "$pct%"
        bubble.background = circle(if (adjusting) 0xF2FFD34D.toInt() else 0xE615171A.toInt())
        bubble.setTextColor(if (adjusting) 0xFF0B0D0C.toInt() else Color.WHITE)
        moveBtn.text = if (adjusting) "Done" else "Move"
        moveBtn.background = circle(if (adjusting) 0xF2FFD34D.toInt() else 0xD915171A.toInt())
        moveBtn.setTextColor(if (adjusting) 0xFF0B0D0C.toInt() else Color.WHITE)
        modeBtn.text = if (prefs.outline) "Photo" else "Edge"
        hideBtn.text = if (ghost?.visibility == View.VISIBLE) "Hide" else "Show"
    }

    private fun openApp(pick: Boolean) {
        if (adjusting) toggleAdjust()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("pick", pick))
    }

    private fun openCamera() {
        if (adjusting) toggleAdjust()
        if (!launchCamera(this)) Toast.makeText(this, "No camera app found", Toast.LENGTH_SHORT).show()
    }

    // ---------------- state ----------------
    private fun saveTransform() { ghost?.state()?.let { prefs.transform = it } }

    private fun applyPrefs() {
        val g = ghost ?: return
        val v = prefs.refVersion
        if (v != refVersion) {
            refVersion = v
            edgeMap = null
            g.setBitmap(RefStore.load(this), prefs.transform)
            if (g.bitmap == null) Toast.makeText(this, "Pick a reference photo in Ghost Frame", Toast.LENGTH_LONG).show()
        }
        g.outline = prefs.outline
        applyAlpha()
        if (prefs.outline) buildEdges()
        refreshLabels()
    }

    /**
     * Pass-through mode: the window is translucent (Android only lets taps through windows at most ~80% opaque).
     * Move mode: Android won't deliver touches to a mostly transparent window, so the window goes opaque
     * and the view draws the photo translucent instead.
     */
    private fun applyAlpha() {
        val g = ghost ?: return
        val a = minOf(prefs.opacity, maxPassThroughAlpha(this))
        if (adjusting) { ghostLp.alpha = 1f; g.drawAlpha = a }
        else { ghostLp.alpha = a; g.drawAlpha = 1f }
        wm.updateViewLayout(g, ghostLp)
    }

    private fun buildEdges() {
        val g = ghost ?: return
        val bmp = g.bitmap ?: return
        val detail = prefs.detail; val color = OUTLINE_COLORS[prefs.color.coerceIn(0, OUTLINE_COLORS.size - 1)]
        val job = ++edgeJob
        exec.execute {
            val em = edgeMap?.takeIf { it.src === bmp } ?: EdgeMap(bmp).also { edgeMap = it }
            val e = em.render(detail, color)
            main.post { if (job == edgeJob && ghost?.bitmap === bmp) ghost?.edges = e }
        }
    }
}
