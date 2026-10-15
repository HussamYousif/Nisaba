package com.nisaba.notebook

import com.nisaba.device.PenTool
import com.nisaba.domain.ink.ShapeKind
import com.nisaba.domain.ink.Stroke
import com.nisaba.domain.settings.AnswerInk
import com.nisaba.domain.settings.CodeHighlight
import com.nisaba.domain.settings.PenSize
import com.nisaba.domain.lesson.LessonTopic
import com.nisaba.domain.notebook.PageBackground
import com.nisaba.domain.persona.PersonaRegistry
import com.nisaba.domain.shared.ExchangeId
import com.nisaba.domain.shared.PagePoint
import com.nisaba.domain.shared.PageSize

data class NotebookUiState(
    val title: String = "",
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    val pageSize: PageSize = PageSize.PORTRAIT,
    /** The page on screen (null until a notebook is open). */
    val pageId: com.nisaba.domain.shared.PageId? = null,
    val background: PageBackground = PageBackground.Blank,
    val strokes: List<Stroke> = emptyList(),
    val tool: PenTool = PenTool.Pen,
    /** The user's pen (ARGB, thickness) and what the shape tool draws; [toolPanel] = the pen's or shapes' picker. */
    val penInk: Int = 0xFF111111.toInt(),
    val penSize: PenSize = PenSize.Medium,
    val shape: ShapeKind = ShapeKind.Auto,
    val toolPanel: ToolPanel? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val toolbarVisible: Boolean = true,
    val listening: Boolean = true,
    val loading: Boolean = true,
    val renaming: Boolean = false,
    /** One line in the notebook's voice when the last request failed (tap to retry). */
    val failure: String? = null,
    /** "Remembered: …" for a few seconds after a reply taught Nisaba something. */
    val memoryNote: String? = null,
    /** Something about this page worth one line until tapped away (e.g. ink that couldn't be read was kept aside). */
    val notice: String? = null,
    /** Where a stopped answer can be continued ("…" mark), if any. */
    val resumeMark: PagePoint? = null,
    val answerColor: Int = 0xFF27407A.toInt(),
    val answerInk: AnswerInk = AnswerInk.Lapis,
    val codeHighlight: CodeHighlight = CodeHighlight.Colour,
    val aiAvailable: Boolean = false,
    /** The first-ever blank page shows a hand-written hint until the first stroke. */
    val hintPending: Boolean = false,
    /** An endless canvas: no pages, the view pans. [viewOrigin] = where the view starts (page space), when remembered. */
    val endless: Boolean = false,
    val viewOrigin: PagePoint? = null,
    /** A finger is held on an answer: what can be done with it. */
    val answerMenu: AnswerMenu? = null,
    /** The endless canvas zoomed out to fit (until a spot is picked). */
    val overview: Boolean = false,
    /** Nisaba teaches this notebook's document; [lessonTopics] = what it has recorded, shown when [progressOpen]. */
    val lesson: Boolean = false,
    val lessonTopics: List<LessonTopic> = emptyList(),
    val progressOpen: Boolean = false,
    /** The toolbar's ⋯ menu is open (the bar stays up meanwhile). */
    val menuOpen: Boolean = false,
    /** The page grid is open, showing screenful [pageGridScreen]. */
    val pageGridOpen: Boolean = false,
    val pageGridScreen: Int = 0,
    /** There is writing Nisaba has not been asked about yet (Ask now would send it). */
    val askable: Boolean = false,
    /** Nisaba is thinking about or writing an answer (the corner star says so while the bar is hidden). */
    val working: Boolean = false,
    /** The voice Nisaba answers in here; [voiceOpen] = its picker is up. */
    val personaId: String = PersonaRegistry.DEFAULT_ID,
    val style: com.nisaba.domain.notebook.AnswerStyle = com.nisaba.domain.notebook.AnswerStyle(),
    val voiceOpen: Boolean = false,
) {
    val showHint: Boolean get() = hintPending && !loading && strokes.isEmpty() && background is PageBackground.Blank

    /** A menu or picker the toolbar opened is up (the bar stays meanwhile). */
    val pickerOpen: Boolean get() = menuOpen || pageGridOpen || toolPanel != null

    /** Anything drawn over the page, where the pen must not ink. */
    val overlayOpen: Boolean get() = pickerOpen || overview || answerMenu != null || progressOpen || renaming || voiceOpen
}

enum class ToolPanel { Pen, Shapes }

/** [pickVoice] = choosing the voice for a second opinion ([otherVoice] = the one picked first). */
data class AnswerMenu(
    val exchangeId: ExchangeId,
    val canAskAgain: Boolean,
    val pickVoice: Boolean = false,
    val otherVoice: String = "teacher",
)

/** One-shot instructions for the view layer. */
sealed interface NotebookEffect {
    /** Ask the panel for a clean pass ([full] = flashing full-screen refresh). */
    data class Refresh(val full: Boolean) : NotebookEffect

    /** Show the endless canvas from [origin] (page space, the view's top-left). */
    data class PanTo(val origin: PagePoint) : NotebookEffect

    /** Repaint the page from its committed ink (live ink drawn under the pen was replaced, e.g. by a clean shape). */
    data object Repaint : NotebookEffect

    /** Put [text] on the clipboard. */
    data class Copy(val text: String) : NotebookEffect

    /** Offer [text] to another app (the share sheet). */
    data class Share(val subject: String, val text: String) : NotebookEffect
}
