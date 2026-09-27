package com.warlock.rokidcamera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import java.lang.ref.WeakReference

object ButtonHub {
    const val TAG = "WarlockBtn"

    val ACTIONS = arrayOf(
        "com.rokid.glass3.action.button.CLICK",
        "com.rokid.glass3.action.button.DOUBLE_CLICK",
        "com.rokid.glass3.action.button.LONG_PRESS",
        "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS",
        "com.android.action.ACTION_SPRITE_BUTTON_DOWN",
        "com.android.action.ACTION_SPRITE_BUTTON_UP",
        "com.rokid.sprite.ACTION_CAMERA",
        "com.rokid.glass.action.button.CLICK",
        "com.rokid.os.sprite.action.BUTTON_CLICK",
        "com.rokid.os.sprite.action.BUTTON_LONG_PRESS",
        "com.rokid.os.sprite.action.BUTTON_DOUBLE_CLICK",
        "android.intent.action.CAMERA_BUTTON",
        "com.rokid.os.master.assist.server.cmd",
        "com.rokid.glass.homekey.longpress"
    )

    enum class Kind { CLICK, DOUBLE, LONG, DOWN, UP, OTHER }

    @Volatile var host: WeakReference<MainActivity>? = null
    private var lastKind = Kind.OTHER
    private var lastKindAt = 0L

    fun filter(): IntentFilter {
        val f = IntentFilter()
        for (a in ACTIONS) f.addAction(a)
        f.priority = 999
        return f
    }

    fun classify(action: String?, extras: Bundle?): Kind {
        val a = action ?: return Kind.OTHER
        if (a.contains("VERY_VERY_LONG", true)) return Kind.OTHER
        if (a.contains("assist.server.cmd")) {
            val scene = extras?.getString("scene") ?: ""
            val open = extras?.getString("open") ?: extras?.get("open")?.toString() ?: ""
            Log.i(TAG, "assist cmd scene=$scene open=$open extras=$extras")
            if (scene.contains("picture", true) || scene.contains("photo", true) || scene == "ar_picture")
                return Kind.CLICK
            if (scene.contains("record", true) || scene.contains("video", true) || scene == "mix_record")
                return Kind.LONG
            return Kind.OTHER
        }
        if (a.contains("DOUBLE", true)) return Kind.DOUBLE
        if (a.contains("LONG", true)) return Kind.LONG
        if (a.contains("BUTTON_DOWN", true) || a.endsWith("_DOWN")) return Kind.DOWN
        if (a.contains("BUTTON_UP", true) || a.endsWith("_UP")) return Kind.UP
        if (a.endsWith(".CLICK") || a.contains("BUTTON_CLICK", true) ||
            a.contains("CAMERA_BUTTON", true) || a.contains("ACTION_CAMERA", true) ||
            a.contains("CAPTURE", true)
        ) return Kind.CLICK
        return Kind.OTHER
    }

    fun dispatch(kind: Kind) {
        val now = SystemClock.uptimeMillis()
        if (kind == lastKind && now - lastKindAt < 180) return
        lastKind = kind
        lastKindAt = now
        val a = host?.get() ?: return
        a.runOnUiThread { a.onGlassesButton(kind) }
    }
}

class ButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val a = intent?.action ?: return
        Log.i(ButtonHub.TAG, "broadcast: $a")
        val kind = ButtonHub.classify(a, intent.extras)
        if (kind != ButtonHub.Kind.OTHER) {
            try { abortBroadcast() } catch (_: Exception) {}
            Log.i(ButtonHub.TAG, "aborted $kind")
            ButtonHub.dispatch(kind)
        }
    }
}
