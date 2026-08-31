package com.nisaba.render

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.util.Xml
import com.nisaba.magic.MagicTheme
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * SVG-subset → ink drawing items (docs/08 §4). The model is instructed to
 * produce only: <path>, <polyline>, <line>, <rect>, <circle>, <ellipse> with
 * stroke/fill in the ink colour, opacity 1.0/0.35, translate/scale transforms.
 * Anything else is ignored; any parse failure returns null and the caller
 * shows the smudge placeholder.
 */
object SvgInkRenderer {

    data class DrawItem(val path: Path, val paint: Paint, val length: Float, val fill: Boolean)

    const val VIEWBOX = 1000f
    const val MAX_ELEMENTS = 40

    fun parse(svg: String): List<DrawItem>? = runCatching {
        val items = ArrayList<DrawItem>()
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setInput(StringReader(svg))
        var event = parser.eventType
        var count = 0
        while (event != XmlPullParser.END_DOCUMENT && count <= MAX_ELEMENTS) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "path" -> {
                        val d = parser.getAttributeValue(null, "d") ?: return null
                        val cmds = SvgPathParser.parse(d) ?: return null
                        val path = buildPath(cmds) ?: return null
                        items.add(item(path, parser))
                    }
                    "polyline" -> {
                        val pts = parser.getAttributeValue(null, "points") ?: return null
                        val cmds = pointsToCmds(pts) ?: return null
                        val path = buildPath(cmds) ?: return null
                        items.add(item(path, parser))
                    }
                    "line" -> {
                        val x1 = num(parser, "x1") ?: return null
                        val y1 = num(parser, "y1") ?: return null
                        val x2 = num(parser, "x2") ?: return null
                        val y2 = num(parser, "y2") ?: return null
                        val path = buildPath(
                            listOf(SvgPathParser.Cmd('M', floatArrayOf(x1, y1)), SvgPathParser.Cmd('L', floatArrayOf(x2, y2)))
                        ) ?: return null
                        items.add(item(path, parser))
                    }
                    "rect" -> {
                        val x = num(parser, "x") ?: 0f
                        val y = num(parser, "y") ?: 0f
                        val w = num(parser, "width") ?: return null
                        val h = num(parser, "height") ?: return null
                        val path = Path().apply { addRect(x, y, x + w, y + h, Path.Direction.CW) }
                        items.add(item(path, parser))
                    }
                    "circle" -> {
                        val cx = num(parser, "cx") ?: 0f
                        val cy = num(parser, "cy") ?: 0f
                        val r = num(parser, "r") ?: return null
                        val path = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
                        items.add(item(path, parser))
                    }
                    "ellipse" -> {
                        val cx = num(parser, "cx") ?: 0f
                        val cy = num(parser, "cy") ?: 0f
                        val rx = num(parser, "rx") ?: return null
                        val ry = num(parser, "ry") ?: return null
                        val path = Path().apply {
                            addOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), Path.Direction.CW)
                        }
                        items.add(item(path, parser))
                    }
                }
                count++
            }
            event = parser.next()
        }
        if (items.isEmpty()) null else items
    }.getOrNull()

    // ------------------------------------------------------------ pieces

    private fun item(path: Path, parser: XmlPullParser): DrawItem {
        val strokeWidth = (num(parser, "stroke-width") ?: 6f).coerceIn(4f, 10f)
        val opacity = num(parser, "opacity") ?: 1f
        val fillAttr = parser.getAttributeValue(null, "fill")
        val fill = fillAttr != null && fillAttr != "none"

        // transform (translate/scale) applies to the path, then we measure.
        parser.getAttributeValue(null, "transform")?.let { t ->
            parseTransform(t)?.let { path.transform(it) }
        }
        val length = pathLength(path)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = MagicTheme.INK
            alpha = if (opacity < 0.6f) 89 else 255 // 0.35 hatching vs 1.0
        }
        return DrawItem(path, paint, length, fill)
    }

    private fun parseTransform(t: String): Matrix? = runCatching {
        val m = Matrix()
        val re = Regex("""(translate|scale)\s*\(\s*([-\d.]+)\s*[, ]\s*([-\d.]+)\s*\)""")
        for (match in re.findAll(t)) {
            val a = match.groupValues[2].toFloat()
            val b = match.groupValues[3].toFloat()
            when (match.groupValues[1]) {
                "translate" -> m.postTranslate(a, b)
                "scale" -> m.postScale(a, b)
            }
        }
        m
    }.getOrNull()

    private fun num(parser: XmlPullParser, name: String): Float? =
        parser.getAttributeValue(null, name)?.toFloatOrNull()

    private fun pointsToCmds(points: String): List<SvgPathParser.Cmd>? {
        val nums = points.trim().split(Regex("[\\s,]+")).filter { it.isNotBlank() }
        if (nums.size < 4 || nums.size % 2 != 0) return null
        val floats = nums.mapNotNull { it.toFloatOrNull() }
        if (floats.size != nums.size) return null
        val cmds = ArrayList<SvgPathParser.Cmd>()
        cmds.add(SvgPathParser.Cmd('M', floatArrayOf(floats[0], floats[1])))
        for (i in 2 until floats.size step 2) {
            cmds.add(SvgPathParser.Cmd('L', floatArrayOf(floats[i], floats[i + 1])))
        }
        return cmds
    }

    /** Commands → Path (relative support). Returns null on malformed input. */
    private fun buildPath(cmds: List<SvgPathParser.Cmd>): Path? {
        val path = Path()
        var cx = 0f
        var cy = 0f
        var startX = 0f
        var startY = 0f
        for (cmd in cmds) {
            val a = cmd.args
            val rel = cmd.op.isLowerCase()
            when (cmd.op.uppercaseChar()) {
                'M' -> {
                    if (a.size < 2) return null
                    cx = if (rel) cx + a[0] else a[0]
                    cy = if (rel) cy + a[1] else a[1]
                    startX = cx; startY = cy
                    path.moveTo(cx, cy)
                }
                'L' -> {
                    if (a.size < 2) return null
                    cx = if (rel) cx + a[0] else a[0]
                    cy = if (rel) cy + a[1] else a[1]
                    path.lineTo(cx, cy)
                }
                'H' -> {
                    cx = if (rel) cx + a[0] else a[0]
                    path.lineTo(cx, cy)
                }
                'V' -> {
                    cy = if (rel) cy + a[0] else a[0]
                    path.lineTo(cx, cy)
                }
                'C' -> {
                    if (a.size < 6) return null
                    val c1x = if (rel) cx + a[0] else a[0]
                    val c1y = if (rel) cy + a[1] else a[1]
                    val c2x = if (rel) cx + a[2] else a[2]
                    val c2y = if (rel) cy + a[3] else a[3]
                    cx = if (rel) cx + a[4] else a[4]
                    cy = if (rel) cy + a[5] else a[5]
                    path.cubicTo(c1x, c1y, c2x, c2y, cx, cy)
                }
                'Q' -> {
                    if (a.size < 4) return null
                    val c1x = if (rel) cx + a[0] else a[0]
                    val c1y = if (rel) cy + a[1] else a[1]
                    cx = if (rel) cx + a[2] else a[2]
                    cy = if (rel) cy + a[3] else a[3]
                    path.quadTo(c1x, c1y, cx, cy)
                }
                'Z' -> {
                    path.close()
                    cx = startX; cy = startY
                }
                else -> return null
            }
        }
        return path
    }

    /** Total length across all contours (for the ink-draw reveal timing). */
    private fun pathLength(path: Path): Float {
        val measure = PathMeasure(path, false)
        var total = measure.length
        while (measure.nextContour()) {
            total += measure.length
        }
        return total
    }
}
