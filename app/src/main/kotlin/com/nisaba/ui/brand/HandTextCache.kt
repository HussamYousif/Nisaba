package com.nisaba.ui.brand

import com.nisaba.domain.answer.Block
import com.nisaba.domain.answer.Inline
import com.nisaba.domain.settings.HandSpeed
import com.nisaba.domain.shared.PageSize
import com.nisaba.scribe.hand.GlyphSet
import com.nisaba.scribe.hand.HumanParams
import com.nisaba.scribe.hand.strokeOutline
import com.nisaba.scribe.layout.LayoutRegion
import com.nisaba.scribe.layout.OccupancyLookup
import com.nisaba.scribe.motion.ScribePlanUseCase
import com.nisaba.scribe.motion.TimedStroke
import com.nisaba.scribe.motion.speedProfile

/** A short text written by the hand: the timed strokes (to animate) and their full outlines (to draw statically). */
class HandText(
    val strokes: List<TimedStroke>,
    val outlines: List<FloatArray>,
    val minX: Float,
    val minY: Float,
    val width: Float,
    val height: Float,
) {
    val durationMs: Int get() = strokes.lastOrNull()?.endMs ?: 0
}

/** Lays out and remembers hand-written text for the wordmark, covers and hints (computed once per string). */
class HandTextCache(private val hand: () -> GlyphSet) {
    private val cache = HashMap<String, HandText>()

    @Synchronized
    fun of(text: String, lineWidth: Float = DEFAULT_WIDTH, xHeight: Float = DEFAULT_X_HEIGHT): HandText =
        cache.getOrPut("$lineWidth|$xHeight|$text") { build(text, lineWidth, xHeight) }

    private fun build(text: String, lineWidth: Float, xh: Float): HandText {
        val region = LayoutRegion(PageSize(lineWidth, LARGE), 0f, lineWidth, 0f, LARGE, xh)
        val plan = ScribePlanUseCase(
            hand(), region, OccupancyLookup { null }, text.hashCode().toLong(),
            HumanParams.CASUAL, speedProfile(HandSpeed.Swift),
        )
        val step = plan(plan.start(), Block.Paragraph(listOf(Inline.Text(text))))
        val strokes = step.strokes.filter { it.page == 0 }
        if (strokes.isEmpty()) return HandText(emptyList(), emptyList(), 0f, 0f, 1f, 1f)
        val minX = strokes.minOf { it.points.xs.min() } - PAD; val maxX = strokes.maxOf { it.points.xs.max() } + PAD
        val minY = strokes.minOf { it.points.ys.min() } - PAD; val maxY = strokes.maxOf { it.points.ys.max() } + PAD
        return HandText(strokes, strokes.map { strokeOutline(it.points) }, minX, minY, maxX - minX, maxY - minY)
    }

    private companion object {
        const val DEFAULT_WIDTH = 900f
        const val DEFAULT_X_HEIGHT = 44f
        const val LARGE = 4000f
        const val PAD = 6f
    }
}
