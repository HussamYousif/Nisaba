package com.nisaba.domain.shared

import kotlin.math.max
import kotlin.math.min

fun PageRect.union(o: PageRect) = PageRect(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))

fun PageRect.inflate(d: Float) = PageRect(left - d, top - d, right + d, bottom + d)

fun PageRect.intersects(o: PageRect) = left <= o.right && o.left <= right && top <= o.bottom && o.top <= bottom

fun PageRect.contains(x: Float, y: Float) = x in left..right && y in top..bottom

fun PageRect.contains(p: PagePoint) = contains(p.x, p.y)

/** Fits [page] inside a view of [viewW]×[viewH], preserving aspect ratio, centred. */
fun fitTransform(page: PageSize, viewW: Float, viewH: Float): Affine {
    val scale = min(viewW / page.w, viewH / page.h)
    return Affine(scale, (viewW - page.w * scale) / 2f, (viewH - page.h * scale) / 2f)
}

fun Affine.toView(x: Float, y: Float) = PagePoint(x * scale + dx, y * scale + dy)

fun Affine.toPage(vx: Float, vy: Float) = PagePoint((vx - dx) / scale, (vy - dy) / scale)

fun <T> T?.orNotFound(): Outcome<T> = if (this == null) Outcome.Err(DomainError.NotFound) else Outcome.Ok(this)
