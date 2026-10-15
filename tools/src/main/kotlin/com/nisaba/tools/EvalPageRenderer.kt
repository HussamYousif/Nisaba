package com.nisaba.tools

import com.nisaba.domain.answer.Block
import com.nisaba.domain.answer.Inline
import com.nisaba.domain.conversation.PageSnapshot
import com.nisaba.domain.settings.HandSpeed
import com.nisaba.domain.shared.PageSize
import com.nisaba.scribe.hand.GlyphSet
import com.nisaba.scribe.hand.HumanParams
import com.nisaba.scribe.layout.LayoutRegion
import com.nisaba.scribe.layout.OccupancyLookup
import com.nisaba.scribe.motion.ScribePlanUseCase
import com.nisaba.scribe.motion.speedProfile
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * A page with [text] "handwritten" by the app's own hand in black, sent the way the app sends it: the whole page at
 * 1600 px on its long side, and the writing's crop (+10 %) at full size, both colour JPEG q85.
 */
class EvalPageRenderer(private val hand: GlyphSet) {
    private val page = PageSize.PORTRAIT

    fun snapshot(text: String, seed: Long): PageSnapshot {
        val plan = ScribePlanUseCase(
            hand, LayoutRegion.below(page, LEFT, TOP, X_HEIGHT), OccupancyLookup { null }, seed, HumanParams.CASUAL,
            speedProfile(HandSpeed.Swift),
        )
        val strokes = plan(plan.start(), Block.Paragraph(listOf(Inline.Text(text)))).strokes.filter { it.page == 0 }
        val full = ScribePreviewRenderer(page.w.toInt(), page.h.toInt(), 1f).render(strokes, 0, Color(0x11, 0x11, 0x11))
        val xs = strokes.flatMap { s -> (0 until s.points.size).map { s.points.xs[it] } }
        val ys = strokes.flatMap { s -> (0 until s.points.size).map { s.points.ys[it] } }
        val margin = ((ys.max() - ys.min()).coerceAtLeast(MIN_FOCUS) * FOCUS_MARGIN).toInt()
        val l = (xs.min().toInt() - margin).coerceAtLeast(0); val t = (ys.min().toInt() - margin).coerceAtLeast(0)
        val r = (xs.max().toInt() + margin).coerceAtMost(full.width); val b = (ys.max().toInt() + margin).coerceAtMost(full.height)
        val focus = full.getSubimage(l, t, r - l, b - t)
        val k = LONG_EDGE / page.h
        val small = BufferedImage((page.w * k).toInt(), (page.h * k).toInt(), BufferedImage.TYPE_INT_RGB)
        small.createGraphics().apply { drawImage(full, 0, 0, small.width, small.height, null); dispose() }
        return PageSnapshot(jpeg(small), jpeg(copy(focus)))
    }

    private fun copy(img: BufferedImage) = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB).also {
        it.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
    }

    private fun jpeg(img: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = QUALITY }
        ImageIO.createImageOutputStream(out).use { ios -> writer.output = ios; writer.write(null, IIOImage(img, null, null), param) }
        writer.dispose()
        return out.toByteArray()
    }

    private companion object {
        const val LEFT = 200f
        const val TOP = 320f
        const val X_HEIGHT = 48f
        const val LONG_EDGE = 1600f
        const val MIN_FOCUS = 100f
        const val FOCUS_MARGIN = 0.1f
        const val QUALITY = 0.85f
    }
}
