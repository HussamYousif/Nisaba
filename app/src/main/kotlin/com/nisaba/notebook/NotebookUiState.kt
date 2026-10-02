package com.nisaba.notebook

import com.nisaba.device.PenTool
import com.nisaba.domain.ink.Stroke
import com.nisaba.domain.notebook.PageBackground
import com.nisaba.domain.shared.PagePoint
import com.nisaba.domain.shared.PageSize

data class NotebookUiState(
    val title: String = "",
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    val pageSize: PageSize = PageSize.PORTRAIT,
    val background: PageBackground = PageBackground.Blank,
    val strokes: List<Stroke> = emptyList(),
    val tool: PenTool = PenTool.Pen,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val toolbarVisible: Boolean = true,
    val listening: Boolean = true,
    val loading: Boolean = true,
    val renaming: Boolean = false,
    /** One line in the notebook's voice when the last request failed (tap to retry). */
    val failure: String? = null,
    /** Where a stopped answer can be continued ("…" mark), if any. */
    val resumeMark: PagePoint? = null,
    val answerColor: Int = 0xFF27407A.toInt(),
    val aiAvailable: Boolean = false,
    /** The first-ever blank page shows a hand-written hint until the first stroke. */
    val hintPending: Boolean = false,
    /** An endless canvas: no pages, the view pans. [viewOrigin] = where the view starts (page space), when remembered. */
    val endless: Boolean = false,
    val viewOrigin: PagePoint? = null,
) {
    val showHint: Boolean get() = hintPending && !loading && strokes.isEmpty() && background is PageBackground.Blank
}

/** One-shot instructions for the view layer. */
sealed interface NotebookEffect {
    /** Ask the panel for a clean pass ([full] = flashing full-screen refresh). */
    data class Refresh(val full: Boolean) : NotebookEffect
}
