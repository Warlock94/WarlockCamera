package com.warlock.rokidcamera

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MediaVault {
    data class Item(val file: File) {
        val name: String get() = file.name
        val isVideo: Boolean get() = name.endsWith(".mp4", true)
        val label: String get() {
            val t = SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date(file.lastModified()))
            val kb = file.length() / 1024
            val sz = if (kb >= 1024) "${kb / 1024} МБ" else "$kb КБ"
            val kind = if (isVideo) "VID" else "JPG"
            return "$kind  $t  $sz"
        }
    }

    fun dir(ctx: Context): File {
        val d = File(ctx.getExternalFilesDir(null), "media")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun newFile(ctx: Context, ext: String): File {
        val name = "WARLOCK_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "." + ext
        return File(dir(ctx), name)
    }

    fun list(ctx: Context): List<Item> =
        dir(ctx).listFiles()
            ?.filter { it.isFile && it.length() > 0 }
            ?.sortedByDescending { it.lastModified() }
            ?.map { Item(it) }
            ?: emptyList()

    fun delete(item: Item): Boolean = item.file.delete()

    fun deleteAll(ctx: Context): Int {
        var n = 0
        for (item in list(ctx)) if (item.file.delete()) n++
        return n
    }

    fun sendBluetooth(ctx: Context, item: Item): String {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", item.file)
        val mime = if (item.isVideo) "video/mp4" else "image/jpeg"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pkgs = arrayOf("com.android.bluetooth", "com.android.bluetooth.opp")
        for (p in pkgs) {
            try {
                ctx.startActivity(Intent(send).setPackage(p))
                return "BLUETOOTH…"
            } catch (_: Exception) {}
        }
        return try {
            ctx.startActivity(Intent.createChooser(send, "Bluetooth"))
            "ОТПРАВКА…"
        } catch (e: Exception) {
            "BT ERROR"
        }
    }
}
