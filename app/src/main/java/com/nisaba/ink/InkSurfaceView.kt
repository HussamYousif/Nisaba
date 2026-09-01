package com.nisaba.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.graphics.Path
import android.graphics.PathMeasure
import android.os.Handler
import android.os.Looper
import com.nisaba.epd.Epd
import com.nisaba.epd.Oec
import com.nisaba.magic.MagicTheme
import com.nisaba.magic.SummonInk
import com.nisaba.notebook.model.NotebookPage
import com.nisaba.notebook.model.Stroke
import com.nisaba.render.AnswerInk
import kotlin.math.max
import kotlin.math.min

/**
 * The writing surface (docs/03 §2, docs/04 §3–5).
 *
 * - Stylus (EMR) draws with pressure; fingers never draw.
 * - Hover events gate palm rejection while the pen is in range.
 * - Pen side button (BTN_STYLUS/BTN_STYLUS2) acts as a held eraser.
 * - Ink renders in page space through [PageTransform]; dirty-rect invalidation
 *   keeps per-move redraws minimal while writing.
 */
open class InkSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var page: NotebookPage? = null
        set(value) {
            field = value
            transform.pageWidth = value?.width ?: 2480
            transform.pageHeight = value?.height ?: 1860
            transform.setViewport(width, height)
            requestLayout() // page aspect may have changed
            rebuildBgLayer()
            invalidate()
        }

    var inkColor: Int = MagicTheme.INK
    var eraserActive: Boolean = false

    /** Decoded page background (PDF page); null = blank parchment. */
    var background: android.graphics.Bitmap? = null
        set(value) {
            field = value
            rebuildBgLayer()
            invalidate()
        }

    /** Fired for every new stroke point (summon idle reset / autosave, M3). */
    var onStrokePoint: (() -> Unit)? = null

    /** Fired on pen-up with the finished stroke (tool == "eraser" for erases). */
    var onStrokeComplete: ((Stroke) -> Unit)? = null

    /** Fired when the pen side-button state changes. */
    var onEraserButton: ((Boolean) -> Unit)? = null

    private val transform = PageTransform()
    private val current = StrokeBuilder()
    private var penHovering = false
    private var drawing = false
    private var eraserButtonHeld = false

    // Invalidation throttling: coalesce dirty rects and flush at ~40 Hz so a
    // slow e-ink refresh cycle never blocks input (docs/04 §3).
    private val flushHandler = Handler(Looper.getMainLooper())
    private var dirtyLeft = Int.MAX_VALUE
    private var dirtyTop = Int.MAX_VALUE
    private var dirtyRight = Int.MIN_VALUE
    private var dirtyBottom = Int.MIN_VALUE
    private var flushScheduled = false
    private val flushRunnable = Runnable {
        flushScheduled = false
        if (dirtyRight >= dirtyLeft) {
            invalidate(dirtyLeft, dirtyTop, dirtyRight, dirtyBottom)
            resetDirty()
        }
    }
    // Debounced EPD clean pass after the pen lifts (ghosting removal).
    private val cleanRunnable = Runnable {
        Epd.disableFastMode(this)
        Epd.cleanRefresh(this)
    }

    // The summon's organic ink narrative (docs/09 §3): a thread of ink flows
    // from the last stroke's tail to the answer locus, gathers into a blot,
    // and is gently fed by travelling ink beads while the scribe thinks.
    private enum class InkPhase { IDLE, THREAD, FLOW, SETTLED, RECEDE }

    private var inkPhase = InkPhase.IDLE
    private var threadPath: Path? = null
    private var threadLen = 0f
    private var threadProgress = 0f
    private var beadProgress = 0f
    private var rippleT = 0f
    private var flowPause = 0
    private var blotX = 0f
    private var blotY = 0f
    private val inkHandler = Handler(Looper.getMainLooper())
    private val inkTick: Runnable = object : Runnable {
        override fun run() {
            advanceInk()
            if (inkPhase != InkPhase.IDLE && inkPhase != InkPhase.SETTLED) {
                inkHandler.postDelayed(this, INK_STEP_MS)
            }
            invalidate()
        }
    }

    val isSummonInkActive: Boolean get() = inkPhase != InkPhase.IDLE

    /**
     * The ink reaches out: a thread draws from the last stroke's tail to the
     * answer locus ([blotX],[blotY]), then beads flow while waiting.
     */
    fun beginSummonInk(tailX: Float, tailY: Float, blotX: Float, blotY: Float) {
        this.blotX = blotX
        this.blotY = blotY
        threadPath = SummonInk.buildThread(tailX, tailY, blotX, blotY)
        threadLen = SummonInk.threadLength(threadPath!!)
        threadProgress = 0f
        beadProgress = 0f
        rippleT = 0f
        flowPause = 0
        inkPhase = InkPhase.THREAD
        inkHandler.removeCallbacks(inkTick)
        inkHandler.postDelayed(inkTick, INK_STEP_MS)
        invalidate()
    }

    /**
     * The answer arrived (or the summon ended). [keep] true — the thread and
     * blot stay while the answer ink reveals over them; false — the thread
     * draws back into the stroke and everything clears.
     */
    fun endSummonInk(keep: Boolean) {
        if (inkPhase == InkPhase.IDLE) return
        if (keep) {
            inkPhase = InkPhase.SETTLED
            inkHandler.removeCallbacks(inkTick)
            threadProgress = 1f
            beadProgress = 0f
            invalidate()
        } else {
            inkPhase = InkPhase.RECEDE
            invalidate()
        }
    }

    private fun advanceInk() {
        when (inkPhase) {
            InkPhase.THREAD -> {
                threadProgress += 1f / THREAD_STEPS
                if (threadProgress >= 1f) {
                    threadProgress = 1f
                    inkPhase = InkPhase.FLOW
                    flowPause = FLOW_PAUSE_STEPS
                }
            }
            InkPhase.FLOW -> {
                if (flowPause > 0) {
                    flowPause--
                } else {
                    beadProgress += 1f / BEAD_STEPS
                    if (beadProgress >= 1f) {
                        beadProgress = 0f
                        rippleT = 1f
                        flowPause = FLOW_PAUSE_STEPS
                    }
                }
                if (rippleT > 0f) rippleT -= RIPPLE_STEP
            }
            InkPhase.RECEDE -> {
                threadProgress -= 1f / RECEDE_STEPS
                if (threadProgress <= 0f) {
                    threadProgress = 0f
                    inkPhase = InkPhase.IDLE
                    threadPath = null
                    threadLen = 0f
                }
            }
            else -> Unit
        }
    }

    private fun drawSummonInk(canvas: Canvas) {
        if (inkPhase == InkPhase.IDLE) return
        val path = threadPath
        if (path != null && threadLen > 0f && threadProgress > 0f) {
            SummonInk.drawThread(canvas, path, threadLen, threadProgress)
            if (inkPhase == InkPhase.FLOW) {
                SummonInk.drawBead(canvas, path, threadLen, beadProgress)
            }
        }
        if (threadProgress >= 1f || inkPhase == InkPhase.SETTLED || inkPhase == InkPhase.RECEDE) {
            SummonInk.drawBlot(canvas, blotX, blotY, BLOT_RADIUS)
            if (rippleT > 0f) SummonInk.drawRipple(canvas, blotX, blotY, BLOT_RADIUS, rippleT)
        }
    }

    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val bgPaint = Paint().apply {
        isFilterBitmap = true // smooth 200dpi → 300dpi upscale
    }

    // Cached static layer (parchment + rules + PDF background) at view
    // resolution: pen flushes redraw only the new ink on top, so the render
    // thread frees up earlier and fresh frames reach the e-ink panel sooner
    // (docs/04 §3). Strokes and answer ink stay vector — cheap to redraw.
    private var bgLayer: Bitmap? = null

    private fun rebuildBgLayer() {
        bgLayer?.recycle()
        bgLayer = null
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val p = page ?: return
        val layer = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val c = Canvas(layer)
        c.drawColor(MagicTheme.PARCHMENT)
        c.save()
        c.translate(transform.offsetX, transform.offsetY)
        c.scale(transform.scale, transform.scale)
        background?.let { bmp ->
            c.drawBitmap(bmp, null, RectF(0f, 0f, p.width.toFloat(), p.height.toFloat()), bgPaint)
        }
        if (background == null) drawRules(c, p)
        c.restore()
        bgLayer = layer
    }

    // The scribe's answer, drawn as ink on the canvas (docs/08 §1).
    private var answerLayouts: List<AnswerInk.Layout> = emptyList()
    private var answerDisplayBottom = 0f // page-space bottom incl. answers
    private var answerTicks = 0
    private var answerTotalTicks = 0
    private var answerAnimating = false
    private val answerHandler = Handler(Looper.getMainLooper())
    private val answerTick = Runnable { advanceAnswer() }

    /** Replaces the answer ink; [animateLast] writes only the newest in. */
    fun setAnswers(layouts: List<AnswerInk.Layout>, displayBottom: Float, animateLast: Boolean) {
        answerLayouts = layouts
        answerDisplayBottom = displayBottom
        if (animateLast && layouts.isNotEmpty()) {
            answerTicks = 0
            answerTotalTicks = layouts.last().totalTicks
            answerAnimating = answerTotalTicks > 0
            if (answerAnimating) {
                answerHandler.removeCallbacks(answerTick)
                answerHandler.postDelayed(answerTick, ANSWER_STEP_MS)
            }
        } else {
            answerAnimating = false
            answerHandler.removeCallbacks(answerTick)
            answerTicks = Int.MAX_VALUE
        }
        requestLayout() // the page may need to grow
        invalidate()
    }

    fun skipAnswerReveal() {
        answerAnimating = false
        answerHandler.removeCallbacks(answerTick)
        answerTicks = Int.MAX_VALUE
        invalidate()
    }

    /** Test/UI accessor: how many answer ink items are on the canvas. */
    val answerItemCount: Int get() = answerLayouts.sumOf { it.itemCount }
    val answerPathCount: Int
        get() = answerLayouts.sumOf { l -> l.items.count { it is AnswerInk.Layout.Item.Path } }
    val answerRuleCount: Int
        get() = answerLayouts.sumOf { l -> l.items.count { it is AnswerInk.Layout.Item.Rule } }

    private fun advanceAnswer() {
        if (!answerAnimating) return
        answerTicks++
        if (answerTicks >= answerTotalTicks) {
            answerAnimating = false
            answerTicks = Int.MAX_VALUE
        } else {
            answerHandler.postDelayed(answerTick, ANSWER_STEP_MS)
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (context as? android.app.Activity)?.let { Epd.disableFullScreenUpdate(it) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        transform.setViewportFitWidth(w)
        rebuildBgLayer()
    }

    /** Fit-width: the page plus any answer ink below it. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val p = page
        if (p != null && w > 0) {
            val scale = minOf(w.toFloat() / p.width, PageTransform.MAX_SCALE)
            val display = max(p.height.toFloat(), answerDisplayBottom)
            setMeasuredDimension(w, (display * scale).toInt())
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(MagicTheme.PARCHMENT)
        val p = page ?: return
        val layer = bgLayer
        if (layer != null) {
            canvas.drawBitmap(layer, 0f, 0f, null)
        } else {
            canvas.save()
            canvas.translate(transform.offsetX, transform.offsetY)
            canvas.scale(transform.scale, transform.scale)
            background?.let { bmp ->
                canvas.drawBitmap(bmp, null, android.graphics.RectF(0f, 0f, p.width.toFloat(), p.height.toFloat()), bgPaint)
            }
            if (background == null) drawRules(canvas, p)
            canvas.restore()
        }
        canvas.save()
        canvas.translate(transform.offsetX, transform.offsetY)
        canvas.scale(transform.scale, transform.scale)
        inkPaint.color = inkColor
        for (s in p.strokes) StrokeRenderer.draw(canvas, s, inkPaint)
        current.buildPreview()?.let { StrokeRenderer.draw(canvas, it, inkPaint) }
        drawSummonInk(canvas)
        drawAnswerInk(canvas)
        canvas.restore()
    }

    /** Draws the answer ink; only the newest layout animates (docs/08 §5). */
    private fun drawAnswerInk(canvas: Canvas) {
        if (answerLayouts.isEmpty()) return
        val animatingLayout = answerAnimating
        var cumTicks = 0
        for ((li, layout) in answerLayouts.withIndex()) {
            val isLast = li == answerLayouts.lastIndex
            val layoutTicks = if (isLast) layout.totalTicks else Int.MAX_VALUE
            var itemStart = cumTicks
            for (item in layout.items) {
                val ticks = item.revealTicks
                val local = if (!isLast || !animatingLayout || answerTicks >= layoutTicks) {
                    1f
                } else {
                    ((answerTicks - itemStart).toFloat() / ticks).coerceIn(0f, 1f)
                }
                when (item) {
                    is AnswerInk.Layout.Item.Line -> {
                        if (local >= 1f) canvas.drawText(item.text, item.x, item.baselineY, item.paint)
                    }
                    is AnswerInk.Layout.Item.Rule -> {
                        if (local >= 1f) canvas.drawLine(item.x1, item.y1, item.x2, item.y2, item.paint)
                    }
                    is AnswerInk.Layout.Item.Path -> {
                        if (local <= 0f) { /* nothing yet */ } else if (item.fill) {
                            if (local >= 1f) canvas.drawPath(item.path, item.paint)
                        } else {
                            val measure = PathMeasure(item.path, false)
                            if (measure.length > 0f) {
                                val seg = android.graphics.Path()
                                measure.getSegment(0f, measure.length * local, seg, true)
                                if (!seg.isEmpty) canvas.drawPath(seg, item.paint)
                            }
                        }
                    }
                }
                itemStart += ticks
            }
            cumTicks += layoutTicks
        }
    }

    private fun drawRules(canvas: Canvas, p: NotebookPage) {
        val rule = Paint().apply {
            color = MagicTheme.INK
            alpha = MagicTheme.RULE_ALPHA
            strokeWidth = 1.2f
        }
        var y = MagicTheme.RULE_SPACING
        while (y < p.height) {
            canvas.drawLine(0f, y, p.width.toFloat(), y, rule)
            y += MagicTheme.RULE_SPACING
        }
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val isStylus = isStylusEvent(event)

        // Pen side-button state must be tracked on EVERY event — the button is
        // pressed while drawing, so move events matter as much as hovers.
        val btn = event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY) ||
            event.isButtonPressed(MotionEvent.BUTTON_STYLUS_SECONDARY)
        if (btn != eraserButtonHeld) {
            eraserButtonHeld = btn
            if (btn && drawing) current.setErasing()
            onEraserButton?.invoke(btn)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE -> penHovering = true

            MotionEvent.ACTION_HOVER_EXIT -> penHovering = false

            MotionEvent.ACTION_DOWN -> {
                // Palm rejection: fingers never draw; while the pen hovers,
                // even finger touches are ignored outright.
                if (!isStylus) return false
                if (page == null) return false
                val erasing = eraserActive || eraserButtonHeld
                current.start(
                    transform.toPageX(event.x),
                    transform.toPageY(event.y),
                    event.pressure,
                    event.eventTime,
                    erasing,
                )
                drawing = true
                flushHandler.removeCallbacks(flushRunnable)
                flushScheduled = false
                Epd.enableFastMode(this)
                Oec.setRefreshMode(Oec.MODE_FAST)
                onStrokePoint?.invoke()
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!drawing) return false
                if (isStylus) {
                    val pageX = transform.toPageX(event.x)
                    val pageY = transform.toPageY(event.y)
                    current.append(pageX, pageY, event.pressure, event.eventTime)
                    onStrokePoint?.invoke()
                    accumulateDirty(pageX, pageY)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (drawing) {
                    finishStroke()
                    return true
                }
                return false
            }

            MotionEvent.ACTION_CANCEL -> {
                if (drawing) {
                    drawing = false
                    current.cancel()
                    flushNow()
                    Oec.setRefreshMode(Oec.MODE_NORMAL)
                    invalidate()
                }
            }
        }
        return true
    }

    /** Open seam for tests; on-device this resolves the EMR tool type. */
    protected open fun isStylusEvent(event: MotionEvent): Boolean =
        event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
            event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER

    private fun finishStroke() {
        drawing = false
        flushNow()
        Oec.setRefreshMode(Oec.MODE_NORMAL)
        val stroke = current.build() ?: return
        onStrokeComplete?.invoke(stroke)
        invalidate()
        // Ghosting clean pass, debounced — repeated quick strokes share one.
        flushHandler.removeCallbacks(cleanRunnable)
        flushHandler.postDelayed(cleanRunnable, CLEAN_PASS_MS)
    }

    /** Coalesces the new ink segment into the pending dirty rect (docs/04 §3). */
    private fun accumulateDirty(pageX: Float, pageY: Float) {
        val vx = transform.toViewX(pageX)
        val vy = transform.toViewY(pageY)
        val pad = (24 * transform.scale).toInt() + 8
        dirtyLeft = min(dirtyLeft, vx.toInt() - pad)
        dirtyTop = min(dirtyTop, vy.toInt() - pad)
        dirtyRight = max(dirtyRight, vx.toInt() + pad)
        dirtyBottom = max(dirtyBottom, vy.toInt() + pad)
        if (!flushScheduled) {
            flushScheduled = true
            flushHandler.postDelayed(flushRunnable, FLUSH_MS)
        }
    }

    private fun resetDirty() {
        dirtyLeft = Int.MAX_VALUE
        dirtyTop = Int.MAX_VALUE
        dirtyRight = Int.MIN_VALUE
        dirtyBottom = Int.MIN_VALUE
    }

    private fun flushNow() {
        flushHandler.removeCallbacks(flushRunnable)
        if (dirtyRight >= dirtyLeft) {
            invalidate(dirtyLeft, dirtyTop, dirtyRight, dirtyBottom)
        }
        resetDirty()
        flushScheduled = false
    }

    /** True when the stylus is currently drawing a stroke. */
    val isDrawing: Boolean get() = drawing

    companion object {
        private const val FLUSH_MS = 16L
        private const val CLEAN_PASS_MS = 400L
        private const val ANSWER_STEP_MS = 110L
        private const val INK_STEP_MS = 160L
        private const val THREAD_STEPS = 14
        private const val BEAD_STEPS = 8
        private const val FLOW_PAUSE_STEPS = 16
        private const val RECEDE_STEPS = 6
        private const val RIPPLE_STEP = 0.18f
        private const val BLOT_RADIUS = 24f
    }
}
