package com.nisaba.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.MotionEvent
import com.nisaba.canvas.PageView.Companion.ENDLESS_LEFT_MARGIN
import com.nisaba.canvas.PageView.Companion.FOLLOW_EDGE
import com.nisaba.canvas.PageView.Companion.FOLLOW_TARGET
import com.nisaba.canvas.PageView.Companion.LONG_PRESS_MILLIS
import com.nisaba.canvas.PageView.Companion.MARK_HIT
import com.nisaba.canvas.PageView.Companion.MIN_VISIBLE_WIDTH
import com.nisaba.canvas.PageView.Companion.PAN_STEP_MILLIS
import com.nisaba.canvas.PageView.Companion.PINCH_SLOP
import com.nisaba.canvas.PageView.Companion.SWIPE_MIN
import com.nisaba.canvas.PageView.Companion.TAP_MILLIS
import com.nisaba.canvas.PageView.Companion.TAP_SLOP
import com.nisaba.domain.shared.PagePoint
import com.nisaba.domain.shared.PageSize
import com.nisaba.infra.render.BackgroundRenderer
import kotlin.math.abs

// Finger gestures on the page (turn, undo/redo taps, long press, skip, the resume mark),
// and the endless canvas following the finger, two fingers (pan and zoom) or the hand.

/** Two fingers on an endless canvas: their spread zooms (from [zoom] at [span] apart), their middle drags. */
internal class Pinch {
    var span = 1f
    var zoom = 1f
    var spreading = false
    var zoomed = false
    var midX = Float.NaN
    var midY = 0f
}

/** Moves the endless canvas under the view by shifting the picture and painting only the newly exposed strips. */
internal fun PageView.panTo(nx: Float, ny: Float) {
    val tx = nx.coerceIn(0f, PageSize.ENDLESS.w - width / zoom)
    val ty = ny.coerceIn(0f, PageSize.ENDLESS.h - height / zoom)
    val dx = Math.round((tx - panX) * zoom); val dy = Math.round((ty - panY) * zoom) // whole pixels: the picture shifts
    if (dx == 0 && dy == 0) return
    panX += dx / zoom; panY += dy / zoom
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
    onPan(panX, panY, zoom)
}

/** Zooms the endless canvas to [z] around the view pixel [fx],[fy] (the spot there stays put); the picture is redrawn. */
fun PageView.zoomTo(z: Float, fx: Float = width / 2f, fy: Float = height / 2f) {
    if (!endless || width <= 0 || z == zoom) return
    val o = zoomOrigin(PagePoint(panX, panY), zoom, z, fx, fy)
    zoom = z
    panX = o.x.coerceIn(0f, PageSize.ENDLESS.w - width / z)
    panY = o.y.coerceIn(0f, PageSize.ENDLESS.h - height / z)
    recomputeTransform(); rebuild(); invalidate()
    onPan(panX, panY, zoom)
}

/** Shows [area] (page space) whole, as close as it fits. */
fun PageView.fitArea(area: com.nisaba.domain.shared.PageRect) {
    if (!endless || width <= 0) return
    val z = fitZoom(area, width.toFloat(), height.toFloat())
    zoom = z
    panX = area.left + area.width / 2f - width / (2f * z); panY = area.top + area.height / 2f - height / (2f * z)
    panX = panX.coerceIn(0f, PageSize.ENDLESS.w - width / z); panY = panY.coerceIn(0f, PageSize.ENDLESS.h - height / z)
    recomputeTransform(); rebuild(); invalidate()
    onPan(panX, panY, zoom)
}

/** Repaints one band of the picture from paper and committed ink. */
internal fun PageView.paintStrip(r: Rect) {
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

/** The hand wrote near the bottom edge of the view: scroll so the line it is on sits comfortably higher. */
internal fun PageView.followWriting(y: Float) {
    if ((y - panY) * zoom > height * FOLLOW_EDGE) panTo(panX, y - height * FOLLOW_TARGET / zoom)
}

/** Shows the endless canvas from [origin] (page space): a jump, so the whole picture is redrawn. */
fun PageView.jumpTo(origin: PagePoint) { if (endless) panTo(origin.x, origin.y) }

/** The answer will start at [at]: nothing is shown while the model thinks, but an endless canvas pans there. */
fun PageView.prepareAnswerAt(at: PagePoint) {
    if (!endless || width <= 0) return
    val vx = (at.x - panX) * zoom; val vy = (at.y - panY) * zoom
    val offSideways = vx < 0 || vx > width - MIN_VISIBLE_WIDTH
    if (offSideways || vy < 0 || vy > height * FOLLOW_EDGE) {
        panTo(if (offSideways) at.x - ENDLESS_LEFT_MARGIN / zoom else panX, at.y - height * FOLLOW_TARGET / zoom)
    }
}

internal fun PageView.fingerEvent(e: MotionEvent) {
    when (e.actionMasked) {
        MotionEvent.ACTION_DOWN -> fingerDown(e)
        MotionEvent.ACTION_POINTER_DOWN -> {
            maxPointers = maxOf(maxPointers, e.pointerCount); removeCallbacks(longPress)
            if (endless && e.pointerCount == 2) startPinch(e)
        }
        MotionEvent.ACTION_POINTER_UP -> if (endless && e.pointerCount == 2) endPinch(e)
        MotionEvent.ACTION_MOVE -> {
            if (abs(e.x - downX) > TAP_SLOP || abs(e.y - downY) > TAP_SLOP) { moved = true; removeCallbacks(longPress) }
            if (endless && maxPointers == 1) dragCanvas(e)
            if (endless && e.pointerCount >= 2) pinchMove(e, throttle = true)
        }
        MotionEvent.ACTION_UP -> fingerUp(e)
        MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
    }
}

private fun spanOf(e: MotionEvent) = kotlin.math.hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)).coerceAtLeast(1f)

private fun PageView.startPinch(e: MotionEvent) {
    pinch.span = spanOf(e); pinch.zoom = zoom; pinch.spreading = false
    pinch.midX = (e.getX(0) + e.getX(1)) / 2f; pinch.midY = (e.getY(0) + e.getY(1)) / 2f
    pendingDx = 0f; pendingDy = 0f
}

/** Two fingers moved: the canvas follows their middle and zooms with their spread (to the nearest zoom level). */
private fun PageView.pinchMove(e: MotionEvent, throttle: Boolean) {
    if (pinch.midX.isNaN()) return
    val span = spanOf(e)
    val mx = (e.getX(0) + e.getX(1)) / 2f; val my = (e.getY(0) + e.getY(1)) / 2f
    if (!pinch.spreading && abs(span - pinch.span) > PINCH_SLOP) { pinch.spreading = true; moved = true; removeCallbacks(longPress) }
    pendingDx += mx - pinch.midX; pendingDy += my - pinch.midY
    pinch.midX = mx; pinch.midY = my
    if (throttle && e.eventTime - lastPanMillis < PAN_STEP_MILLIS) return
    lastPanMillis = e.eventTime
    val z = if (pinch.spreading) nearestZoom(pinch.zoom * span / pinch.span) else zoom
    if (z != zoom) { zoomTo(z, mx, my); pinch.zoomed = true }
    if (moved) applyPendingPan()
}

/** One of two fingers lifted: the last spread counts; what is left of the gesture no longer pans or zooms. */
private fun PageView.endPinch(e: MotionEvent) {
    pinchMove(e, throttle = false)
    pinch.midX = Float.NaN
    if (pinch.zoomed) { pinch.zoomed = false; onZoomed() }
}

internal fun PageView.fingerDown(e: MotionEvent) {
    downX = e.x; downY = e.y; downTime = e.eventTime; maxPointers = 1; moved = false; longPressed = false
    removeCallbacks(longPress); postDelayed(longPress, LONG_PRESS_MILLIS)
    lastX = e.x; lastY = e.y; pendingDx = 0f; pendingDy = 0f
}

internal fun PageView.fingerUp(e: MotionEvent) {
    removeCallbacks(longPress)
    when {
        longPressed -> Unit
        endless && moved && maxPointers == 1 -> applyPendingPan()
        else -> finishGesture(e)
    }
}

/** One finger drags the endless canvas; the picture follows at most once a frame. */
internal fun PageView.dragCanvas(e: MotionEvent) {
    pendingDx += e.x - lastX; pendingDy += e.y - lastY
    lastX = e.x; lastY = e.y
    if (moved && e.eventTime - lastPanMillis >= PAN_STEP_MILLIS) { lastPanMillis = e.eventTime; applyPendingPan() }
}

internal fun PageView.applyPendingPan() {
    val dx = pendingDx; val dy = pendingDy
    pendingDx = 0f; pendingDy = 0f
    panTo(panX - dx / zoom, panY - dy / zoom)
}

internal fun PageView.tappedMark(x: Float, y: Float): Boolean {
    val m = resumeMark ?: return false
    val p = com.nisaba.domain.shared.PagePoint(x, y).let { (it.x - affine.dx) / affine.scale to (it.y - affine.dy) / affine.scale }
    return kotlin.math.abs(p.first - m.x) < MARK_HIT && kotlin.math.abs(p.second - m.y) < MARK_HIT
}

internal fun PageView.finishGesture(e: MotionEvent) {
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
internal fun PageView.singleTap(x: Float, y: Float) {
    when {
        tappedMark(x, y) -> onMarkTap()
        isScribing() -> onSkipTap()
        downY < topStripPx -> onTopTap()
    }
}
