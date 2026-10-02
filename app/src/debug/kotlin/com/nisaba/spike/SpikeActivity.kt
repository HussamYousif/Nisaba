package com.nisaba.spike

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import kotlin.math.PI
import kotlin.math.sin

/** M0 spike harness (S1/S2/S6). Debug only; deleted at M0.12. */
class SpikeActivity : Activity() {
    private val logTag = "NisabaSpike"
    private lateinit var surface: SurfaceView
    private lateinit var status: TextView
    private lateinit var bar: LinearLayout
    private var helper: TouchHelper? = null
    private lateinit var bmp: Bitmap
    private lateinit var bmpCanvas: Canvas
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 3f }
    private val lapis = Paint(ink).apply { color = 0xFF27407A.toInt(); strokeWidth = 4f }

    // stats
    private var strokes = 0
    private var lastStrokePts = 0
    private var moveCount = 0
    private var minP = 1e9f
    private var maxP = 0f
    private var lastDownAt = 0L
    private var erasing = 0
    private var commitStrokes = false
    private var animating = false
    @Volatile private var interrupted = false
    private var animStrategy = 1
    private var lastMsg = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val root = FrameLayout(this).apply { setBackgroundColor(0xFFF6F2EA.toInt()) }
        surface = object : SurfaceView(this) {
            override fun onTouchEvent(e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    val lat = SystemClock.uptimeMillis() - e.eventTime
                    Log.i(logTag, "onTouchEvent DOWN tool=${e.getToolType(0)} btn=${e.buttonState} dispatchLatency=${lat}ms animating=$animating")
                    if (animating && e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                        interrupted = true
                        Log.i(logTag, "INTERRUPT via onTouchEvent latency=${lat}ms")
                    }
                }
                return true
            }
        }
        root.addView(surface, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 200 })
        status = TextView(this).apply { textSize = 13f; setTextColor(Color.BLACK); setPadding(12, 4, 12, 4) }
        bar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFFDDD8CC.toInt()) }
        val row1 = row(); val row2 = row()
        row1.add("Clear") { clear() }
        row1.add("RawOn") { helper?.setRawDrawingEnabled(true); helper?.setRawDrawingRenderEnabled(true); log("raw ON") }
        row1.add("RawOff") { helper?.setRawDrawingEnabled(false); log("raw OFF") }
        row1.add("RndOff") { helper?.setRawDrawingRenderEnabled(false); log("render OFF") }
        row1.add("Commit:off") { b -> commitStrokes = !commitStrokes; b.text = "Commit:" + if (commitStrokes) "on" else "off" }
        row2.add("A1 toggle") { animate(1) }
        row2.add("A2 rndOff") { animate(2) }
        row2.add("A3 keepRaw") { animate(3) }
        row2.add("SideErase") { helper?.enableSideBtnErase(true); log("sideBtnErase on") }
        row2.add("Stats") { logStats() }
        bar.addView(row1); bar.addView(row2); bar.addView(status)
        root.addView(bar, FrameLayout.LayoutParams(-1, 200, Gravity.TOP))
        setContentView(root)
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {}
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) { onSurface(w, hh) }
            override fun surfaceDestroyed(h: SurfaceHolder) {}
        })
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun LinearLayout.add(label: String, click: (Button) -> Unit) {
        addView(Button(this@SpikeActivity).apply { text = label; textSize = 11f; isAllCaps = false; setOnClickListener { click(this) } }, LinearLayout.LayoutParams(0, 92, 1f))
    }

    private fun onSurface(w: Int, h: Int) {
        if (!::bmp.isInitialized || bmp.width != w || bmp.height != h) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); bmpCanvas = Canvas(bmp); bmpCanvas.drawColor(0xFFF6F2EA.toInt())
        }
        present(null)
        val callback = object : RawInputCallback() {
            override fun onBeginRawDrawing(b: Boolean, p: TouchPoint) { lastDownAt = SystemClock.uptimeMillis(); moveCount = 0; Log.i(logTag, "beginDraw b=$b x=${p.x} y=${p.y} p=${p.pressure} size=${p.size} ts=${p.timestamp} now=${System.currentTimeMillis()}"); if (animating) { interrupted = true; Log.i(logTag, "INTERRUPT via rawBegin") } }
            override fun onEndRawDrawing(b: Boolean, p: TouchPoint) { Log.i(logTag, "endDraw moves=$moveCount") }
            override fun onRawDrawingTouchPointMoveReceived(p: TouchPoint) { moveCount++; if (p.pressure < minP) minP = p.pressure; if (p.pressure > maxP) maxP = p.pressure }
            override fun onRawDrawingTouchPointListReceived(l: TouchPointList) {
                strokes++; lastStrokePts = l.size()
                val f = l.get(0); val la = l.get(l.size() - 1)
                Log.i(logTag, "strokeList n=${l.size()} moves=$moveCount dur=${la.timestamp - f.timestamp}ms tsFirst=${f.timestamp} pMax=$maxP")
                if (commitStrokes) commit(l)
                runOnUiThread { showStats() }
            }
            override fun onBeginRawErasing(b: Boolean, p: TouchPoint) { erasing++; Log.i(logTag, "beginErase b=$b") }
            override fun onEndRawErasing(b: Boolean, p: TouchPoint) { Log.i(logTag, "endErase") }
            override fun onRawErasingTouchPointMoveReceived(p: TouchPoint) {}
            override fun onRawErasingTouchPointListReceived(l: TouchPointList) { Log.i(logTag, "eraseList n=${l.size()}"); runOnUiThread { showStats() } }
            override fun onPenActive(p: TouchPoint) { Log.i(logTag, "penActive") }
            override fun onPenUpRefresh(r: RectF) { Log.i(logTag, "penUpRefresh $r") }
        }
        helper?.closeRawDrawing()
        val hh = TouchHelper.create(surface, callback)
        helper = hh
        val limit = Rect(0, 0, w, h)
        val loc = IntArray(2); surface.getLocationOnScreen(loc)
        Log.i(logTag, "surface ${w}x$h at ${loc[0]},${loc[1]}")
        hh.setStrokeWidth(3f).setStrokeStyle(TouchHelper.STROKE_STYLE_FOUNTAIN).setLimitRect(limit, emptyList()).openRawDrawing()
        hh.setStrokeColor(Color.BLACK)
        hh.setRawDrawingEnabled(true)
        log("raw drawing open ${w}x$h")
    }

    private fun commit(l: TouchPointList) {
        val path = android.graphics.Path()
        for (i in 0 until l.size()) { val p = l.get(i); if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
        bmpCanvas.drawPath(path, lapis)
        present(null)
    }

    private fun present(dirty: Rect?) {
        val c = surface.holder.lockCanvas(dirty) ?: return
        try { c.drawBitmap(bmp, 0f, 0f, null) } finally { surface.holder.unlockCanvasAndPost(c) }
    }

    private fun clear() { bmpCanvas.drawColor(0xFFF6F2EA.toInt()); present(null); log("cleared") }

    private fun animate(strategy: Int) {
        if (animating) return
        animStrategy = strategy; animating = true; interrupted = false
        val h = helper
        when (strategy) { 1 -> h?.setRawDrawingEnabled(false); 2 -> h?.setRawDrawingRenderEnabled(false) }
        log("anim strategy $strategy started")
        Thread {
            val w = bmp.width; val hgt = bmp.height
            val frames = 100; val t0 = SystemClock.uptimeMillis(); var last = Pair(60f, hgt * 0.4f)
            var done = 0; val frameMs = ArrayList<Long>()
            for (f in 1..frames) {
                if (interrupted) break
                val fs = SystemClock.uptimeMillis()
                val x = 60f + (w - 120f) * f / frames
                val y = hgt * 0.4f + 120f * sin(f * 2 * PI / 25).toFloat()
                bmpCanvas.drawLine(last.first, last.second, x, y, lapis)
                val d = Rect(minOf(last.first, x).toInt() - 8, minOf(last.second, y).toInt() - 8, maxOf(last.first, x).toInt() + 8, maxOf(last.second, y).toInt() + 8)
                present(d)
                last = Pair(x, y); done++
                frameMs.add(SystemClock.uptimeMillis() - fs)
                val wait = 50 - (SystemClock.uptimeMillis() - fs); if (wait > 0) Thread.sleep(wait)
            }
            val el = SystemClock.uptimeMillis() - t0
            Log.i(logTag, "anim done frames=$done/$frames elapsed=${el}ms fps=${done * 1000f / el} avgFrameWork=${frameMs.average()}ms max=${frameMs.maxOrNull()}ms interrupted=$interrupted")
            runOnUiThread {
                helper?.setRawDrawingEnabled(true); helper?.setRawDrawingRenderEnabled(true)
                animating = false; lastMsg = "anim$strategy fps=${"%.1f".format(done * 1000f / el)} int=$interrupted"; showStats()
            }
        }.start()
    }

    private fun log(m: String) { lastMsg = m; Log.i(logTag, m); showStats() }
    private fun logStats() { Log.i(logTag, "STATS strokes=$strokes lastPts=$lastStrokePts pressure=[$minP,$maxP] erase=$erasing"); showStats() }
    private fun showStats() { status.text = "strokes=$strokes lastPts=$lastStrokePts p=[${"%.2f".format(minP)},${"%.0f".format(maxP)}] erase=$erasing | $lastMsg" }

    override fun onPause() { helper?.closeRawDrawing(); helper = null; super.onPause() }
    override fun onResume() { super.onResume(); if (::bmp.isInitialized) onSurface(bmp.width, bmp.height) }
}
