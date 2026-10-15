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
import com.nisaba.canvas.PageView.Companion.SWIPE_MIN
import com.nisaba.canvas.PageView.Companion.TAP_MILLIS
import com.nisaba.domain.shared.PagePoint
import com.nisaba.domain.shared.PageSize
import com.nisaba.infra.render.BackgroundRenderer
import kotlin.math.abs

// Finger gestures on the page (turn, undo/redo taps, long press, skip, the resume mark),
// and the endless canvas following the finger or the hand.

/** Moves the endless canvas under the view by shifting the picture and painting only the newly exposed strips. */
internal fun PageView.panTo(nx: Float, ny: Float) {
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
    if (y - panY > height * FOLLOW_EDGE) panTo(panX, y - height * FOLLOW_TARGET)
}

/** Shows the endless canvas from [origin] (page space): a jump, so the whole picture is redrawn. */
fun PageView.jumpTo(origin: PagePoint) { if (endless) panTo(origin.x, origin.y) }

/** The answer will start at [at]: nothing is shown while the model thinks, but an endless canvas pans there. */
fun PageView.prepareAnswerAt(at: PagePoint) {
    if (!endless || width <= 0) return
    val vx = at.x - panX; val vy = at.y - panY
    val offSideways = vx < 0 || vx > width - MIN_VISIBLE_WIDTH
    if (offSideways || vy < 0 || vy > height * FOLLOW_EDGE) {
        panTo(if (offSideways) at.x - ENDLESS_LEFT_MARGIN else panX, at.y - height * FOLLOW_TARGET)
    }
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

/** One finger drags the endless canvas; the picture follows in discrete steps (e-ink), not every move event. */
internal fun PageView.dragCanvas(e: MotionEvent) {
    pendingDx += e.x - lastX; pendingDy += e.y - lastY
    lastX = e.x; lastY = e.y
    if (moved && e.eventTime - lastPanMillis >= PAN_STEP_MILLIS) { lastPanMillis = e.eventTime; applyPendingPan() }
}

internal fun PageView.applyPendingPan() {
    val dx = pendingDx; val dy = pendingDy
    pendingDx = 0f; pendingDy = 0f
    panTo(panX - dx, panY - dy)
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
