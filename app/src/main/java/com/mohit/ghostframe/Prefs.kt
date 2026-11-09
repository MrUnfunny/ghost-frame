package com.mohit.ghostframe

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.hardware.input.InputManager
import android.os.Build
import android.provider.MediaStore

val OUTLINE_COLORS = intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFD34D.toInt(), 0xFF5CF2FF.toInt(), 0xFFFF5CA8.toInt())

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("ghostframe", Context.MODE_PRIVATE)

    var opacity: Float
        get() = sp.getFloat("opacity", 0.45f)
        set(v) = sp.edit().putFloat("opacity", v).apply()
    var outline: Boolean
        get() = sp.getBoolean("outline", false)
        set(v) = sp.edit().putBoolean("outline", v).apply()
    var detail: Int
        get() = sp.getInt("detail", 40)
        set(v) = sp.edit().putInt("detail", v).apply()
    var color: Int
        get() = sp.getInt("color", 1)
        set(v) = sp.edit().putInt("color", v).apply()
    var refVersion: Long
        get() = sp.getLong("refVersion", 0L)
        set(v) = sp.edit().putLong("refVersion", v).apply()
    var bubbleX: Int
        get() = sp.getInt("bubbleX", 24)
        set(v) = sp.edit().putInt("bubbleX", v).apply()
    var bubbleY: Int
        get() = sp.getInt("bubbleY", 400)
        set(v) = sp.edit().putInt("bubbleY", v).apply()

    /** cx/width, cy/height, scale relative to fit, rotation (rad), flip (0/1) */
    var transform: FloatArray?
        get() = sp.getString("transform", null)?.split(",")?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 5 }?.toFloatArray()
        set(v) = sp.edit().putString("transform", v?.joinToString(",")).apply()
}

/** Opens the phone's built-in camera directly (skips the chooser when apps like Snapchat also claim the camera intent). */
fun launchCamera(ctx: Context): Boolean {
    val base = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val system = ctx.packageManager.queryIntentActivities(base, 0)
        .firstOrNull { (it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 }
    val intent = if (system != null) Intent(base).setPackage(system.activityInfo.packageName) else base
    return runCatching { ctx.startActivity(intent) }.isSuccess
}

/** Android 12+ only lets taps pass through another app's window when it is at most this opaque. */
fun maxPassThroughAlpha(ctx: Context): Float =
    if (Build.VERSION.SDK_INT >= 31)
        ctx.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch
    else 1f
