package com.warlock.rokidcamera

import android.app.Activity
import android.os.Bundle
import android.widget.*
import android.graphics.Color

class SettingsActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("camera", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(1024, 1024)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24,24,24,24)
            setBackgroundColor(Color.BLACK)
        }

        fun label(t: String) = TextView(this).apply {
            text = t
            textSize = 16f
            setTextColor(Color.GREEN)
            setPadding(0,12,0,4)
        }

        fun seek(title: String, key: String, min: Int, max: Int, def: Int) {
            root.addView(label(title))
            val v = prefs.getInt(key, def)
            val bar = SeekBar(this).apply {
                this.max = max - min
                progress = v.coerceIn(min,max) - min
            }
            val value = TextView(this).apply {
                text = "$v"
                textSize = 14f
                setTextColor(Color.GREEN)
            }
            bar.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val n = p + min
                    value.text = n.toString()
                    prefs.edit().putInt(key,n).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            root.addView(bar)
            root.addView(value)
        }

        root.addView(TextView(this).apply {
            text = "WARLOCK CAMERA 2.0"
            textSize = 24f
            setTextColor(Color.GREEN)
            setPadding(0,0,0,16)
        })

        root.addView(label("Размер превью — ширина"))
        val w = SeekBar(this).apply {
            max = 480
            progress = prefs.getInt("w", 180).coerceIn(40,520) - 40
        }
        val wv = TextView(this).apply { setTextColor(Color.GREEN) }
        fun updateW() {
            val n = w.progress + 40
            wv.text = "$n px"
            prefs.edit().putInt("w",n).apply()
        }
        updateW()
        w.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { updateW() }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        root.addView(w); root.addView(wv)

        root.addView(label("Размер превью — высота"))
        val h = SeekBar(this).apply {
            max = 480
            progress = prefs.getInt("h", 135).coerceIn(40,520) - 40
        }
        val hv = TextView(this).apply { setTextColor(Color.GREEN) }
        fun updateH() {
            val n = h.progress + 40
            hv.text = "$n px"
            prefs.edit().putInt("h",n).apply()
        }
        updateH()
        h.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { updateH() }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        root.addView(h); root.addView(hv)

        seek("Положение X", "x", 0, 480, 280)
        seek("Положение Y", "y", 0, 640, 20)
        seek("Прозрачность", "alpha", 10, 100, 100)

        root.addView(label("Длина длинного нажатия для REC (мс)"))
        val rec = SeekBar(this).apply {
            max = 1200
            progress = prefs.getInt("longPress", 900).coerceIn(300,1500)-300
        }
        val rv = TextView(this).apply { setTextColor(Color.GREEN) }
        fun updateR() {
            val n=rec.progress+300
            rv.text="$n мс"
            prefs.edit().putInt("longPress",n).apply()
        }
        updateR()
        rec.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { updateR() }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        root.addView(rec); root.addView(rv)

        root.addView(Button(this).apply {
            text = "Готово"
            setOnClickListener { finish() }
        })

        setContentView(root)
    }
}
