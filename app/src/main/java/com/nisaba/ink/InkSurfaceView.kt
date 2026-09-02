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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The writing surface (docs/03 §2, docs/04 §3–5).
 *
 * - Stylus (EMR) draws with pressure; fingers never draw. Palm rejection is
 *   tool-type gating: non-stylus ACTION_DOWN is ignored outright (audit P4-3
 *   — the old hover-based gate was unreachable dead code).
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
            // Fit-width everywhere (audit P0-3): contain-fit offset the page
            // vertically, so ink and touch coordinates shifted on page turns.
            transform.setViewportFitWidth(width)
            // The summon narrative and the answer ink are page-local — a new
            // page must not inherit the old page's thread/blot or answer
            // layouts (audit P0-7, P0-2).
            inkHandler.removeCallbacks(inkTick)
            clearSummonInk()
            setAnswers(emptyList(), 0f, animateLast = false)
            requestLayout() // page aspect may have changed
            rebuildBgLayer()
            invalidate()
        }

    var inkColor: Int = MagicTheme.INK
    var eraserActive: Boolean = false

    /** Decoded page background (PDF page); null = blank parchment. */
    var pageBackground: android.graphics.Bitmap? = null
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

    // The summon's organic ink narrative (docs/09 §3): a thread of ink wanders
    // from the last stroke's tail to the answer locus, gathers into a blot fed
    // by travelling beads while the scribe thinks, and is then drunk — drained
    // tail-first into the blot so the answer writes into a clean page.
    private enum class InkPhase { IDLE, REACH, GATHER, DRINK, RECEDE }

    private var inkPhase = InkPhase.IDLE
    private var threadPath: Path? = null
    private var roots: List<SummonInk.Tendril> = emptyList()
    private var threadLen = 0f
    private var threadProgress = 0f
    private var reachStep = 0
    private var drainProgress = 0f
    private var beadProgress = 0f
    private var rippleT = 0f
    private var flowPause = 0
    private var breathStep = 0
    private var blotX = 0f
    private var blotY = 0f
    private val inkHandler = Handler(Looper.getMainLooper())
    private val inkTick: Runnable = object : Runnable {
        override fun run() {
            advanceInk()
            if (inkPhase != InkPhase.IDLE) {
                inkHandler.postDelayed(this, INK_STEP_MS)
            }
            invalidate()
        }
    }

    val isSummonInkActive: Boolean get() = inkPhase != InkPhase.IDLE

    /** Fired once the reached-out ink has been drunk — the page is clean. */
    var onSummonInkDrained: (() -> Unit)? = null

    /**
     * The ink reaches out: a thread wanders from the last stroke's tail to the
     * answer locus ([blotX],[blotY]), then beads flow while waiting.
     * [exitDx],[exitDy] is the direction the pen was travelling as the stroke
     * ended — the thread leaves along it before turning toward the locus.
     */
    @JvmOverloads
    fun beginSummonInk(
        tailX: Float,
        tailY: Float,
        blotX: Float,
        blotY: Float,
        exitDx: Float = 0f,
        exitDy: Float = 0f,
    ) {
        this.blotX = blotX
        this.blotY = blotY
        val path = SummonInk.buildThread(tailX, tailY, blotX, blotY, exitDx, exitDy)
        threadPath = path
        threadLen = SummonInk.threadLength(path)
        roots = SummonInk.buildRoots(path, threadLen, tailX, tailY, blotX, blotY, exitDx, exitDy)
        threadProgress = 0f
        reachStep = 0
        drainProgress = 0f
        beadProgress = 0f
        rippleT = 0f
        flowPause = 0
        breathStep = 0
        inkPhase = InkPhase.REACH
        inkHandler.removeCallbacks(inkTick)
        inkHandler.postDelayed(inkTick, INK_STEP_MS)
        invalidate()
    }

    /**
     * The answer arrived (or the summon ended). [keep] true — the answer drinks
     * the thread: it drains tail-first into the blot, which deflates away, and
     * [onSummonInkDrained] fires on a clean page. False — the thread draws back
     * into the stroke tip-first and everything clears (reaching-out undone,
     * which is deliberately a different gesture from being consumed).
     */
    fun endSummonInk(keep: Boolean) {
        if (inkPhase == InkPhase.IDLE) return
        if (keep) {
            inkPhase = InkPhase.DRINK
            threadProgress = 1f
            drainProgress = 0f
            beadProgress = 0f
            breathStep = 0
            inkHandler.removeCallbacks(inkTick)
            inkHandler.postDelayed(inkTick, INK_STEP_MS)
            invalidate()
        } else {
            inkPhase = InkPhase.RECEDE
            invalidate()
        }
    }

    /** Tap-to-skip the drain (docs/09 §2: every animation is skippable). */
    private fun skipDrainIfDrinking() {
        if (inkPhase == InkPhase.DRINK) drainProgress = 1f
    }

    private fun advanceInk() {
        when (inkPhase) {
            InkPhase.REACH -> {
                // Hesitating advance: ink wicks in bursts, not at a constant rate.
                threadProgress += SummonInk.threadStepDeltas[reachStep]
                reachStep++
                if (reachStep >= SummonInk.THREAD_STEPS) {
                    threadProgress = 1f
                    inkPhase = InkPhase.GATHER
                    flowPause = FLOW_PAUSE_STEPS
                }
            }
            InkPhase.GATHER -> {
                breathStep = (breathStep + 1) % BREATH_STEPS
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
            InkPhase.DRINK -> {
                drainProgress += 1f / DRINK_STEPS
                if (drainProgress >= 1f) {
                    drainProgress = 1f
                    clearSummonInk()
                    // One clean pass over the ~40 partial updates we just made.
                    Epd.cleanRefresh(this)
                    onSummonInkDrained?.invoke()
                }
            }
            InkPhase.RECEDE -> {
                threadProgress -= 1f / RECEDE_STEPS
                if (threadProgress <= 0f) {
                    threadProgress = 0f
                    clearSummonInk()
                }
            }
            else -> Unit
        }
    }

    private fun clearSummonInk() {
        inkPhase = InkPhase.IDLE
        threadPath = null
        roots = emptyList()
        threadLen = 0f
    }

    private fun drawSummonInk(canvas: Canvas) {
        if (inkPhase == InkPhase.IDLE) return
        val path = threadPath
        if (path != null && threadLen > 0f) {
            if (inkPhase == InkPhase.DRINK) {
                SummonInk.drawThreadDraining(canvas, path, threadLen, drainProgress)
                SummonInk.drawRoots(canvas, roots, 1f, drainProgress)
            } else if (threadProgress > 0f) {
                SummonInk.drawThread(canvas, path, threadLen, threadProgress)
                SummonInk.drawRoots(canvas, roots, threadProgress, 0f)
                if (inkPhase == InkPhase.GATHER) {
                    SummonInk.drawBead(canvas, path, threadLen, beadProgress)
                }
            }
        }
        if (threadProgress >= 1f || inkPhase == InkPhase.RECEDE) {
            val breath = sin(breathStep * 2f * Math.PI.toFloat() / BREATH_STEPS)
            val deflate = if (inkPhase == InkPhase.DRINK) 1f - drainProgress else 1f
            SummonInk.drawBlot(canvas, blotX, blotY, BLOT_RADIUS, breath, deflate)
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

    // Hoisted, not per-call: drawRules runs from onDraw (audit P4-7).
    private val rulePaint = Paint().apply {
        color = MagicTheme.INK
        alpha = MagicTheme.RULE_ALPHA
        strokeWidth = 1.2f
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
        pageBackground?.let { bmp ->
            c.drawBitmap(bmp, null, RectF(0f, 0f, p.width.toFloat(), p.height.toFloat()), bgPaint)
        }
        if (pageBackground == null) drawRules(c, p)
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
    private val smudgeTick = Runnable { advanceSmudge() }

    /** Page-space top of each answer layout, parallel to [answerLayouts] (M5). */
    private var answerLayoutStarts: List<Float> = emptyList()

    /** Layout index currently smudging away, or -1. */
    private var smudgeIndex = -1
    private var smudgeStep = 0

    /** Fired when a long-press lands on the page (finger); page-space coords. */
    var onAnswerLongPress: ((pageX: Float, pageY: Float) -> Unit)? = null

    /** Fired when the smudge-away animation finished; the caller removes the answer. */
    var onSmudgeComplete: (() -> Unit)? = null

    // Explicit main-looper handler: the view may be constructed off the main
    // thread (instrumented tests), and GestureDetector's default handler binds
    // to the constructing thread.
    private val gestureDetector = android.view.GestureDetector(
        context,
        object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: android.view.MotionEvent) {
                onAnswerLongPress?.invoke(transform.toPageX(e.x), transform.toPageY(e.y))
            }
        },
        Handler(Looper.getMainLooper()),
    )

    /** Replaces the answer ink; [animateLast] writes only the newest in. */
    fun setAnswers(
        layouts: List<AnswerInk.Layout>,
        displayBottom: Float,
        animateLast: Boolean,
        layoutStarts: List<Float> = emptyList(),
    ) {
        answerLayouts = layouts
        answerLayoutStarts = layoutStarts
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

    /** Which answer layout contains [pageX]/[pageY], or null (M5 hit-testing). */
    fun answerGroupIndexAt(pageX: Float, pageY: Float): Int? {
        for (i in answerLayouts.indices) {
            val start = answerLayoutStarts.getOrNull(i) ?: continue
            val end = start + answerLayouts[i].heightPx
            if (pageY in start..end) return i
        }
        return null
    }

    /**
     * Plays the smudge-away animation for layout [index] (F4.6, M5): the ink
     * streaks into gray smudges over a few steps, then [onSmudgeComplete]
     * fires and the caller removes the answer from the session.
     */
    fun beginSmudge(index: Int) {
        if (index !in answerLayouts.indices || smudgeIndex != -1) return
        smudgeIndex = index
        smudgeStep = 0
        answerHandler.removeCallbacks(smudgeTick)
        answerHandler.postDelayed(smudgeTick, INK_STEP_MS)
        invalidate()
    }

    val isSmudging: Boolean get() = smudgeIndex != -1

    private fun advanceSmudge() {
        if (smudgeIndex == -1) return
        smudgeStep++
        if (smudgeStep >= SMUDGE_STEPS) {
            smudgeIndex = -1
            answerHandler.removeCallbacks(smudgeTick)
            onSmudgeComplete?.invoke()
        } else {
            answerHandler.postDelayed(smudgeTick, INK_STEP_MS)
        }
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

    /** Test accessor: the page→view vertical offset (audit P0-3 asserts it stays 0). */
    val transformOffsetY: Float get() = transform.offsetY
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

    /**
     * The view is going away: stop every self-reposting ticker, or a summon
     * interrupted mid-flight would keep the main looper busy and leak this
     * view (→ NotebookScreen → Activity) for the life of the process, plus
     * the ~5-15 MB bgLayer bitmap (audit P1-1).
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        inkHandler.removeCallbacks(inkTick)
        answerHandler.removeCallbacks(answerTick)
        answerHandler.removeCallbacks(smudgeTick)
        smudgeIndex = -1
        flushHandler.removeCallbacks(flushRunnable)
        flushHandler.removeCallbacks(cleanRunnable)
        inkPhase = InkPhase.IDLE
        threadPath = null
        bgLayer?.recycle()
        bgLayer = null
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
            pageBackground?.let { bmp ->
                canvas.drawBitmap(bmp, null, android.graphics.RectF(0f, 0f, p.width.toFloat(), p.height.toFloat()), bgPaint)
            }
            if (pageBackground == null) drawRules(canvas, p)
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
        for ((li, layout) in answerLayouts.withIndex()) {
            if (li == smudgeIndex) {
                drawSmudge(canvas, layout, smudgeStep.toFloat() / SMUDGE_STEPS)
                continue
            }
            val isLast = li == answerLayouts.lastIndex
            // Only the last layout ever animates; its item offsets are relative
            // to its own start — no cross-layout accumulator (audit P0-4: the
            // old cumTicks overflowed past Int.MAX_VALUE and the newest answer
            // never revealed).
            var itemStart = 0
            for (item in layout.items) {
                val ticks = item.revealTicks
                val local = if (!isLast || !animatingLayout || answerTicks >= layout.totalTicks) {
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
                    is AnswerInk.Layout.Item.Stroke -> {
                        drawStrokeItem(canvas, item, 0f, local, null)
                    }
                }
                itemStart += ticks
            }
        }
    }

    /** Draws a synthesized pen stroke (width-modulated polyline) up to [fraction] of its arc. */
    private fun drawStrokeItem(
        canvas: Canvas,
        item: AnswerInk.Layout.Item.Stroke,
        dx: Float,
        fraction: Float,
        paint: Paint?,
    ) {
        if (fraction <= 0f || item.pointCount < 2) return
        val p = paint ?: answerStrokePaint
        val target = item.length * fraction
        val xys = item.xys
        val widths = item.widths
        var cum = 0f
        val n = item.pointCount
        var i = 0
        while (i < n - 1) {
            val x0 = xys[i * 2] + dx
            val y0 = xys[i * 2 + 1]
            val x1 = xys[(i + 1) * 2] + dx
            val y1 = xys[(i + 1) * 2 + 1]
            val dxs = x1 - x0
            val dys = y1 - y0
            val segLen = hypot(dxs, dys)
            if (segLen <= 0.001f) {
                i++
                continue
            }
            p.strokeWidth = (widths[i] + widths[i + 1]) * 0.5f
            if (cum + segLen <= target) {
                canvas.drawLine(x0, y0, x1, y1, p)
            } else {
                val t = (target - cum) / segLen
                canvas.drawLine(x0, y0, x0 + dxs * t, y0 + dys * t, p)
                break
            }
            cum += segLen
            i++
        }
    }

    private val answerStrokePaint: Paint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = MagicTheme.INK
        }
    }

    /**
     * The smudge-away (F4.6, M5): the answer streaks horizontally into gray
     * smudges as [progress] goes 0→1 — three passes at widening offsets with
     * fading alpha reads as ink being smeared, not erased.
     */
    private fun drawSmudge(canvas: Canvas, layout: AnswerInk.Layout, progress: Float) {
        if (progress <= 0f) return
        val streak = progress * 10f
        val fade = (0xFF * (1f - progress)).toInt().coerceIn(0, 0xFF)
        for ((ii, item) in layout.items.withIndex()) {
            val wob = ((ii * 7) % 5 - 2) * streak * 0.35f // per-item smear direction
            for (pass in -1..1) {
                val dx = pass * streak + wob * pass
                when (item) {
                    is AnswerInk.Layout.Item.Line -> {
                        val p = Paint(item.paint).apply { alpha = fade }
                        canvas.drawText(item.text, item.x + dx, item.baselineY, p)
                    }
                    is AnswerInk.Layout.Item.Rule -> {
                        val p = Paint(item.paint).apply { alpha = fade }
                        canvas.drawLine(item.x1 + dx, item.y1, item.x2 + dx, item.y2, p)
                    }
                    is AnswerInk.Layout.Item.Path -> {
                        val p = Paint(item.paint).apply { alpha = fade }
                        canvas.save()
                        canvas.translate(dx, 0f)
                        canvas.drawPath(item.path, p)
                        canvas.restore()
                    }
                    is AnswerInk.Layout.Item.Stroke -> {
                        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            style = Paint.Style.STROKE
                            strokeCap = Paint.Cap.ROUND
                            strokeJoin = Paint.Join.ROUND
                            color = MagicTheme.INK
                            alpha = fade
                        }
                        drawStrokeItem(canvas, item, dx, 1f, p)
                    }
                }
            }
        }
    }

    private fun drawRules(canvas: Canvas, p: NotebookPage) {
        var y = MagicTheme.RULE_SPACING
        while (y < p.height) {
            canvas.drawLine(0f, y, p.width.toFloat(), y, rulePaint)
            y += MagicTheme.RULE_SPACING
        }
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val isStylus = isStylusEvent(event)

        // Fingers never draw, but they feed the gesture detector: a long-press
        // on the answer area summons the per-answer controls (M5, F4.6). The
        // event itself is still declined so parents can scroll/click.
        if (!isStylus) gestureDetector.onTouchEvent(event)

        // A touch during the drain finishes it now — never consumed here, so
        // pen input carries on untouched (docs/09 §2).
        if (event.actionMasked == MotionEvent.ACTION_DOWN) skipDrainIfDrinking()

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
            MotionEvent.ACTION_DOWN -> {
                // Palm rejection is tool-type gating: a finger is never a pen
                // (audit P4-3 — the hover gate was unreachable; Android sends
                // hover events to onGenericMotionEvent, which is not overridden).
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
                    // Same lift-the-fast-mode guarantee as finishStroke (P1-9).
                    flushHandler.removeCallbacks(cleanRunnable)
                    flushHandler.postDelayed(cleanRunnable, CLEAN_PASS_MS)
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
        // Ghosting clean pass, debounced — scheduled BEFORE the commit
        // check: fast mode was enabled on DOWN and must always be lifted,
        // even for a dot too short to commit (audit P1-9).
        flushHandler.removeCallbacks(cleanRunnable)
        flushHandler.postDelayed(cleanRunnable, CLEAN_PASS_MS)
        val stroke = current.build() ?: return
        onStrokeComplete?.invoke(stroke)
        invalidate()
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
        private const val INK_STEP_MS = 100L
        private const val DRINK_STEPS = 14
        private const val BEAD_STEPS = 12
        private const val FLOW_PAUSE_STEPS = 22
        private const val RECEDE_STEPS = 8
        private const val SMUDGE_STEPS = 5 // ~0.5 s smudge-away (M5, F4.6)
        private const val BREATH_STEPS = 60 // ~6 s breathing cycle at INK_STEP_MS
        private const val RIPPLE_STEP = 0.18f
        private const val BLOT_RADIUS = 24f
    }
}
