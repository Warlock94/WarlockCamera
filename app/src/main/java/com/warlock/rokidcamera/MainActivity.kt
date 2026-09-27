package com.warlock.rokidcamera

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.*
import android.view.*
import android.widget.*
import java.lang.ref.WeakReference

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var texture: TextureView
    private lateinit var recText: TextView
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var overlay: ScrollView
    private lateinit var overlayBox: LinearLayout

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var imageReader: ImageReader? = null
    private var recording = false
    private var cameraId = "0"
    private var sensorOrientation = 90
    private var jpegSizes: Array<android.util.Size> = emptyArray()
    private var videoSizes: Array<android.util.Size> = emptyArray()
    private var pressAt = 0L
    private var pressKey = -1
    private var lastBtnAt = 0L
    private var lastNavAt = 0L
    private var leaving = false
    private var padTaps = 0
    private var lastConfirmAt = 0L
    private var lastPadAt = 0L
    private var shutterDownAt = 0L
    private var suppressClickUntil = 0L
    private var longHandled = false
    private var padDownX = 0f
    private var padDownY = 0f
    private var padDownAt = 0L
    private var padMoved = false
    private var padFired = false
    private var recFile: java.io.File? = null
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("camera", MODE_PRIVATE) }
    private val receiver = ButtonReceiver()
    private var receiverOn = false

    private enum class Screen {
        CAMERA, MENU, DURATION, PHOTO, VIDEO, HZ, FILES, FILE, PREVIEW, HELP, WIFI, WIPE, PERMS
    }
    private var screen = Screen.CAMERA
    private var sel = 0
    private var files = listOf<MediaVault.Item>()
    private var fileIdx = 0
    private var prevField = 0
    private var wifiUrl = ""

    private val recLimit = Runnable { if (recording) stopRecording() }
    private val heartbeat = object : Runnable {
        override fun run() {
            pingGuard(ButtonGuardService.ACTION_BEAT)
            handler.postDelayed(this, 2000)
        }
    }
    private val padSingle = Runnable {
        padTaps = 0
        if (SystemClock.uptimeMillis() < suppressClickUntil) return@Runnable
        if (screen == Screen.CAMERA) openMenu() else activate()
    }

    private fun onSwipeDir(forward: Boolean) {
        val now = SystemClock.uptimeMillis()
        suppressClickUntil = now + 520
        padTaps = 0
        handler.removeCallbacks(padSingle)
        markPad()
        if (screen == Screen.CAMERA) {
            if (navOk()) openMenu()
            return
        }
        if (screen == Screen.HELP) return
        if (screen == Screen.PREVIEW && sel < prevKeys.size) {
            nudgePreview(if (forward) 1 else -1)
            return
        }
        if (navOk()) move(if (forward) 1 else -1)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        ButtonHub.host = WeakReference(this)
        buildUi()
        registerBtn()
        startService(Intent(this, GuardService::class.java))
        if (cameraGranted()) {
            claimButton()
            openCamera()
        } else {
            releaseButton()
            status.text = "ЖДУ РАЗРЕШЕНИЕ КАМЕРЫ"
            handler.post { requestCameraDialog() }
        }
    }

    private fun cameraGranted() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraDialog() {
        if (cameraGranted()) return
        releaseButton()
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        requestPermissions(arrayOf(Manifest.permission.CAMERA), 50)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        if (!cameraGranted()) return
        if (c == 50 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            releaseButton()
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 51)
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        claimButton()
        if (camera == null) openCamera()
        if (screen == Screen.PERMS) hideOverlay()
    }

    private fun registerBtn() {
        if (receiverOn) return
        try {
            if (Build.VERSION.SDK_INT >= 33)
                registerReceiver(receiver, ButtonHub.filter(), Context.RECEIVER_EXPORTED)
            else @Suppress("DEPRECATION") registerReceiver(receiver, ButtonHub.filter())
            receiverOn = true
        } catch (e: Exception) {
            android.util.Log.w(ButtonHub.TAG, "reg: ${e.message}")
        }
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        texture = TextureView(this)
        val w = prefs.getInt("w", 180)
        val h = prefs.getInt("h", 135)
        val x = prefs.getInt("x", 280)
        val y = prefs.getInt("y", 20)
        root.addView(texture, FrameLayout.LayoutParams(w, h).apply { leftMargin = x; topMargin = y })

        recText = TextView(this).apply {
            text = "● REC"; textSize = 16f; setTextColor(Color.RED); visibility = View.GONE; setPadding(8, 0, 0, 0)
        }
        root.addView(recText, FrameLayout.LayoutParams(140, 40).apply { leftMargin = x; topMargin = y })

        status = TextView(this).apply {
            text = "WARLOCK 2.6.1"; textSize = 12f; setTextColor(Color.GREEN)
            setBackgroundColor(Color.BLACK); setPadding(4, 2, 4, 2)
        }
        root.addView(status, FrameLayout.LayoutParams(220, 36).apply { leftMargin = x; topMargin = y + h - 36 })

        hint = TextView(this).apply {
            text = "тап меню · свайп тоже меню · 2× выход · запись только пунктом меню"
            textSize = 11f; setTextColor(Color.GREEN); setBackgroundColor(0xCC000000.toInt()); setPadding(8, 4, 8, 6)
        }
        root.addView(hint, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM })

        overlayBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.BLACK)
        }
        overlay = ScrollView(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
            addView(overlayBox)
        }
        root.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)
        texture.alpha = prefs.getInt("alpha", 100) / 100f
    }

    fun onGlassesButton(kind: ButtonHub.Kind) {
        val now = SystemClock.uptimeMillis()
        if (now - lastPadAt < 800) return
        lastBtnAt = now
        when (kind) {
            ButtonHub.Kind.DOWN -> {
                shutterDownAt = now
                longHandled = false
            }
            ButtonHub.Kind.LONG -> {
                if (!longHandled) {
                    longHandled = true
                    suppressClickUntil = now + 900
                    if (screen != Screen.CAMERA) hideOverlay()
                    if (!recording) startRecording()
                }
            }
            ButtonHub.Kind.UP -> {
                suppressClickUntil = now + 900
                val short = shutterDownAt != 0L && now - shutterDownAt < 900
                if (recording && !longHandled) {
                    if (screen != Screen.CAMERA) hideOverlay()
                    stopRecording()
                } else if (!longHandled && short) {
                    if (screen == Screen.CAMERA) takePhoto() else activate()
                }
                longHandled = false
                shutterDownAt = 0L
            }
            ButtonHub.Kind.CLICK -> {
                if (now < suppressClickUntil) return
                if (screen == Screen.CAMERA) captureOrToggle() else activate()
            }
            ButtonHub.Kind.DOUBLE -> if (screen == Screen.CAMERA) openMenu() else back()
            ButtonHub.Kind.OTHER -> {}
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!cameraGranted()) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) {
            return super.dispatchKeyEvent(event)
        }
        val code = event.keyCode
        val shutter = code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_VOLUME_DOWN ||
            code == KeyEvent.KEYCODE_CAMERA || code == KeyEvent.KEYCODE_HEADSETHOOK ||
            code == KeyEvent.KEYCODE_FOCUS
        if (shutter && screen == Screen.CAMERA) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (pressKey != code) {
                    pressKey = code
                    pressAt = SystemClock.uptimeMillis()
                    lastBtnAt = pressAt
                    val lp = prefs.getInt("longPress", 900)
                    handler.postDelayed({
                        if (pressKey == code && SystemClock.uptimeMillis() - pressAt >= lp) {
                            toggleRecording(); pressKey = -1
                        }
                    }, lp.toLong())
                }
            } else if (code == pressKey) {
                val dt = SystemClock.uptimeMillis() - pressAt
                pressKey = -1
                if (dt < prefs.getInt("longPress", 900)) captureOrToggle()
            }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (event.repeatCount > 0) return true
        when (code) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (SystemClock.uptimeMillis() < suppressClickUntil) return true
                onPadTap()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                onSwipeDir(true)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                onSwipeDir(false)
                return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (SystemClock.uptimeMillis() < suppressClickUntil) return true
                if (screen != Screen.CAMERA) back() else exitApp()
                return true
            }
            KeyEvent.KEYCODE_TV, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_BOOKMARK -> {
                if (screen == Screen.CAMERA) openMenu() else back()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun navOk(): Boolean {
        val n = SystemClock.uptimeMillis()
        if (n - lastNavAt < 380) return false
        lastNavAt = n
        return true
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!cameraGranted()) return super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                padDownX = ev.x
                padDownY = ev.y
                padDownAt = SystemClock.uptimeMillis()
                padMoved = false
                padFired = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - padDownX
                val dy = ev.y - padDownY
                if (!padFired && (kotlin.math.abs(dx) > 14f || kotlin.math.abs(dy) > 14f)) {
                    padFired = true
                    fireSwipe(dx, dy)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (padFired || ev.actionMasked == MotionEvent.ACTION_CANCEL) return true
                val dx = ev.x - padDownX
                val dy = ev.y - padDownY
                val dt = SystemClock.uptimeMillis() - padDownAt
                if (kotlin.math.abs(dx) > 14f || kotlin.math.abs(dy) > 14f) fireSwipe(dx, dy)
                else if (dt in 1..400) onPadTap()
            }
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            val dir = when {
                kotlin.math.abs(v) >= kotlin.math.abs(h) && v != 0f -> v < 0f
                h != 0f -> h > 0f
                else -> null
            }
            if (dir != null) {
                onSwipeDir(dir)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    private fun fireSwipe(dx: Float, dy: Float) {
        val ax = kotlin.math.abs(dx)
        val ay = kotlin.math.abs(dy)
        onSwipeDir(if (ax >= ay) dx > 0 else dy > 0)
    }

    private fun onPadTap() {
        val now = SystemClock.uptimeMillis()
        if (now < suppressClickUntil) return
        markPad()
        val gap = now - lastConfirmAt
        if (gap < 220) return
        lastConfirmAt = now
        handler.removeCallbacks(padSingle)
        if (padTaps == 1 && gap < 500) {
            padTaps = 0
            onPadDouble()
        } else {
            padTaps = 1
            handler.postDelayed(padSingle, 500)
        }
    }

    private fun onPadDouble() {
        padTaps = 0
        handler.removeCallbacks(padSingle)
        if (screen == Screen.CAMERA || screen == Screen.PERMS) exitApp() else back()
    }

    private fun markPad() {
        lastPadAt = SystemClock.uptimeMillis()
    }

    private fun exitApp() {
        leaving = true
        padTaps = 0
        handler.removeCallbacks(padSingle)
        handler.removeCallbacks(recLimit)
        releaseButton()
        WifiShare.stop()
        try { if (recording) stopRecording() } catch (_: Exception) {}
        finish()
    }

    private fun openMenu() {
        screen = Screen.MENU
        sel = if (recording) 1 else 0
        showOverlay()
    }

    private fun back() {
        if (screen == Screen.WIFI) {
            WifiShare.stop()
            wifiUrl = ""
        }
        if (screen == Screen.PERMS) {
            showOverlay()
            return
        }
        val keep = if (screen == Screen.FILE) (fileIdx + 2).coerceAtMost(files.size + 2) else 0
        val next = when (screen) {
            Screen.FILE, Screen.WIFI, Screen.WIPE -> Screen.FILES
            Screen.FILES, Screen.DURATION, Screen.PHOTO, Screen.VIDEO,
            Screen.HZ, Screen.PREVIEW, Screen.HELP -> Screen.MENU
            else -> Screen.CAMERA
        }
        screen = next
        sel = keep
        if (screen == Screen.CAMERA) hideOverlay() else showOverlay()
    }

    private fun hideOverlay() {
        overlay.visibility = View.GONE
        hint.visibility = View.VISIBLE
        screen = Screen.CAMERA
    }

    private val menuCount = 12
    private val fileActs = arrayOf("Отправить по Bluetooth", "Удалить", "Назад")
    private val durations = intArrayOf(0, 30, 120, 300, 600, 900)
    private val durationLabels = arrayOf(
        "Без лимита", "30 секунд", "2 минуты", "5 минут", "10 минут", "15 минут"
    )
    private val photoLabels = arrayOf("4K 3:4", "4K 9:16", "3K 4:3")
    private val photoDisp = arrayOf(intArrayOf(3024, 4032), intArrayOf(2268, 4032), intArrayOf(3024, 2268))
    private val videoLabels = arrayOf(
        "3K 30fps 9:16", "2.5K 30fps 3:4", "2.5K 30fps 9:16", "2K 30fps 9:19.5", "2.5K 30fps 4:3"
    )
    private val videoDisp = arrayOf(
        intArrayOf(1728, 3072), intArrayOf(1920, 2560), intArrayOf(1440, 2560),
        intArrayOf(1080, 2340), intArrayOf(2560, 1920)
    )
    private val hzLabels = arrayOf("Авто", "50 Гц", "60 Гц")
    private val helpLines = arrayOf(
        "КАМЕРА",
        "тап или свайп — меню",
        "двойной тап — выход",
        "запись только пунктом меню",
        "МЕНЮ",
        "свайп — только смена пункта",
        "тап — выбрать",
        "двойной тап — назад",
        "ПРЕВЬЮ",
        "свайп вперёд — больше",
        "свайп назад — меньше",
        "тап — следующий пункт",
        "Стандарт — сброс"
    )
    private val prevKeys = arrayOf("w", "h", "x", "y", "alpha", "longPress")
    private val prevNames = arrayOf("Ширина превью", "Высота превью", "Позиция X", "Позиция Y", "Прозрачность", "Длинное нажатие мс")
    private val prevMin = intArrayOf(40, 40, 0, 0, 10, 300)
    private val prevMax = intArrayOf(480, 480, 400, 600, 100, 1500)
    private val prevDef = intArrayOf(180, 135, 280, 20, 100, 900)

    private fun count(): Int = when (screen) {
        Screen.MENU -> menuCount
        Screen.DURATION -> durations.size
        Screen.PHOTO -> photoLabels.size
        Screen.VIDEO -> videoLabels.size
        Screen.HZ -> hzLabels.size
        Screen.FILES -> files.size + 3
        Screen.FILE -> fileActs.size
        Screen.PREVIEW -> prevKeys.size + 2
        Screen.HELP -> helpLines.size
        Screen.WIFI -> 1
        Screen.WIPE -> 2
        Screen.PERMS -> 2
        Screen.CAMERA -> 0
    }

    private fun move(d: Int) {
        val n = count()
        if (n <= 0) return
        sel = (sel + d + n) % n
        showOverlay()
    }

    private fun activate() {
        when (screen) {
            Screen.MENU -> when (sel) {
                0 -> if (!recording) { hideOverlay(); startRecording() } else status.text = "УЖЕ ИДЁТ ЗАПИСЬ"
                1 -> if (recording) { hideOverlay(); stopRecording() } else status.text = "НЕТ ЗАПИСИ"
                2 -> {
                    hideOverlay()
                    if (recording) status.text = "Сначала нажми Стоп" else takePhoto()
                }
                3 -> { files = MediaVault.list(this); fileIdx = 0; sel = 0; screen = Screen.FILES; showOverlay() }
                4 -> {
                    val cur = prefs.getInt("videoSec", 0)
                    sel = durations.indexOf(cur).let { if (it < 0) 0 else it }
                    screen = Screen.DURATION; showOverlay()
                }
                5 -> { sel = prefs.getInt("photoMode", 0).coerceIn(0, photoLabels.lastIndex); screen = Screen.PHOTO; showOverlay() }
                6 -> { sel = prefs.getInt("videoMode", 1).coerceIn(0, videoLabels.lastIndex); screen = Screen.VIDEO; showOverlay() }
                7 -> { sel = prefs.getInt("hz", 0).coerceIn(0, hzLabels.lastIndex); screen = Screen.HZ; showOverlay() }
                8 -> { prevField = 0; sel = 0; screen = Screen.PREVIEW; showOverlay() }
                9 -> { sel = 0; screen = Screen.HELP; showOverlay() }
                10 -> hideOverlay()
                else -> exitApp()
            }
            Screen.DURATION -> {
                prefs.edit().putInt("videoSec", durations[sel]).apply()
                status.text = "ВИДЕО: ${durationLabels[sel]}"
                hideOverlay()
            }
            Screen.PHOTO -> {
                prefs.edit().putInt("photoMode", sel).apply()
                status.text = "ФОТО: ${photoLabels[sel]}"
                hideOverlay()
            }
            Screen.VIDEO -> {
                prefs.edit().putInt("videoMode", sel).apply()
                status.text = if (recording) "ВИДЕО С ${videoLabels[sel]} ПОСЛЕ СТОП" else "ВИДЕО: ${videoLabels[sel]}"
                hideOverlay()
            }
            Screen.HZ -> {
                prefs.edit().putInt("hz", sel).apply()
                status.text = "СЕТЬ: ${hzLabels[sel]}"
                hideOverlay()
                if (!recording) startPreview()
            }
            Screen.FILES -> when {
                sel == 0 -> startWifiShare()
                sel == 1 -> {
                    if (files.isEmpty()) status.text = "НЕТ ФАЙЛОВ"
                    else { sel = 0; screen = Screen.WIPE; showOverlay() }
                }
                sel >= files.size + 2 -> hideOverlay()
                else -> { fileIdx = sel - 2; sel = 0; screen = Screen.FILE; showOverlay() }
            }
            Screen.FILE -> when (sel) {
                0 -> {
                    val item = files.getOrNull(fileIdx)
                    status.text = if (item != null) MediaVault.sendBluetooth(this, item) else "НЕТ ФАЙЛА"
                    hideOverlay()
                }
                1 -> {
                    files.getOrNull(fileIdx)?.let { MediaVault.delete(it) }
                    files = MediaVault.list(this)
                    sel = 0; screen = Screen.FILES; showOverlay()
                }
                else -> { sel = (fileIdx + 2).coerceAtMost(files.size + 2); screen = Screen.FILES; showOverlay() }
            }
            Screen.WIFI -> {
                WifiShare.stop()
                wifiUrl = ""
                sel = 0
                screen = Screen.FILES
                showOverlay()
            }
            Screen.WIPE -> {
                if (sel == 0) {
                    WifiShare.stop()
                    val n = MediaVault.deleteAll(this)
                    files = MediaVault.list(this)
                    status.text = "УДАЛЕНО $n"
                }
                sel = 0
                screen = Screen.FILES
                showOverlay()
            }
            Screen.PREVIEW -> when {
                sel == prevKeys.size -> resetPreview()
                sel > prevKeys.size -> { rebuildPreviewLayout(); hideOverlay() }
                else -> { sel = (sel + 1) % count(); showOverlay() }
            }
            Screen.HELP -> back()
            Screen.PERMS -> requestCameraDialog()
            Screen.CAMERA -> openMenu()
        }
    }

    private fun nudgePreview(dir: Int) {
        if (!navOk()) return
        if (sel >= prevKeys.size) {
            move(dir)
            return
        }
        val step = when (sel) {
            5 -> 50
            4 -> 5
            else -> 10
        }
        adjustPreview(if (dir > 0) step else -step)
    }

    private fun adjustPreview(delta: Int) {
        if (screen != Screen.PREVIEW || sel >= prevKeys.size) return
        val key = prevKeys[sel]
        val v = prefs.getInt(key, prevDef[sel]) + delta
        prefs.edit().putInt(key, v.coerceIn(prevMin[sel], prevMax[sel])).apply()
        rebuildPreviewLayout()
        showOverlay()
    }

    private fun resetPreview() {
        val edit = prefs.edit()
        for (i in prevKeys.indices) edit.putInt(prevKeys[i], prevDef[i])
        edit.apply()
        rebuildPreviewLayout()
        status.text = "ПРЕВЬЮ: СТАНДАРТ"
        showOverlay()
    }

    private fun startWifiShare() {
        if (files.isEmpty()) {
            status.text = "НЕТ ФАЙЛОВ"
            return
        }
        val result = WifiShare.start(this)
        if (!result.startsWith("http")) {
            status.text = result
            return
        }
        wifiUrl = result
        sel = 0
        screen = Screen.WIFI
        showOverlay()
    }

    private fun showOverlay() {
        overlay.visibility = View.VISIBLE
        hint.visibility = View.GONE
        overlayBox.removeAllViews()
        overlayBox.addView(titleTv(when (screen) {
            Screen.MENU -> if (recording) "МЕНЮ   ● REC" else "МЕНЮ"
            Screen.DURATION -> "ДЛИТЕЛЬНОСТЬ ВИДЕО"
            Screen.PHOTO -> "СООТНОШЕНИЕ ФОТО"
            Screen.VIDEO -> "НАСТРОЙКИ ВИДЕО"
            Screen.HZ -> "ЧАСТОТА СЕТИ"
            Screen.FILES -> "ФАЙЛЫ"
            Screen.FILE -> files.getOrNull(fileIdx)?.name ?: "ФАЙЛ"
            Screen.WIFI -> if (wifiUrl.isBlank()) "WI‑FI" else wifiUrl
            Screen.WIPE -> "УДАЛИТЬ ВСЕ?"
            Screen.PREVIEW -> "ПРЕВЬЮ"
            Screen.HELP -> "ТАЧПАД"
            Screen.PERMS -> "РАЗРЕШЕНИЕ КАМЕРЫ"
            Screen.CAMERA -> ""
        }))
        val n = count()
        if (n == 0) return
        sel = sel.coerceIn(0, n - 1)
        for (i in 0 until n) overlayBox.addView(row(i, label(i), i == sel))
        overlayBox.addView(hintTv(when (screen) {
            Screen.PREVIEW -> "свайп только меняет число · тап — дальше · 2× назад"
            Screen.WIFI -> "телефон в той же сети · открой адрес · Стоп закрывает"
            Screen.WIPE -> "это сотрёт все записи приложения"
            Screen.PERMS -> "тап — этот пункт · свайп — другой"
            Screen.CAMERA -> ""
            else -> "свайп только двигает пункт · тап выбирает · 2× назад"
        }))
        overlay.post { overlay.scrollTo(0, (sel * dp(48)).coerceAtLeast(0)) }
    }

    private fun label(i: Int): String = when (screen) {
        Screen.MENU -> menuText(i)
        Screen.DURATION -> mark(durations[i] == prefs.getInt("videoSec", 0), durationLabels[i])
        Screen.PHOTO -> mark(i == prefs.getInt("photoMode", 0), photoLabels[i])
        Screen.VIDEO -> mark(i == prefs.getInt("videoMode", 1), videoLabels[i])
        Screen.HZ -> mark(i == prefs.getInt("hz", 0), hzLabels[i])
        Screen.FILES -> when {
            i == 0 -> "Отправить все по Wi‑Fi"
            i == 1 -> "Удалить все"
            i >= files.size + 2 -> "← к камере"
            else -> files[i - 2].label
        }
        Screen.FILE -> fileActs[i]
        Screen.PREVIEW -> when {
            i == prevKeys.size -> "Стандарт"
            i > prevKeys.size -> "Готово"
            else -> "${prevNames[i]}  ${prefs.getInt(prevKeys[i], prevDef[i])}"
        }
        Screen.HELP -> helpLines[i]
        Screen.WIFI -> "Стоп"
        Screen.WIPE -> if (i == 0) "Да, удалить" else "Нет"
        Screen.PERMS -> if (i == 0) "Запросить разрешение" else "Открыть настройки"
        else -> ""
    }

    private fun titleTv(t: String) = TextView(this).apply {
        text = t; textSize = 20f; setTextColor(Color.GREEN); setPadding(0, 0, 0, 12)
    }
    private fun hintTv(t: String) = TextView(this).apply {
        text = t; textSize = 12f; setTextColor(0xFF66AA77.toInt()); setPadding(0, 16, 0, 8)
    }
    private fun row(i: Int, t: String, on: Boolean) = TextView(this).apply {
        text = if (on) "▶ $t" else "    $t"
        textSize = 18f
        setPadding(8, 10, 8, 10)
        if (on) { setBackgroundColor(Color.GREEN); setTextColor(Color.BLACK) }
        else { setBackgroundColor(Color.BLACK); setTextColor(Color.GREEN) }
        setOnClickListener { sel = i; showOverlay() }
    }

    private fun menuText(i: Int): String = when (i) {
        0 -> {
            val sec = prefs.getInt("videoSec", 0)
            val lim = durationLabels.getOrNull(durations.indexOf(sec)) ?: "Без лимита"
            if (recording) "Запись видео   ●" else "Запись видео   $lim"
        }
        1 -> if (recording) "Стоп   ● REC" else "Стоп"
        2 -> "Снимок"
        3 -> "Файлы"
        4 -> "Длительность видео"
        5 -> "Фото   ${photoLabels[prefs.getInt("photoMode", 0).coerceIn(0, photoLabels.lastIndex)]}"
        6 -> "Видео   ${videoLabels[prefs.getInt("videoMode", 1).coerceIn(0, videoLabels.lastIndex)]}"
        7 -> "Сеть   ${hzLabels[prefs.getInt("hz", 0).coerceIn(0, hzLabels.lastIndex)]}"
        8 -> "Превью"
        9 -> "Тачпад"
        10 -> "К камере"
        else -> "Выход"
    }

    private fun mark(on: Boolean, text: String) = if (on) "$text  ●" else text

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (screen == Screen.PREVIEW && sel < prevKeys.size) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                adjustPreview(if (sel == 5) 50 else 10); return true
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                adjustPreview(if (sel == 5) -50 else -10); return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun captureOrToggle() { if (recording) stopRecording() else takePhoto() }
    private fun toggleRecording() { if (recording) stopRecording() else startRecording() }

    private fun openCamera() {
        val cm = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            cameraId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.first()
            val chars = cm.getCameraCharacteristics(cameraId)
            val reported = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            sensorOrientation = if (reported == 0) 90 else reported
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            jpegSizes = map?.getOutputSizes(android.graphics.ImageFormat.JPEG) ?: emptyArray()
            videoSizes = map?.getOutputSizes(MediaRecorder::class.java)
                ?: map?.getOutputSizes(SurfaceTexture::class.java)
                ?: emptyArray()
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { camera = c; startPreview() }
                override fun onDisconnected(c: CameraDevice) { c.close(); camera = null }
                override fun onError(c: CameraDevice, e: Int) { c.close(); camera = null; status.text = "CAM ERR $e" }
            }, handler)
        } catch (e: Exception) { status.text = "CAMERA: ${e.javaClass.simpleName}" }
    }

    private fun startPreview() {
        if (!texture.isAvailable || camera == null) {
            texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { startPreview() }
                override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
            }
            return
        }
        val surface = Surface(texture.surfaceTexture)
        try {
            camera!!.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    val r = camera!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    r.addTarget(surface)
                    applyAe(r)
                    s.setRepeatingRequest(r.build(), null, handler)
                    status.text = "CAMERA READY"
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { status.text = "PREVIEW ERROR" }
            }, handler)
        } catch (_: Exception) { status.text = "PREVIEW ERROR" }
    }

    private fun takePhoto() {
        val c = camera ?: run { status.text = "НЕТ КАМЕРЫ"; return }
        try {
            try { imageReader?.close() } catch (_: Exception) {}
            val photo = photoSize()
            val reader = ImageReader.newInstance(photo.width, photo.height, android.graphics.ImageFormat.JPEG, 2)
            imageReader = reader
            val out = MediaVault.newFile(this, "jpg")
            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buf = image.planes[0].buffer
                    val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                    out.outputStream().use { it.write(bytes) }
                    status.text = "ФОТО · ${out.name}"
                } catch (_: Exception) { status.text = "PHOTO ERROR" }
                finally {
                    image.close()
                    handler.postDelayed({ if (screen == Screen.CAMERA) status.text = "CAMERA READY" }, 900)
                }
            }, handler)
            val preview = Surface(texture.surfaceTexture)
            c.createCaptureSession(listOf(preview, reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(sess: CameraCaptureSession) {
                    session = sess
                    val req = c.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    req.addTarget(reader.surface)
                    req.set(CaptureRequest.JPEG_ORIENTATION, captureOrientation())
                    applyAe(req)
                    try {
                        sess.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(
                                s: CameraCaptureSession,
                                request: CaptureRequest,
                                result: TotalCaptureResult
                            ) {
                                handler.postDelayed({
                                    if (!recording) startPreview()
                                    try { reader.close() } catch (_: Exception) {}
                                    if (imageReader === reader) imageReader = null
                                }, 250)
                            }

                            override fun onCaptureFailed(
                                s: CameraCaptureSession,
                                request: CaptureRequest,
                                failure: CaptureFailure
                            ) {
                                status.text = "PHOTO ERROR"
                                handler.post { if (!recording) startPreview() }
                            }
                        }, handler)
                    } catch (_: Exception) {
                        status.text = "PHOTO ERROR"
                        if (!recording) startPreview()
                    }
                }
                override fun onConfigureFailed(sess: CameraCaptureSession) {
                    status.text = "PHOTO SESSION ERROR"; reader.close(); imageReader = null
                    if (!recording) startPreview()
                }
            }, handler)
        } catch (e: Exception) { status.text = "PHOTO ERROR: ${e.javaClass.simpleName}" }
    }

    private fun startRecording() {
        if (camera == null) return
        try {
            val f = MediaVault.newFile(this, "mp4")
            recFile = f
            val video = videoSize()
            recorder = MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(if (video.width * video.height > 1920 * 1080) 12_000_000 else 6_000_000)
                setVideoSize(video.width, video.height)
                setOrientationHint(captureOrientation())
                setOutputFile(f.absolutePath)
                prepare()
            }
            val preview = Surface(texture.surfaceTexture)
            val recSurface = recorder!!.surface
            camera!!.createCaptureSession(listOf(preview, recSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    val r = camera!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    r.addTarget(preview); r.addTarget(recSurface)
                    applyAe(r)
                    s.setRepeatingRequest(r.build(), null, handler)
                    recorder!!.start()
                    recording = true
                    recText.visibility = View.VISIBLE
                    val sec = prefs.getInt("videoSec", 0)
                    val mode = videoLabels[prefs.getInt("videoMode", 1).coerceIn(0, videoLabels.lastIndex)]
                    status.text = if (sec > 0) "REC $mode  ${sec}с" else "REC $mode"
                    handler.removeCallbacks(recLimit)
                    if (sec > 0) handler.postDelayed(recLimit, sec * 1000L)
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { status.text = "REC SESSION ERROR" }
            }, handler)
        } catch (e: Exception) { status.text = "REC ERROR: ${e.javaClass.simpleName}" }
    }

    private fun stopRecording() {
        if (!recording) return
        handler.removeCallbacks(recLimit)
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.reset() } catch (_: Exception) {}
        recorder?.release(); recorder = null
        recording = false
        recText.visibility = View.GONE
        startPreview()
        status.text = "СОХРАНЕНО · ${recFile?.name ?: ""}"
    }

    private fun rebuildPreviewLayout() {
        val w = prefs.getInt("w", 180); val h = prefs.getInt("h", 135)
        val x = prefs.getInt("x", 280); val y = prefs.getInt("y", 20)
        (texture.layoutParams as FrameLayout.LayoutParams).apply { width = w; height = h; leftMargin = x; topMargin = y }
        texture.alpha = prefs.getInt("alpha", 100) / 100f
        texture.requestLayout()
    }

    override fun onPause() {
        super.onPause()
        if (!leaving && !isFinishing && SystemClock.uptimeMillis() - lastBtnAt < 2000) {
            handler.postDelayed({
                if (!hasWindowFocus() && !leaving) {
                    startActivity(Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
            }, 180)
        }
    }

    private fun captureOrientation(): Int {
        val display = when (windowManager.defaultDisplay.rotation) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensorOrientation + display) % 360
    }

    private fun applyAe(b: CaptureRequest.Builder) {
        val mode = when (prefs.getInt("hz", 0)) {
            1 -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ
            2 -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
            else -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
        }
        try { b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, mode) } catch (_: Exception) {}
    }

    private fun photoSize(): android.util.Size {
        val i = prefs.getInt("photoMode", 0).coerceIn(0, photoDisp.lastIndex)
        return pickSize(sensorRequest(photoDisp[i][0], photoDisp[i][1]), jpegSizes)
    }

    private fun videoSize(): android.util.Size {
        val i = prefs.getInt("videoMode", 1).coerceIn(0, videoDisp.lastIndex)
        return pickSize(sensorRequest(videoDisp[i][0], videoDisp[i][1]), videoSizes)
    }

    private fun sensorRequest(dispW: Int, dispH: Int): android.util.Size {
        val swap = captureOrientation() % 180 != 0
        return if (swap) android.util.Size(dispH, dispW) else android.util.Size(dispW, dispH)
    }

    private fun pickSize(want: android.util.Size, choices: Array<android.util.Size>): android.util.Size {
        if (choices.isEmpty()) return want
        choices.firstOrNull { it.width == want.width && it.height == want.height }?.let { return it }
        val wantRatio = want.width.toFloat() / want.height.coerceAtLeast(1)
        val close = choices.filter {
            kotlin.math.abs(it.width.toFloat() / it.height.coerceAtLeast(1) - wantRatio) < 0.08f
        }
        val pool = if (close.isNotEmpty()) close else choices.toList()
        return pool.minBy { kotlin.math.abs(it.width.toLong() * it.height - want.width.toLong() * want.height) }
    }

    private fun pingGuard(action: String) {
        val intent = Intent(this, ButtonGuardService::class.java).setAction(action)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (_: Exception) {
            if (action == ButtonGuardService.ACTION_RELEASE) RokidKeys.restore(this)
            else RokidKeys.claim(this)
        }
    }

    private fun claimButton() {
        pingGuard(ButtonGuardService.ACTION_CLAIM)
        handler.removeCallbacks(heartbeat)
        handler.post(heartbeat)
    }

    private fun releaseButton() {
        handler.removeCallbacks(heartbeat)
        pingGuard(ButtonGuardService.ACTION_RELEASE)
    }

    override fun onUserLeaveHint() {
        if (!recording) releaseButton()
        super.onUserLeaveHint()
    }

    override fun onResume() {
        super.onResume()
        leaving = false
        registerBtn()
        if (cameraGranted()) {
            claimButton()
            if (camera == null) openCamera()
        } else {
            releaseButton()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        leaving = false
    }

    override fun onDestroy() {
        leaving = true
        releaseButton()
        ButtonHub.host = null
        handler.removeCallbacks(recLimit)
        try { if (receiverOn) unregisterReceiver(receiver) } catch (_: Exception) {}
        try { stopService(Intent(this, GuardService::class.java)) } catch (_: Exception) {}
        try { if (recording) stopRecording() } catch (_: Exception) {}
        WifiShare.stop()
        imageReader?.close(); imageReader = null
        session?.close(); camera?.close(); camera = null
        super.onDestroy()
    }
}
