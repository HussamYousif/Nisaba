package com.nisaba.canvas

import android.graphics.Rect
import com.nisaba.canvas.PageView.Companion.MARK_REACH
import com.nisaba.domain.shared.PagePoint
import kotlin.math.ceil
import kotlin.math.floor

// Page-space boxes to the view pixels to repaint, and the resume mark.

internal fun PageView.dirtyOfView(b: com.nisaba.domain.shared.PageRect, pad: Float) =
    Rect(floor(b.left - pad).toInt(), floor(b.top - pad).toInt(), ceil(b.right + pad).toInt(), ceil(b.bottom + pad).toInt())

/** The lasso's box (page units), dashed around what it picked; null hides it. */
fun PageView.showSelection(bounds: com.nisaba.domain.shared.PageRect?) {
    if (bounds == selection) return
    val pad = PageView.SELECTION_PAD * affine.scale + SELECTION_STROKE
    selection?.let { invalidate(dirtyOf(it, pad)) }
    selection = bounds
    bounds?.let { invalidate(dirtyOf(it, pad)) }
}

private const val SELECTION_STROKE = 6f

fun PageView.showResumeMark(at: PagePoint?) {
    val old = resumeMark; resumeMark = at
    old?.let { invalidate(dirtyAround(it, MARK_REACH)) }
    at?.let { invalidate(dirtyAround(it, MARK_REACH)) }
}

internal fun PageView.dirtyAround(p: PagePoint, reach: Float): Rect {
    val cx = p.x * affine.scale + affine.dx; val cy = p.y * affine.scale + affine.dy
    val r = reach * affine.scale + 4f
    return Rect(floor(cx - r).toInt(), floor(cy - r).toInt(), ceil(cx + r).toInt(), ceil(cy + r).toInt())
}

internal fun PageView.dirtyOf(b: com.nisaba.domain.shared.PageRect, pad: Float): Rect = Rect(
    floor(b.left * affine.scale + affine.dx - pad).toInt(), floor(b.top * affine.scale + affine.dy - pad).toInt(),
    ceil(b.right * affine.scale + affine.dx + pad).toInt(), ceil(b.bottom * affine.scale + affine.dy + pad).toInt(),
)
