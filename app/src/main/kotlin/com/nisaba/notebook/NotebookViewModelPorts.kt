package com.nisaba.notebook

import com.nisaba.domain.conversation.ConversationContextPorts
import com.nisaba.domain.conversation.ConversationSession
import com.nisaba.domain.conversation.ConversationSurface
import com.nisaba.domain.conversation.Exchange
import com.nisaba.domain.ink.InkUseCases
import com.nisaba.domain.lesson.LessonTopic
import com.nisaba.domain.notebook.InsertPageUseCase
import com.nisaba.domain.notebook.ObserveNotebookUseCase
import com.nisaba.domain.notebook.Page
import com.nisaba.domain.notebook.RenameNotebookUseCase
import com.nisaba.domain.notebook.SetListeningUseCase
import com.nisaba.domain.notebook.SetPersonaUseCase
import com.nisaba.domain.settings.HandStyle
import com.nisaba.domain.settings.PenSize
import com.nisaba.domain.settings.Settings
import com.nisaba.domain.shared.ExchangeId
import com.nisaba.domain.shared.IdGen
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PagePoint
import com.nisaba.scribe.hand.GlyphSet
import com.nisaba.scribe.layout.Occupancy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first


/** Builds the ask loop for one page; supplied by the composition root (null in plain-ink tests). */
fun interface SessionFactory {
    fun create(
        surface: ConversationSurface,
        scope: CoroutineScope,
        settings: Settings,
        listening: Boolean,
        context: ConversationContextPorts,
    ): ConversationSession
}

class NotebookDeps(
    val notebookId: NotebookId,
    val observe: ObserveNotebookUseCase,
    val insertPage: InsertPageUseCase,
    val rename: RenameNotebookUseCase,
    val ink: InkUseCases,
    val setListening: SetListeningUseCase,
    val idGen: IdGen,
    val settings: Flow<Settings> = emptyFlow(),
    /** Where the view of an endless canvas was last left (page space), and the way to remember it. */
    val memory: NotebookMemory = NotebookMemory(),
    val sessions: SessionFactory? = null,
    val hands: (HandStyle) -> GlyphSet = { error("no hand configured") },
    val sources: NotebookSources = NotebookSources(),
    val changes: NotebookChanges = NotebookChanges(),
)

/** Changes to the notebook itself made from inside it. */
class NotebookChanges(
    /** The voice Nisaba answers in here (null: the voice can't be changed, e.g. in plain-ink tests). */
    val setPersona: SetPersonaUseCase? = null,
    /** Marks the notebook as just written in (the library's order). */
    val touch: suspend (NotebookId) -> Unit = {},
    /** When Nisaba answers here and how long its answers are. */
    val setStyle: suspend (NotebookId, com.nisaba.domain.notebook.AnswerStyle) -> Unit = { _, _ -> },
    /** An answer was erased from the page: it leaves the conversation's history. */
    val markErased: suspend (com.nisaba.domain.shared.ExchangeId) -> Unit = {},
)

/** What answers draw on besides the ink: the document, its printed text, saved exchanges. */
class NotebookSources(
    /** Text of the notebook's document, one string per page (empty for plain notebooks). */
    val documentPages: suspend () -> List<String> = { emptyList() },
    /** Occupancy of what is printed on a document page, so answers avoid it (null for blank pages). */
    val backgroundOccupancy: suspend (Page) -> Occupancy? = { null },
    /** A saved exchange (to ask it again). */
    val exchange: suspend (ExchangeId) -> Exchange? = { null },
    /** A lesson notebook's progress as the tutor records it. */
    val lessonTopics: Flow<List<LessonTopic>> = emptyFlow(),
    /** Whether the device has a network; a question that failed offline is asked again when it comes back. */
    val online: Flow<Boolean> = emptyFlow(),
)

/** What the app remembers for the user between visits: the first-run hint, where each endless canvas was left, the pen. */
class NotebookMemory(
    val markHintSeen: suspend () -> Unit = {},
    val loadView: (PageId) -> PagePoint? = { null },
    val saveView: (PageId, PagePoint) -> Unit = { _, _ -> },
    val savePen: suspend (ink: Int, size: PenSize) -> Unit = { _, _ -> },
    /** The page a notebook was left on, to open it there next time. */
    val lastPage: (NotebookId) -> PageId? = { null },
    val saveLastPage: (NotebookId, PageId) -> Unit = { _, _ -> },
    /** A page's unanswered question, kept across page turns and restarts (null clears it). */
    val loadPending: (PageId) -> com.nisaba.domain.conversation.Utterance? = { null },
    val savePending: (PageId, com.nisaba.domain.conversation.Utterance?) -> Unit = { _, _ -> },
)
