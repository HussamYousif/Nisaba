package com.nisaba.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.nisaba.device.PenDevice
import com.nisaba.domain.ink.InkPoints
import com.nisaba.domain.ink.Stroke
import com.nisaba.domain.ink.StrokeOrigin
import com.nisaba.domain.shared.PagePoint
import com.nisaba.domain.shared.StrokeId
import com.nisaba.domain.notebook.PageBackground
import com.nisaba.domain.shared.Affine
import com.nisaba.domain.shared.PageSize
import com.nisaba.domain.shared.fitTransform
import com.nisaba.infra.render.BackgroundRenderer
import com.nisaba.infra.render.InkRenderer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The page: paper + committed ink in one cached bitmap, plus the live stroke on top when the pen device
 * doesn't draw it itself. Finger gestures (page turns, undo/redo, toolbar) are handled here; stylus goes to [pen].
 */
class PageView(
    context: Context,
    private val pen: PenDevice,
    private val ink: InkRenderer,
    private val background: BackgroundRenderer,
) : View(context), ScribeCanvas {
    var onSwipe: (Int) -> Unit = {}
    var onMultiTap: (Int) -> Unit = {}
    var onTopTap: () -> Unit = {}

    /** A finger held still on the page (view px): e.g. to act on the answer under it. */
    var onLongPress: (Float, Float) -> Unit = { _, _ -> }
    private val longPress = Runnable { if (!moved && maxPointers == 1 && !isScribing()) { longPressed = true; onLongPress(downX, downY) } }
    private var longPressed = false
    var onSizeKnown: (Int, Int) -> Unit = { _, _ -> }
    var topStripPx: Int = 96
    /** Endless canvas only: the view's top-left in page space changed (to remember where you were). */
    var onPan: (Float, Float) -> Unit = { _, _ -> }

    init { pen.onLiveChanged = { onLive() } }

    // live ink from a pen device that doesn't draw its own: only the newest points are painted, into a small dirty rect
    private var liveDrawn = 0
    private var liveStrokesAwaitingCommit = 0 // strokes already painted live whose committed copy has not arrived yet

    private fun onLive() {
        val canvas = layerCanvas ?: return
        val n = pen.liveCount
        if (n == 0) { // the stroke just ended
            if (liveDrawn > 0) liveStrokesAwaitingCommit++
            liveDrawn = 0
            return
        }
        if (n <= liveDrawn) return
        val seg = pen.liveSlice(maxOf(0, liveDrawn - LIVE_OVERLAP), n) ?: return
        ink.drawLive(canvas, seg, liveInk, liveWidthScale)
        invalidate(dirtyOfView(seg.bounds(), MAX_WIDTH))
        liveDrawn = n
    }

    private var liveInk = LIVE_INK
    private var liveWidthScale = 1f

    /** The user's pen: live ink (here or the device's own) is drawn in [argb] at [width] page units. */
    fun setPen(argb: Int, width: Float) {
        liveInk = argb; liveWidthScale = width / MEDIUM_WIDTH
        pen.setPen(argb, width)
    }

    private fun dirtyOfView(b: com.nisaba.domain.shared.PageRect, pad: Float) =
        Rect(floor(b.left - pad).toInt(), floor(b.top - pad).toInt(), ceil(b.right + pad).toInt(), ceil(b.bottom + pad).toInt())

    private var pageId: com.nisaba.domain.shared.PageId? = null
    private var pageSize = PageSize.PORTRAIT
    private var pageBackground: PageBackground = PageBackground.Blank
    private var strokes: List<Stroke> = emptyList()
    private var layer: Bitmap? = null
    private var layerCanvas: Canvas? = null
    private var affine = Affine(1f, 0f, 0f)
    private var scratch: Bitmap? = null

    // endless canvas: the view is a window onto the huge page; [panX]/[panY] = page position of its top-left corner
    private var endless = false
    private var panX = 0f
    private var panY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var pendingDx = 0f; private var pendingDy = 0f
    private var lastPanMillis = 0L
    private var exclusions: List<Rect> = emptyList()
    private var attached = false

    // finger gesture tracking
    private var downX = 0f; private var downY = 0f; private var downTime = 0L
    private var maxPointers = 0; private var moved = false

    /** Shows page [id]; another page starts from nothing (its ink follows in [setStrokes]), so nothing carries over. */
    fun setPage(size: PageSize, bg: PageBackground, origin: PagePoint? = null, id: com.nisaba.domain.shared.PageId? = null) {
        val samePage = id == pageId
        if (samePage && size == pageSize && bg == pageBackground) return
        if (!samePage) { strokes = emptyList(); drawnByHand.clear(); handPieces.clear(); liveStrokesAwaitingCommit = 0 }
        pageId = id
        pageSize = size; pageBackground = bg
        endless = size.isEndless
        if (endless) {
            panX = origin?.x ?: PageSize.ENDLESS_ORIGIN
            panY = origin?.y ?: PageSize.ENDLESS_ORIGIN
        }
        recomputeTransform(); rebuild(); invalidate()
    }

    /** Moves the endless canvas under the view by shifting the picture and painting only the newly exposed strips. */
    private fun panTo(nx: Float, ny: Float) {
        val tx = nx.coerceIn(0f, PageSize.ENDLESS.w - width)
        val ty = ny.coerceIn(0f, PageSize.ENDLESS.h - height)
        val dx = Math.round(tx - panX); val dy = Math.round(ty - panY)
        if (dx == 0 && dy == 0) return
        panX += dx; panY += dy
        recomputeTransform()
        val old = layer
        if (old == null || abs(dx) >= width || abs(dy) >= height) { rebuild() } else {
            val next = scratch?.takeIf { it.width == width && it.height == height }
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val nextCanvas = Canvas(next)
            nextCanvas.drawBitmap(old, -dx.toFloat(), -dy.toFloat(), null)
            scratch = old; layer = next; layerCanvas = nextCanvas
            if (dy > 0) paintStrip(Rect(0, height - dy, width, height)) else if (dy < 0) paintStrip(Rect(0, 0, width, -dy))
            if (dx > 0) paintStrip(Rect(width - dx, 0, width, height)) else if (dx < 0) paintStrip(Rect(0, 0, -dx, height))
        }
        invalidate()
        onPan(panX, panY)
    }

    /** Repaints one band of the picture from paper and committed ink. */
    private fun paintStrip(r: Rect) {
        val canvas = layerCanvas ?: return
        canvas.save()
        canvas.clipRect(r)
        canvas.drawColor(BackgroundRenderer.PAPER)
        withPageTransform(canvas) {
            ink.drawAll(canvas, visibleStrokes(r))
            for (p in handPieces) ink.drawLive(canvas, p.points, p.argb, p.widthScale)
        }
        canvas.restore()
    }

    /** On an endless canvas, only the strokes that reach [area] (view px; the whole view when null): the canvas is huge. */
    private fun visibleStrokes(area: Rect? = null): List<Stroke> {
        if (!endless) return strokes
        val a = area ?: Rect(0, 0, width, height)
        val l = panX + a.left - MAX_WIDTH; val t = panY + a.top - MAX_WIDTH
        val r = panX + a.right + MAX_WIDTH; val b = panY + a.bottom + MAX_WIDTH
        return strokes.filter { s -> s.bounds.let { it.right >= l && it.left <= r && it.bottom >= t && it.top <= b } }
    }

    /** Applies new ink: incremental when exactly one stroke was appended, full rebuild otherwise. */
    fun setStrokes(next: List<Stroke>) {
        val prev = strokes
        strokes = next
        val canvas = layerCanvas
        if (canvas == null) { invalidate(); return }
        val appendedOnly = next.size >= prev.size && prev.indices.all { next[it] === prev[it] }
        val freshPage = prev.isEmpty() && next.size > 1 // a page just opened: one pass (and only what's in view on a canvas)
        if (!appendedOnly || freshPage) { drawnByHand.clear(); liveStrokesAwaitingCommit = 0; rebuild(); invalidate(); return }
        for (s in next.subList(prev.size, next.size)) {
            if (alreadyPainted(s)) continue
            withPageTransform(canvas) { ink.drawStroke(canvas, s) }
            invalidate(dirtyOf(s.bounds, s.width * 2f + 4f))
        }
    }

    /** True if [s] was already painted on screen while it was being written (by the hand, or live under the pen). */
    private fun alreadyPainted(s: Stroke): Boolean {
        if (drawnByHand.remove(s.id)) return true
        if (liveStrokesAwaitingCommit > 0 && s.origin is StrokeOrigin.User) { liveStrokesAwaitingCommit--; return true }
        return false
    }

    // --- the hand writing (drawn straight into the ink layer), resume mark ----------------------------------------
    private val markPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF27407A.toInt(); textSize = 64f; typeface = android.graphics.Typeface.SERIF
    }
    private var resumeMark: PagePoint? = null
    private val drawnByHand = HashSet<StrokeId>()

    /** What the hand drew that isn't committed yet: redrawn after any rebuild, so a redraw mid-answer can't wipe it. */
    private class HandPiece(val points: InkPoints, val argb: Int, val widthScale: Float)
    private val handPieces = ArrayList<HandPiece>()
    var onMarkTap: () -> Unit = {}
    var onSkipTap: () -> Unit = {}
    var onStylusDownWhileScribing: () -> Unit = {}
    var isScribing: () -> Boolean = { false }

    /** Draws points [from, toExclusive) of a stroke being written by the hand into the ink layer. */
    override fun drawSegment(points: InkPoints, from: Int, toExclusive: Int, argb: Int, widthScale: Float) {
        val canvas = layerCanvas ?: return
        if (toExclusive <= from) return
        val part = points.slice(from, toExclusive)
        handPieces += HandPiece(part, argb, widthScale)
        withPageTransform(canvas) { ink.drawLive(canvas, part, argb, widthScale) }
        invalidate(dirtyOf(part.bounds(), MAX_WIDTH))
        if (endless) followWriting(points.ys[toExclusive - 1])
    }

    /** The hand wrote near the bottom edge of the view: scroll so the line it is on sits comfortably higher. */
    private fun followWriting(y: Float) {
        if (y - panY > height * FOLLOW_EDGE) panTo(panX, y - height * FOLLOW_TARGET)
    }

    /**
     * Everything the hand drew so far has been committed as [ids]: `setStrokes` must not draw them a second time, and
     * they no longer need redrawing from [handPieces].
     */
    fun markDrawn(ids: Collection<StrokeId>) { drawnByHand += ids; handPieces.clear() }

    /** Shows the endless canvas from [origin] (page space): a jump, so the whole picture is redrawn. */
    fun jumpTo(origin: PagePoint) { if (endless) panTo(origin.x, origin.y) }

    /** The answer will start at [at]: nothing is shown while the model thinks, but an endless canvas pans there. */
    fun prepareAnswerAt(at: PagePoint) {
        if (!endless || width <= 0) return
        val vx = at.x - panX; val vy = at.y - panY
        val offSideways = vx < 0 || vx > width - MIN_VISIBLE_WIDTH
        if (offSideways || vy < 0 || vy > height * FOLLOW_EDGE) {
            panTo(if (offSideways) at.x - ENDLESS_LEFT_MARGIN else panX, at.y - height * FOLLOW_TARGET)
        }
    }

    fun showResumeMark(at: PagePoint?) {
        val old = resumeMark; resumeMark = at
        old?.let { invalidate(dirtyAround(it, MARK_REACH)) }
        at?.let { invalidate(dirtyAround(it, MARK_REACH)) }
    }

    /** One clean refresh pass over a region of page space (minX, minY, maxX, maxY). */
    override fun cleanRegion(b: FloatArray) = invalidate(dirtyOf(com.nisaba.domain.shared.PageRect(b[0], b[1], b[2], b[3]), MAX_WIDTH))

    private fun dirtyAround(p: PagePoint, reach: Float): Rect {
        val cx = p.x * affine.scale + affine.dx; val cy = p.y * affine.scale + affine.dy
        val r = reach * affine.scale + 4f
        return Rect(floor(cx - r).toInt(), floor(cy - r).toInt(), ceil(cx + r).toInt(), ceil(cy + r).toInt())
    }

    private fun dirtyOf(b: com.nisaba.domain.shared.PageRect, pad: Float): Rect = Rect(
        floor(b.left * affine.scale + affine.dx - pad).toInt(), floor(b.top * affine.scale + affine.dy - pad).toInt(),
        ceil(b.right * affine.scale + affine.dx + pad).toInt(), ceil(b.bottom * affine.scale + affine.dy + pad).toInt(),
    )

    /** The page picture became available (or changed): repaint paper, picture and ink. */
    fun reloadBackground() { rebuild(); invalidate() }

    /** Live ink still waiting for its committed copy will not get one as drawn (it was replaced): paint what arrives. */
    fun forgetLiveInk() { liveStrokesAwaitingCommit = 0 }

    fun setExclusions(rects: List<Rect>) {
        exclusions = rects
        if (attached) pen.setExclusions(rects)
    }

    private inline fun withPageTransform(canvas: Canvas, block: () -> Unit) {
        canvas.save(); canvas.translate(affine.dx, affine.dy); canvas.scale(affine.scale, affine.scale); block(); canvas.restore()
    }

    private fun recomputeTransform() {
        affine = if (endless) Affine(1f, -panX, -panY) else fitTransform(pageSize, width.toFloat(), height.toFloat())
    }

    private fun rebuild() {
        val canvas = layerCanvas ?: return
        val t0 = SystemClock.uptimeMillis()
        canvas.drawColor(BackgroundRenderer.PAPER)
        withPageTransform(canvas) {
            background.draw(canvas, pageSize, pageBackground)
            ink.drawAll(canvas, visibleStrokes())
            for (p in handPieces) ink.drawLive(canvas, p.points, p.argb, p.widthScale)
        }
        drawnByHand.clear() // committed strokes not in [strokes] yet were wiped: paint them when they arrive
        val ms = SystemClock.uptimeMillis() - t0
        if (strokes.size > 50) Log.i(TAG, "rebuild ${strokes.size} strokes in ${ms}ms")
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w <= 0 || h <= 0) return
        layer?.recycle(); scratch?.recycle(); scratch = null
        layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { layerCanvas = Canvas(it) }
        recomputeTransform(); rebuild()
        onSizeKnown(w, h)
        attachPen()
    }

    private fun attachPen() {
        if (width <= 0 || !isAttachedToWindow || windowVisibility != VISIBLE) return
        pen.attach(this, Rect(0, 0, width, height), exclusions)
        attached = true
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); attachPen() }
    override fun onDetachedFromWindow() { pen.detach(); attached = false; super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) attachPen() else { pen.detach(); attached = false }
    }

    override fun onDraw(canvas: Canvas) {
        layer?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        canvas.save(); canvas.translate(affine.dx, affine.dy); canvas.scale(affine.scale, affine.scale)
        resumeMark?.let { canvas.drawText("…", it.x, it.y, markPaint) }
        canvas.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN && e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS && isScribing()) {
            onStylusDownWhileScribing()                       // the raw layer is suspended while the hand writes
        }
        if (pen.handleTouch(e)) return true
        if (e.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> fingerDown(e)
            MotionEvent.ACTION_POINTER_DOWN -> { maxPointers = maxOf(maxPointers, e.pointerCount); removeCallbacks(longPress) }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) > TAP_SLOP || abs(e.y - downY) > TAP_SLOP) { moved = true; removeCallbacks(longPress) }
                if (endless && maxPointers == 1) dragCanvas(e)
            }
            MotionEvent.ACTION_UP -> fingerUp(e)
            MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return true
    }

    private fun fingerDown(e: MotionEvent) {
        downX = e.x; downY = e.y; downTime = e.eventTime; maxPointers = 1; moved = false; longPressed = false
        removeCallbacks(longPress); postDelayed(longPress, LONG_PRESS_MILLIS)
        lastX = e.x; lastY = e.y; pendingDx = 0f; pendingDy = 0f
    }

    private fun fingerUp(e: MotionEvent) {
        removeCallbacks(longPress)
        when {
            longPressed -> Unit
            endless && moved && maxPointers == 1 -> applyPendingPan()
            else -> finishGesture(e)
        }
    }

    /** One finger drags the endless canvas; the picture follows in discrete steps (e-ink), not every move event. */
    private fun dragCanvas(e: MotionEvent) {
        pendingDx += e.x - lastX; pendingDy += e.y - lastY
        lastX = e.x; lastY = e.y
        if (moved && e.eventTime - lastPanMillis >= PAN_STEP_MILLIS) { lastPanMillis = e.eventTime; applyPendingPan() }
    }

    private fun applyPendingPan() {
        val dx = pendingDx; val dy = pendingDy
        pendingDx = 0f; pendingDy = 0f
        panTo(panX - dx, panY - dy)
    }

    private fun tappedMark(x: Float, y: Float): Boolean {
        val m = resumeMark ?: return false
        val p = com.nisaba.domain.shared.PagePoint(x, y).let { (it.x - affine.dx) / affine.scale to (it.y - affine.dy) / affine.scale }
        return kotlin.math.abs(p.first - m.x) < MARK_HIT && kotlin.math.abs(p.second - m.y) < MARK_HIT
    }

    private fun finishGesture(e: MotionEvent) {
        val dx = e.x - downX
        val dy = e.y - downY
        val tapLike = e.eventTime - downTime < TAP_MILLIS && !moved
        when {
            !endless && maxPointers == 1 && abs(dx) > SWIPE_MIN && abs(dy) < abs(dx) / 2 -> onSwipe(if (dx < 0) +1 else -1)
            tapLike && maxPointers in 2..3 -> onMultiTap(maxPointers)
            tapLike && maxPointers == 1 -> singleTap(e.x, e.y)
        }
    }

    /** What a one-finger tap means, in priority order: resume mark, skip the writing hand, show the toolbar. */
    private fun singleTap(x: Float, y: Float) {
        when {
            tappedMark(x, y) -> onMarkTap()
            isScribing() -> onSkipTap()
            downY < topStripPx -> onTopTap()
        }
    }

    private companion object {
        const val TAG = "NisabaPage"
        const val TAP_SLOP = 40f
        const val TAP_MILLIS = 350L
        const val LONG_PRESS_MILLIS = 550L
        const val SWIPE_MIN = 140f
        const val MAX_WIDTH = 12f
        const val LIVE_OVERLAP = 3
        const val LIVE_INK = 0xFF111111.toInt()
        const val MEDIUM_WIDTH = 3.5f
        const val MARK_REACH = 60f
        const val MARK_HIT = 90f
        const val PAN_STEP_MILLIS = 120L
        const val FOLLOW_EDGE = 0.88f
        const val FOLLOW_TARGET = 0.3f
        const val MIN_VISIBLE_WIDTH = 500f
        const val ENDLESS_LEFT_MARGIN = 200f
    }
}
