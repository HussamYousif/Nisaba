package com.nisaba.render

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SVG-subset → ink items (docs/08 §4): element coverage, fill/hatching,
 * transforms, and failure → null (smudge placeholder).
 */
@RunWith(AndroidJUnit4::class)
class SvgInkRendererTest {

    private val sample = """
        <svg viewBox="0 0 1000 1000">
          <path d="M100 100 C200 50 300 150 400 100" stroke="#3A2E25" stroke-width="6"/>
          <polyline points="100,200 200,300 300,200"/>
          <line x1="100" y1="400" x2="300" y2="400"/>
          <rect x="400" y="400" width="100" height="80" fill="#3A2E25"/>
          <circle cx="600" cy="400" r="50" opacity="0.35"/>
          <ellipse cx="800" cy="400" rx="60" ry="30" transform="translate(10 10)"/>
        </svg>
    """.trimIndent()

    @Test
    fun parsesAllSupportedElements() {
        val items = SvgInkRenderer.parse(sample)!!
        assertEquals(6, items.size)
        for (item in items) {
            assertTrue("length ${item.length}", item.length > 0f)
        }
    }

    @Test
    fun fillAndHatchingFlags() {
        val items = SvgInkRenderer.parse(sample)!!
        assertTrue(items[3].fill)                    // rect with fill
        assertEquals(false, items[0].fill)           // stroked path
        assertEquals(89, items[4].paint.alpha)       // opacity 0.35 → hatching
        assertEquals(255, items[0].paint.alpha)      // opacity 1.0
    }

    @Test
    fun transformIsApplied() {
        val items = SvgInkRenderer.parse(sample)!!
        val bounds = android.graphics.RectF()
        items[5].path.computeBounds(bounds, true)
        // ellipse at (800,400) + translate(10,10) → center ≈ (810,410)
        assertEquals(810f, bounds.centerX(), 1f)
        assertEquals(410f, bounds.centerY(), 1f)
    }

    @Test
    fun malformedSvg_returnsNull() {
        assertNull(SvgInkRenderer.parse("<svg></svg>"))            // no drawable elements
        assertNull(SvgInkRenderer.parse("<path d='M0 0 L10 10"))   // broken XML
        assertNull(SvgInkRenderer.parse("not svg at all"))
    }

    @Test
    fun unknownElements_areIgnored() {
        val items = SvgInkRenderer.parse(
            """<svg viewBox="0 0 1000 1000"><text>hi</text>
               <path d="M0 0 L100 100" stroke-width="5"/></svg>"""
        )!!
        assertEquals(1, items.size)
    }
}
