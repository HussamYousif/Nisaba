package com.nisaba.domain.ink

import com.nisaba.domain.shared.StrokeId

/** Straight horizontal stroke at [y] from [x0] to [x1] with a point every 10 units. */
fun lineStroke(id: String, x0: Float, x1: Float, y: Float, width: Float = 4f): Stroke {
    val n = ((x1 - x0) / 10f).toInt() + 1
    return Stroke(
        StrokeId(id),
        InkPoints(FloatArray(n) { x0 + it * 10f }, FloatArray(n) { y }, FloatArray(n) { 0.5f }, IntArray(n) { it * 8 }),
        width, InkColor.BLACK, StrokeOrigin.User,
    )
}

/** A stroke whose bounding box is [h] tall (vertical zig). */
fun tallStroke(id: String, x: Float, y: Float, h: Float) = Stroke(
    StrokeId(id),
    InkPoints(floatArrayOf(x, x + 2f, x), floatArrayOf(y, y + h / 2f, y + h), floatArrayOf(.5f, .5f, .5f), intArrayOf(0, 8, 16)),
    3f, InkColor.BLACK, StrokeOrigin.User,
)
