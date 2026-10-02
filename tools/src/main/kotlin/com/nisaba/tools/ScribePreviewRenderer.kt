package com.nisaba.tools

import com.nisaba.scribe.hand.PenProfile
import com.nisaba.scribe.hand.strokeOutline
import com.nisaba.scribe.motion.TimedStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Renders planned strokes exactly as the device would (same [strokeOutline]) onto a paper-coloured PNG. */
class ScribePreviewRenderer(private val pageW: Int, private val pageH: Int, private val scale: Float = 0.5f) {
    fun render(strokes: List<TimedStroke>, page: Int, ink: Color = Color(0x27, 0x40, 0x7A), cropBottom: Float? = null): BufferedImage {
        val h = ((cropBottom ?: pageH.toFloat()) * scale).toInt()
        val img = BufferedImage((pageW * scale).toInt(), h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0xF6, 0xF2, 0xEA); g.fillRect(0, 0, img.width, img.height)
        g.color = ink
        for (s in strokes.filter { it.page == page }) {
            val poly = strokeOutline(s.points, PenProfile.DEFAULT)
            if (poly.size < 4) continue
            val p = Path2D.Float()
            p.moveTo(poly[0] * scale, poly[1] * scale)
            var i = 2
            while (i < poly.size) { p.lineTo(poly[i] * scale, poly[i + 1] * scale); i += 2 }
            p.closePath(); g.fill(p)
        }
        g.dispose()
        return img
    }

    fun save(img: BufferedImage, file: File) { file.parentFile?.mkdirs(); ImageIO.write(img, "png", file) }
}
