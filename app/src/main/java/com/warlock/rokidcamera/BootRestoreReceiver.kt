package com.warlock.rokidcamera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context != null) RokidKeys.restore(context)
    }
}
