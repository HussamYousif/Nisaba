package com.nisaba.infra.inkfile

import com.nisaba.domain.ink.InkColor
import com.nisaba.domain.ink.InkPoints
import com.nisaba.domain.ink.Stroke
import com.nisaba.domain.ink.StrokeOrigin
import com.nisaba.domain.shared.ExchangeId
import com.nisaba.domain.shared.StrokeId

private const val ORIGIN_USER = 0
private const val ORIGIN_ORACLE = 1

fun Stroke.toDto() = StrokeDto(
    id = id.value,
    origin = if (origin is StrokeOrigin.Oracle) ORIGIN_ORACLE else ORIGIN_USER,
    exchangeId = (origin as? StrokeOrigin.Oracle)?.exchangeId?.value.orEmpty(),
    color = color.argb, width = width,
    xs = points.xs, ys = points.ys, ps = points.ps, ts = points.ts,
)

fun StrokeDto.toModel() = Stroke(
    id = StrokeId(id),
    points = InkPoints(xs, ys, ps, ts),
    width = width,
    color = InkColor(color),
    origin = if (origin == ORIGIN_ORACLE) StrokeOrigin.Oracle(ExchangeId(exchangeId)) else StrokeOrigin.User,
)

fun List<Stroke>.toFileDto() = InkFileDto(strokes = map { it.toDto() })

fun InkFileDto.toModels(): List<Stroke> = strokes.map { it.toModel() }
