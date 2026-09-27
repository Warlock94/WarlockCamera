package com.warlock.rokidcamera

import android.content.Context
import android.content.Intent
import android.util.Log

object RokidKeys {
    private const val ACTION = "com.rokid.os.master.assist.server.cmd"
    private const val PKG = "com.rokid.os.sprite.assistserver"

    fun claim(context: Context) = set(context, "none", "none")

    fun restore(context: Context) = set(context, "picture", "video")

    private fun set(context: Context, shortPress: String, longPress: String) {
        try {
            val value = "[{\"key\":\"settings_interaction_shortPressFun\",\"value\":\"$shortPress\"}," +
                "{\"key\":\"settings_interaction_longPressFun\",\"value\":\"$longPress\"}]"
            val intent = Intent(ACTION).setPackage(PKG)
            intent.putExtra("cmd_type", "setting_change")
            intent.putExtra("value", value)
            context.sendBroadcast(intent)
            Log.i(ButtonHub.TAG, "button setting short=$shortPress long=$longPress")
        } catch (e: Exception) {
            Log.w(ButtonHub.TAG, "button setting failed: ${e.message}")
        }
    }
}
