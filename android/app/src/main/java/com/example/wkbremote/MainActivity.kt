package com.example.wkbremote

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private data class Key(val name: String, val b: Int, val m: Int, val color: Int)
    private val green = Color.parseColor("#2F9E5A")
    private val yellow = Color.parseColor("#F2C230")
    private val red = Color.parseColor("#D9382F")
    private val teal = Color.parseColor("#5FC7D6")
    private val white = Color.parseColor("#EEF1F4")
    private val keys = listOf(
        Key("Start",3,0x04,green), Key("Pause",2,0x04,yellow), Key("Dry Cut",1,0x04,white), Key("Stop",0,0x04,red),
        Key("Blow",2,0x08,white), Key("Follow",3,0x08,white), Key("Shutter",1,0x08,white), Key("Aiming",0,0x40,white),
        Key("PT LOC",0,0x80,white), Key("Back",3,0x10,white), Key("Forward",0,0x10,white), Key("Laser",0,0x08,yellow),
        Key("Edge Seek",2,0x10,white), Key("Fn",3,0x80,teal), Key("Frame",0,0x02,teal), Key("Step",0,0x20,teal),
        Key("Zero",0,0x01,white), Key("↑",2,0x02,white), Key("Z↑",1,0x10,teal), Key("Fast",2,0x01,yellow),
        Key("←",3,0x02,white), Key("↓",3,0x01,white), Key("→",1,0x02,white), Key("Z↓",1,0x01,teal)
    )
    private val ACTION = "com.example.wkbremote.USB_PERMISSION"
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val pressed = HashSet<Int>()
    private var port: UsbSerialPort? = null
    private lateinit var status: TextView
    private val beat = object : Runnable {
        override fun run() { if (pressed.isNotEmpty()) { send(); handler.postDelayed(this,100) } }
    }
    private val rcv = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { connect() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val M = ViewGroup.LayoutParams.MATCH_PARENT
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#15171C"))
            setPadding(16,48,16,16)
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f
            text = "غير متصل (المس هنا للاتصال)"
            setPadding(8,8,8,16); setOnClickListener { connect() }
        }
        root.addView(status)
        for (r in 0 until 6) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            root.addView(row, LinearLayout.LayoutParams(M,0,1f))
            for (c in 0 until 4) {
                val i = r*4+c; val k = keys[i]
                val btn = Button(this).apply {
                    text=k.name; isAllCaps=false; setBackgroundColor(k.color)
                    setTextColor(if (k.color==red || k.color==green) Color.WHITE else Color.BLACK)
                    setOnTouchListener { v,e -> touch(i,v,e); true }
                }
                row.addView(btn,LinearLayout.LayoutParams(0,M,1f).apply { setMargins(4,4,4,4) })
            }
        }
        setContentView(root)
        val f = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT>=33) registerReceiver(rcv,f,Context.RECEIVER_NOT_EXPORTED) else registerReceiver(rcv,f)
        connect()
    }
    private fun touch(i:Int,v:View,e:MotionEvent) {
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { v.alpha=0.6f; pressed.add(i); send(); handler.removeCallbacks(beat); handler.postDelayed(beat,100) }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> { v.alpha=1f; pressed.remove(i); send() }
        }
    }
    private fun send() {
        val s=IntArray(4)
        for(i in pressed) { val k=keys[i]; s[k.b]=s[k.b] or k.m }
        val line=String.format("S:%02x%02x%02x%02x\n",s[0],s[1],s[2],s[3])
        val p=port ?: return
        io.execute { try { p.write(line.toByteArray(),100) } catch(e:Exception) {
            port=null; handler.post { status.text="انقطع الاتصال (المس هنا لإعادة الاتصال)" }
        } }
    }
    private fun connect() {
        val um=getSystemService(Context.USB_SERVICE) as UsbManager
        val drv=UsbSerialProber.getDefaultProber().findAllDrivers(um).firstOrNull()
        if(drv==null) { status.text="لا يوجد محوّل USB-Serial (المس لإعادة المحاولة)"; return }
        if(!um.hasPermission(drv.device)) {
            val fl=if(Build.VERSION.SDK_INT>=31) PendingIntent.FLAG_MUTABLE else 0
            val pi=PendingIntent.getBroadcast(this,0,Intent(ACTION).setPackage(packageName),fl)
            um.requestPermission(drv.device,pi); return
        }
        val conn=um.openDevice(drv.device)
        if(conn==null) { status.text="فشل فتح الجهاز"; return }
        try {
            val p=drv.ports[0]; p.open(conn)
            p.setParameters(115200,8,UsbSerialPort.STOPBITS_1,UsbSerialPort.PARITY_NONE)
            try { p.setDTR(false); p.setRTS(false) } catch(ex:Exception) {}
            port=p; status.text="متصل ✓"
        } catch(e:Exception) { status.text="خطأ: ${e.message}" }
    }
    override fun onPause() { super.onPause(); pressed.clear(); send() }
    override fun onDestroy() {
        super.onDestroy(); unregisterReceiver(rcv)
        try { port?.close() } catch(ex:Exception) {}
        io.shutdown()
    }
}
