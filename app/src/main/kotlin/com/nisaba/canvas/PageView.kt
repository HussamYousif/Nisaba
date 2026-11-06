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
import com.nisaba.domain.notebook.PageBackground
import com.nisaba.domain.shared.Affine
import com.nisaba.domain.shared.PagePoint
import com.nisaba.domain.shared.PageSize
import com.nisaba.domain.shared.StrokeId
import com.nisaba.domain.shared.fitTransform
import com.nisaba.infra.render.BackgroundRenderer
import com.nisaba.infra.render.InkRenderer

/**
 * The page: paper + committed ink in one cached bitmap, plus the live stroke on top when the pen device
 * doesn't draw it itself. Finger gestures (page turns, undo/redo, toolbar) are handled here; stylus goes to [pen].
 */
class PageView(
    context: Context,
    internal val pen: PenDevice,
    internal val ink: InkRenderer,
    internal val background: BackgroundRenderer,
) : View(context), ScribeCanvas {
    var onSwipe: (Int) -> Unit = {}
    var onMultiTap: (Int) -> Unit = {}
    var onTopTap: () -> Unit = {}

    /** A finger held still on the page (view px): e.g. to act on the answer under it. */
    var onLongPress: (Float, Float) -> Unit = { _, _ -> }
    internal val longPress = Runnable { if (!moved && maxPointers == 1 && !isScribing()) { longPressed = true; onLongPress(downX, downY) } }
    internal var longPressed = false
    var onSizeKnown: (Int, Int) -> Unit = { _, _ -> }
    var topStripPx: Int = 96
    /** Endless canvas only: the view's top-left in page space or its zoom changed (to remember where you were). */
    var onPan: (Float, Float, Float) -> Unit = { _, _, _ -> }

    /** Endless canvas only: a pinch changed the zoom (the panel wants a clean pass). */
    var onZoomed: () -> Unit = {}

    init { pen.onLiveChanged = { onLive() } }

    // live ink from a pen device that doesn't draw its own: only the newest points are painted, into a small dirty rect
    internal var liveDrawn = 0
    internal var liveStrokesAwaitingCommit = 0 // strokes already painted live whose committed copy has not arrived yet

    internal fun onLive() {
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

    internal var liveInk = LIVE_INK
    internal var liveWidthScale = 1f

    /** The user's pen: live ink (here or the device's own) is drawn in [argb] at [width] page units. */
    fun setPen(argb: Int, width: Float) {
        liveInk = argb; liveWidthScale = width / MEDIUM_WIDTH
        pen.setPen(argb, width)
    }

    internal var pageId: com.nisaba.domain.shared.PageId? = null
    internal var pageSize = PageSize.PORTRAIT
    internal var pageBackground: PageBackground = PageBackground.Blank
    internal var paper = com.nisaba.domain.notebook.Paper.Blank
    internal var strokes: List<Stroke> = emptyList()
    internal var layer: Bitmap? = null
    internal var layerCanvas: Canvas? = null
    internal var affine = Affine(1f, 0f, 0f)
    internal var scratch: Bitmap? = null

    // endless canvas: the view is a window onto the huge page; [panX]/[panY] = page position of its top-left corner
    internal var endless = false
    internal var panX = 0f
    internal var panY = 0f
    internal var zoom = 1f // screen px per page unit ([ZOOM_LEVELS])
    internal val pinch = Pinch()
    internal var lastX = 0f; internal var lastY = 0f
    internal var pendingDx = 0f; internal var pendingDy = 0f
    internal var lastPanMillis = 0L
    internal var exclusions: List<Rect> = emptyList()
    internal var attached = false

    // finger gesture tracking
    internal var downX = 0f; internal var downY = 0f; internal var downTime = 0L
    internal var maxPointers = 0; internal var moved = false

    private fun unchanged(size: PageSize, bg: PageBackground, paper: com.nisaba.domain.notebook.Paper) =
        size == pageSize && bg == pageBackground && paper == this.paper

    /** Shows page [id]; another page starts from nothing (its ink follows in [setStrokes]), so nothing carries over. */
    fun setPage(
        size: PageSize,
        bg: PageBackground,
        origin: PagePoint? = null,
        id: com.nisaba.domain.shared.PageId? = null,
        paper: com.nisaba.domain.notebook.Paper = com.nisaba.domain.notebook.Paper.Blank,
        zoom: Float = 1f,
    ) {
        val samePage = id == pageId
        if (samePage && unchanged(size, bg, paper)) return
        this.paper = paper
        if (!samePage) { strokes = emptyList(); drawnByHand.clear(); handPieces.clear(); liveStrokesAwaitingCommit = 0 }
        pageId = id
        pageSize = size; pageBackground = bg
        endless = size.isEndless
        if (endless) {
            panX = origin?.x ?: PageSize.ENDLESS_ORIGIN
            panY = origin?.y ?: PageSize.ENDLESS_ORIGIN
            this.zoom = zoom
        }
        recomputeTransform()
        // another page: its ink comes in setStrokes right after, so the layer is built once, with it
        if (samePage) { rebuild(); invalidate() } else rebuildPending = true
    }
    private var rebuildPending = false // a page shown, its ink not given yet: setStrokes builds the layer

    /** Applies new ink: only the appended strokes are painted ([redrawOf]); anything else rebuilds the layer. */
    fun setStrokes(next: List<Stroke>) {
        val prev = strokes
        strokes = next
        val canvas = layerCanvas
        if (canvas == null) { invalidate(); return }
        if (rebuildPending) { rebuild(); invalidate(); return }
        val redraw = redrawOf(prev, next)
        if (redraw !is Redraw.Append) { drawnByHand.clear(); liveStrokesAwaitingCommit = 0; rebuild(); invalidate(); return }
        for (s in next.subList(redraw.from, next.size)) {
            if (alreadyPainted(s)) continue
            withPageTransform(canvas) { ink.drawStroke(canvas, s) }
            invalidate(dirtyOf(s.bounds, s.width * 2f + 4f))
        }
    }

    /** True if [s] was already painted on screen while it was being written (by the hand, or live under the pen). */
    internal fun alreadyPainted(s: Stroke): Boolean {
        if (drawnByHand.remove(s.id)) return true
        if (liveStrokesAwaitingCommit > 0 && s.origin is StrokeOrigin.User) { liveStrokesAwaitingCommit--; return true }
        return false
    }

    // --- the hand writing (drawn straight into the ink layer), resume mark ----------------------------------------
    internal var resumeMark: PagePoint? = null
    internal var selection: com.nisaba.domain.shared.PageRect? = null
    internal val drawnByHand = HashSet<StrokeId>()

    /** What the hand drew that isn't committed yet: redrawn after any rebuild, so a redraw mid-answer can't wipe it. */
    internal class HandPiece(val points: InkPoints, val argb: Int, val widthScale: Float)
    internal val handPieces = ArrayList<HandPiece>()
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

    /**
     * Everything the hand drew so far has been committed as [ids]: `setStrokes` must not draw them a second time, and
     * they no longer need redrawing from [handPieces].
     */
    fun markDrawn(ids: Collection<StrokeId>) { drawnByHand += ids; handPieces.clear() }

    /** One clean refresh pass over a region of page space (minX, minY, maxX, maxY). */
    override fun cleanRegion(b: FloatArray) = invalidate(dirtyOf(com.nisaba.domain.shared.PageRect(b[0], b[1], b[2], b[3]), MAX_WIDTH))

    /** The page picture became available (or changed): repaint paper, picture and ink. */
    fun reloadBackground() { rebuild(); invalidate() }

    /** Live ink still waiting for its committed copy will not get one as drawn (it was replaced): paint what arrives. */
    fun forgetLiveInk() { liveStrokesAwaitingCommit = 0 }

    fun setExclusions(rects: List<Rect>) {
        exclusions = rects
        if (attached) pen.setExclusions(rects)
    }

    internal inline fun withPageTransform(canvas: Canvas, block: () -> Unit) {
        canvas.save(); canvas.translate(affine.dx, affine.dy); canvas.scale(affine.scale, affine.scale); block(); canvas.restore()
    }

    internal fun recomputeTransform() {
        affine = if (endless) Affine(zoom, -panX * zoom, -panY * zoom) else fitTransform(pageSize, width.toFloat(), height.toFloat())
    }

    internal fun rebuild() {
        rebuildPending = false
        val canvas = layerCanvas ?: return
        val t0 = SystemClock.uptimeMillis()
        canvas.drawColor(BackgroundRenderer.PAPER)
        withPageTransform(canvas) {
            background.draw(canvas, pageSize, pageBackground, paper)
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

    internal fun attachPen() {
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
        resumeMark?.let { canvas.drawText("…", it.x, it.y, MARK_PAINT) }
        selection?.let { b ->
            val p = SELECTION_PAD
            canvas.drawRect(b.left - p, b.top - p, b.right + p, b.bottom + p, SELECTION_PAINT)
        }
        canvas.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN && e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS && isScribing()) {
            onStylusDownWhileScribing()                       // the raw layer is suspended while the hand writes
        }
        if (pen.handleTouch(e)) return true
        if (e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) fingerEvent(e)
        return true
    }

    internal companion object {
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
        const val SELECTION_PAD = 14f
        const val MARK_HIT = 90f
        const val PAN_STEP_MILLIS = 16L // the canvas follows the finger every frame (e-ink: 120, in steps)
        const val FOLLOW_EDGE = 0.88f
        const val FOLLOW_TARGET = 0.3f
        const val MIN_VISIBLE_WIDTH = 500f
        const val ENDLESS_LEFT_MARGIN = 200f
        const val PINCH_SLOP = 60f
    }
}
