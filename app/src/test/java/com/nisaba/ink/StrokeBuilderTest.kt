package com.nisaba.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StrokeBuilder (docs/04 §5): raw stylus input → Stroke. The preview snapshot
 * shares one placeholder id (it is only drawn, never stored); a committed
 * stroke must carry a unique id — undo/redo and the eraser match strokes by
 * id, and "s-preview" for every stroke made one Undo drop the whole page
 * (audit P0-1).
 */
class StrokeBuilderTest {

    private fun inked(): StrokeBuilder = StrokeBuilder().apply {
        start(10f, 10f, 0.5f, 0L, erasing = false)
        append(50f, 60f, 0.8f, 100L)
        append(90f, 40f, 0.9f, 200L)
    }

    @Test
    fun build_assignsAUniqueId() {
        val a = inked().build()!!
        val b = inked().build()!!
        assertTrue("id must look like a stroke id", a.id.startsWith("s-"))
        assertNotEquals("every committed stroke needs its own id (audit P0-1)", a.id, b.id)
    }

    @Test
    fun build_neverReturnsThePreviewPlaceholderId() {
        val s = inked().build()!!
        assertNotEquals("s-preview", s.id)
        assertEquals("pen", s.tool)
        assertEquals(3, s.points.size)
    }

    @Test
    fun preview_keepsItsPlaceholderId() {
        // The onDraw snapshot is not persisted; its id must never leak into a
        // committed stroke, but the preview itself is free to share one.
        val b = inked()
        assertEquals("s-preview", b.buildPreview()!!.id)
    }

    @Test
    fun build_isConsuming_andTooShortReturnsNull() {
        val b = inked()
        assertTrue(b.build() != null)
        assertNull("build() consumes the stroke", b.buildPreview())

        val short = StrokeBuilder().apply { start(1f, 1f, 0.5f, 0L, erasing = false) }
        assertNull(short.build()) // fewer than 2 points
    }

    @Test
    fun cancel_clearsTheStroke() {
        val b = inked()
        b.cancel()
        assertNull(b.buildPreview())
    }
}
