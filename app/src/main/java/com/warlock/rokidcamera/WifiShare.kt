package com.warlock.rokidcamera

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object WifiShare {
    @Volatile var url: String = ""
        private set

    private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start(ctx: Context): String {
        stop()
        val ip = localIp() ?: return "НЕТ WI‑FI"
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port))
            server = ss
            running = true
            val app = ctx.applicationContext
            Thread({
                while (running) {
                    val sock = try {
                        ss.accept()
                    } catch (_: Exception) {
                        break
                    }
                    Thread({ serve(app, sock) }, "wifi-share").apply { isDaemon = true }.start()
                }
            }, "wifi-accept").apply { isDaemon = true }.start()
            url = "http://$ip:$port"
            url
        } catch (e: Exception) {
            stop()
            "WI‑FI ERROR"
        }
    }

    fun stop() {
        running = false
        url = ""
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun serve(ctx: Context, sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 12000
                val line = s.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine() ?: return
                val path = line.split(" ").getOrNull(1)?.substringBefore("?") ?: "/"
                val out = s.getOutputStream()
                when {
                    path == "/" || path == "/index.html" -> html(ctx, out)
                    path == "/all.zip" -> zipAll(ctx, out)
                    path.startsWith("/f/") -> file(ctx, path.removePrefix("/f/"), out)
                    else -> writeHead(out, 404, "text/plain", 0L)
                }
                out.flush()
            } catch (_: Exception) {
            }
        }
    }

    private fun html(ctx: Context, out: java.io.OutputStream) {
        val items = MediaVault.list(ctx)
        val body = buildString {
            append("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>")
            append("<title>Warlock</title><body style='font-family:sans-serif;background:#111;color:#cfc'>")
            append("<h2>Warlock Camera</h2>")
            append("<p><a href='/all.zip' style='color:#9f9'>Скачать всё ZIP</a></p><ul>")
            for (item in items) {
                val name = item.name
                val href = "/f/" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                append("<li><a style='color:#9f9' href='").append(href).append("'>")
                append(esc(name)).append("</a> ").append(esc(item.label)).append("</li>")
            }
            append("</ul></body>")
        }.toByteArray(Charsets.UTF_8)
        writeHead(out, 200, "text/html; charset=utf-8", body.size.toLong())
        out.write(body)
    }

    private fun file(ctx: Context, raw: String, out: java.io.OutputStream) {
        val name = java.net.URLDecoder.decode(raw, "UTF-8")
        val item = MediaVault.list(ctx).firstOrNull { it.name == name }
        if (item == null || !item.file.isFile) {
            writeHead(out, 404, "text/plain", 0L)
            return
        }
        val mime = if (item.isVideo) "video/mp4" else "image/jpeg"
        stream(out, item.file, mime, item.name)
    }

    private fun zipAll(ctx: Context, out: java.io.OutputStream) {
        val items = MediaVault.list(ctx)
        writeHead(out, 200, "application/zip", -1L, "warlock.zip")
        ZipOutputStream(out).use { zip ->
            val buf = ByteArray(65536)
            for (item in items) {
                if (!item.file.isFile) continue
                zip.putNextEntry(ZipEntry(item.name))
                FileInputStream(item.file).use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        zip.write(buf, 0, n)
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun stream(out: java.io.OutputStream, file: File, mime: String, name: String) {
        writeHead(out, 200, mime, file.length(), name)
        FileInputStream(file).use { input ->
            val buf = ByteArray(65536)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
    }

    private fun writeHead(
        out: java.io.OutputStream,
        code: Int,
        mime: String,
        len: Long,
        filename: String? = null
    ) {
        val msg = if (code == 200) "OK" else "ERR"
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(msg).append("\r\n")
        sb.append("Content-Type: ").append(mime).append("\r\n")
        sb.append("Connection: close\r\n")
        if (len >= 0) sb.append("Content-Length: ").append(len).append("\r\n")
        if (filename != null) {
            sb.append("Content-Disposition: attachment; filename=\"").append(filename).append("\"\r\n")
        }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun esc(s: String) = s
        .replace("&", "&")
        .replace("<", "<")
        .replace(">", ">")

    private fun localIp(): String? {
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
        var fallback: String? = null
        for (nif in ifaces) {
            if (!nif.isUp || nif.isLoopback) continue
            for (addr in nif.inetAddresses) {
                if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                val host = addr.hostAddress ?: continue
                if (nif.name.startsWith("wlan") || nif.name.startsWith("ap") || nif.name.startsWith("swlan")) {
                    return host
                }
                if (fallback == null) fallback = host
            }
        }
        return fallback
    }

    private const val port = 8765
}
