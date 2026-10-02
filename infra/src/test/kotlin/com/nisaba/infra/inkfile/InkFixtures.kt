package com.nisaba.infra.inkfile

import com.nisaba.domain.ink.InkColor
import com.nisaba.domain.ink.InkPoints
import com.nisaba.domain.ink.Stroke
import com.nisaba.domain.ink.StrokeOrigin
import com.nisaba.domain.shared.ExchangeId
import com.nisaba.domain.shared.StrokeId

fun sampleStroke(id: String, seed: Int, oracle: Boolean = false): Stroke {
    val n = 40
    return Stroke(
        StrokeId(id),
        InkPoints(
            FloatArray(n) { seed + it * 3.5f }, FloatArray(n) { seed * 2f + it },
            FloatArray(n) { it / n.toFloat() }, IntArray(n) { it * 8 },
        ),
        width = 3.5f,
        color = if (oracle) InkColor.LAPIS else InkColor.BLACK,
        origin = if (oracle) StrokeOrigin.Oracle(ExchangeId("ex-$seed")) else StrokeOrigin.User,
    )
}
