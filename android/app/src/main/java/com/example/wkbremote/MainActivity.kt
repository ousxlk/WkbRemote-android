package com.example.wkbremote

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.util.concurrent.Executors

class MainActivity : Activity() {

    // name, byte index (0..3 = B2..B5; -1 = no code recorded), mask, color
    private data class Key(val name: String, val b: Int, val m: Int, val color: Int)

    private val green = Color.parseColor("#2F9E5A")
    private val yellow = Color.parseColor("#F2C230")
    private val red = Color.parseColor("#D9382F")
    private val teal = Color.parseColor("#5FC7D6")
    private val white = Color.parseColor("#EEF1F4")
    private val ink = Color.parseColor("#1A1C20")
    private val gray = Color.parseColor("#CFD4DC")
    private val orange = Color.parseColor("#F5A623")
    private val orangeDark = Color.parseColor("#D98A0C")

    // نفس تخطيط الريموت الأصلي (8 صفوف × 4). K1-K6 ودوران W بلا رمز مسجّل بعد.
    private val keys = listOf(
        Key("Start", 3, 0x04, green), Key("Pause", 2, 0x04, yellow), Key("Dry\nCut", 1, 0x04, white), Key("Stop", 0, 0x04, red),
        Key("Blow", 2, 0x08, white), Key("Follow", 3, 0x08, white), Key("Shutter", 1, 0x08, white), Key("Aiming", 0, 0x40, white),
        Key("PT\nLOC", 0, 0x80, white), Key("Back\n←", 3, 0x10, white), Key("Forward\n→", 0, 0x10, white), Key("Laser", 0, 0x08, yellow),
        Key("K1", -1, 0, teal), Key("K2", -1, 0, teal), Key("K3", -1, 0, teal), Key("K4", -1, 0, teal),
        Key("Edge\nSeek", 2, 0x10, white), Key("K5", -1, 0, teal), Key("K6", -1, 0, teal), Key("Fn", 3, 0x80, teal),
        Key("↻ W", -1, 0, teal), Key("↑", 2, 0x02, white), Key("Z ↑", 1, 0x10, teal), Key("Frame", 0, 0x02, teal),
        Key("←", 3, 0x02, white), Key("Zero", 0, 0x01, white), Key("→", 1, 0x02, white), Key("Fast", 2, 0x01, yellow),
        Key("↺ W", -1, 0, teal), Key("↓", 3, 0x01, white), Key("Z ↓", 1, 0x01, teal), Key("Step", 0, 0x20, teal)
    )

    private val ACTION = "com.example.wkbremote.USB_PERMISSION"
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val pressed = LinkedHashSet<Int>()
    private var port: UsbSerialPort? = null
    private var idleMsg = "المس «غير متصل» للاتصال"

    private lateinit var pillText: TextView
    private lateinit var dotBg: GradientDrawable
    private lateinit var ledText: TextView
    private lateinit var ledDotBg: GradientDrawable

    private val beat = object : Runnable {
        override fun run() {
            if (pressed.isNotEmpty()) {
                send()
                handler.postDelayed(this, 100)
            }
        }
    }

    private val rcv = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { connect() }
    }

    // ---------- أدوات الرسم ----------
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(c: Int, r: Int): GradientDrawable =
        GradientDrawable().apply { cornerRadius = dp(r).toFloat(); setColor(c) }

    private fun oval(c: Int): GradientDrawable =
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }

    private fun darker(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[2] = hsv[2] * 0.85f
        return Color.HSVToColor(hsv)
    }

    private fun face(c: Int, top: Int, bot: Int): Drawable {
        val r = dp(11).toFloat()
        val sh = GradientDrawable().apply { cornerRadius = r; setColor(0x59000000) }
        val fc = GradientDrawable().apply { cornerRadius = r; setColor(c) }
        return LayerDrawable(arrayOf<Drawable>(sh, fc)).apply { setLayerInset(1, 0, top, 0, bot) }
    }

    private fun keyBg(c: Int): Drawable {
        val s = StateListDrawable()
        s.addState(intArrayOf(android.R.attr.state_pressed), face(darker(c), dp(3), 0))
        s.addState(intArrayOf(), face(c, 0, dp(3)))
        return s
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.parseColor("#15171C")
        window.navigationBarColor = Color.parseColor("#15171C")
        val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(26), dp(14), dp(24))
        }

        // ---- الترويسة ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = "ريموت WKB-V6"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        })
        titles.addView(TextView(this).apply {
            text = "Cypcut FSCUT · واجهة تحكم"
            setTextColor(Color.parseColor("#8B919C"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        header.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))

        dotBg = oval(red)
        val dot = View(this).apply { background = dotBg }
        pillText = TextView(this).apply {
            text = "غير متصل"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.parseColor("#2A2D34"), 20)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { connect() }
        }
        pill.addView(dot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(7) })
        pill.addView(pillText)
        header.addView(pill)
        col.addView(header, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(14) })

        // ---- المقبض ----
        val handleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        handleRow.addView(View(this), LinearLayout.LayoutParams(0, dp(36), 27f))
        val handle = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(9), orange)
                cornerRadius = dp(22).toFloat()
            }
        }
        handleRow.addView(handle, LinearLayout.LayoutParams(0, dp(36), 46f))
        handleRow.addView(View(this), LinearLayout.LayoutParams(0, dp(36), 27f))
        col.addView(handleRow, LinearLayout.LayoutParams(MATCH, WRAP))

        // ---- الهيكل البرتقالي ----
        val caseBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(22), dp(14), dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(orange, orangeDark))
                .apply { cornerRadius = dp(34).toFloat() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.parseColor("#2A2D34"), 22)
            setPadding(dp(8), dp(12), dp(8), dp(8))
        }

        // ---- الشاشة الصغيرة ----
        val led = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.parseColor("#101216"), 10)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        ledText = TextView(this).apply {
            text = idleMsg
            setTextColor(gray)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        led.addView(ledText, LinearLayout.LayoutParams(0, WRAP, 1f))
        ledDotBg = oval(Color.parseColor("#444444"))
        led.addView(View(this).apply { background = ledDotBg }, LinearLayout.LayoutParams(dp(8), dp(8)))
        panel.addView(led, LinearLayout.LayoutParams(MATCH, dp(40)).apply {
            leftMargin = dp(4); rightMargin = dp(4); bottomMargin = dp(8)
        })

        // ---- الأزرار ----
        for (r in 0 until 8) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 4) {
                val i = r * 4 + c
                val k = keys[i]
                val btn = Button(this).apply {
                    text = k.name
                    isAllCaps = false
                    background = keyBg(k.color)
                    setTextColor(if (k.color == red || k.color == green) Color.WHITE else ink)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setPadding(0, 0, 0, 0)
                    includeFontPadding = false
                    minHeight = 0
                    minimumHeight = 0
                    stateListAnimator = null
                    gravity = Gravity.CENTER
                    setOnTouchListener { v, e -> touch(i, v, e); true }
                }

                // Give every key the same full-size cell so multiline labels
                // (Dry Cut, PT LOC, Edge Seek, etc.) never shift the button edges.
                val cell = FrameLayout(this)
                cell.addView(btn, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                })
                row.addView(cell, LinearLayout.LayoutParams(0, dp(62), 1f))
            }
            panel.addView(row)
        }
        caseBox.addView(panel)
        col.addView(caseBox, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = -dp(22) })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#15171C"))
            isFillViewport = true
        }
        scroll.addView(col)
        setContentView(scroll)

        val f = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rcv, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(rcv, f)
        connect()
    }

    private fun touch(i: Int, v: View, e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.parent.requestDisallowInterceptTouchEvent(true)
                v.isPressed = true
                pressed.add(i)
                showKey()
                send()
                handler.removeCallbacks(beat)
                handler.postDelayed(beat, 100)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                pressed.remove(i)
                showKey()
                send()
            }
        }
    }

    private fun showKey() {
        val last = pressed.lastOrNull()
        if (last != null) {
            ledText.text = keys[last].name.replace("\n", " ")
            ledText.setTextColor(yellow)
            ledDotBg.setColor(green)
        } else {
            ledText.text = idleMsg
            ledText.setTextColor(gray)
            ledDotBg.setColor(Color.parseColor("#444444"))
        }
    }

    private fun setConn(ok: Boolean, msg: String) {
        idleMsg = msg
        pillText.text = if (ok) "متصل" else "غير متصل"
        dotBg.setColor(if (ok) green else red)
        if (pressed.isEmpty()) {
            ledText.text = msg
            ledText.setTextColor(gray)
        }
    }

    // يرسل سطراً من الشكل S:AABBCCDD (قناع الأزرار B2..B5)
    private fun send() {
        val s = IntArray(4)
        for (i in pressed) {
            val k = keys[i]
            if (k.b >= 0) s[k.b] = s[k.b] or k.m
        }
        val line = String.format("S:%02x%02x%02x%02x\n", s[0], s[1], s[2], s[3])
        val p = port ?: return
        io.execute {
            try {
                p.write(line.toByteArray(), 100)
            } catch (e: Exception) {
                port = null
                handler.post { setConn(false, "انقطع الاتصال (المس الحالة لإعادة الاتصال)") }
            }
        }
    }

    private fun connect() {
        val um = getSystemService(Context.USB_SERVICE) as UsbManager
        val drv = UsbSerialProber.getDefaultProber().findAllDrivers(um).firstOrNull()
        if (drv == null) { setConn(false, "لا يوجد محوّل USB-Serial"); return }
        if (!um.hasPermission(drv.device)) {
            setConn(false, "بانتظار إذن USB…")
            val fl = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION).setPackage(packageName), fl)
            um.requestPermission(drv.device, pi)
            return
        }
        val conn = um.openDevice(drv.device)
        if (conn == null) { setConn(false, "فشل فتح الجهاز"); return }
        try {
            val p = drv.ports[0]
            p.open(conn)
            p.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            try { p.setDTR(false); p.setRTS(false) } catch (ex: Exception) {} // يمنع إعادة تشغيل اللوحة
            port = p
            setConn(true, "جاهز · Ready")
        } catch (e: Exception) {
            setConn(false, "خطأ: ${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        pressed.clear()   // أمان: تحرير كل الأزرار عند مغادرة التطبيق
        send()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(rcv)
        try { port?.close() } catch (ex: Exception) {}
        io.shutdown()
    }
}