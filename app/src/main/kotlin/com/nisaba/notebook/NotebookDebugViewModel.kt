package com.nisaba.notebook

import com.nisaba.device.PenEvent
import com.nisaba.domain.answer.Block
import com.nisaba.domain.answer.Inline
import com.nisaba.domain.ink.InkPoints
import com.nisaba.domain.settings.HandSpeed
import com.nisaba.domain.settings.HandStyle
import com.nisaba.notebook.NotebookViewModel.Companion.DEBUG_GAP
import com.nisaba.notebook.NotebookViewModel.Companion.DEBUG_LEFT
import com.nisaba.notebook.NotebookViewModel.Companion.DEBUG_ROOM
import com.nisaba.notebook.NotebookViewModel.Companion.DEBUG_SEED
import com.nisaba.notebook.NotebookViewModel.Companion.DEBUG_STROKE_GAP
import com.nisaba.notebook.NotebookViewModel.Companion.DEBUG_TOP
import com.nisaba.notebook.NotebookViewModel.Companion.DEBUG_X_HEIGHT
import com.nisaba.scribe.hand.HumanParams
import com.nisaba.scribe.layout.LayoutRegion
import com.nisaba.scribe.layout.OccupancyLookup
import com.nisaba.scribe.motion.ScribePlanUseCase
import com.nisaba.scribe.motion.speedProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch


// Debug harness only (driven by adb extras in debug builds).

/**
 * Debug harness: the app's own hand writes [text] as if the user had handwritten it, one pen event per stroke; a
 * literal backslash-n starts a new line.
 * Lets the whole ask loop be exercised end to end without a stylus.
 */
fun NotebookViewModel.debugWriteAsUser(text: String) {
    ownScope.launch {
        val page = currentPage()?.size ?: return@launch
        val originX = viewOrigin?.x ?: 0f; val originY = viewOrigin?.y ?: 0f
        // below whatever is on the page already (a follow-up), else near the top
        val top = edit?.ink?.strokes?.maxOfOrNull { it.bounds.bottom }?.plus(DEBUG_GAP)?.coerceAtMost(page.h - DEBUG_ROOM)
            ?: (DEBUG_TOP + originY)
        val planner = ScribePlanUseCase(
            deps.hands(HandStyle.Casual), LayoutRegion.below(page, DEBUG_LEFT + originX, top, DEBUG_X_HEIGHT),
            OccupancyLookup { null },
            DEBUG_SEED, HumanParams.CASUAL, speedProfile(HandSpeed.Swift),
        )
        // a literal "\n" starts a new line (a worked solution to check, say)
        var state = planner.start()
        val strokes = text.split("\\n").flatMap { line ->
            planner(state, Block.Paragraph(listOf(Inline.Text(line.trim())))).also { state = it.state }.strokes
        }
        for (s in strokes.filter { it.page == 0 }) {
            val p = s.points
            val t0 = p.ts.first()
            onPenEvent(PenEvent.PenDown(0f, 0f))
            val xs = FloatArray(p.size) { p.xs[it] * affine.scale + affine.dx }
            val ys = FloatArray(p.size) { p.ys[it] * affine.scale + affine.dy }
            onPenEvent(PenEvent.StrokeDone(InkPoints(xs, ys, p.ps, IntArray(p.size) { p.ts[it] - t0 })))
            delay(DEBUG_STROKE_GAP)
        }
    }
}
