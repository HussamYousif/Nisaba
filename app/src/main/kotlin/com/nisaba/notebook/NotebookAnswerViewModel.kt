package com.nisaba.notebook

import com.nisaba.domain.conversation.askPending
import com.nisaba.domain.conversation.pendingUtterance
import com.nisaba.domain.conversation.beginLesson
import com.nisaba.domain.conversation.tidy
import com.nisaba.domain.conversation.askAgain
import com.nisaba.domain.ink.utteranceBounds
import com.nisaba.domain.search.plainText
import com.nisaba.domain.conversation.AgainHow
import com.nisaba.canvas.BlockRef
import com.nisaba.canvas.answerPen
import com.nisaba.domain.conversation.ConvState
import com.nisaba.domain.conversation.ConversationContextPorts
import com.nisaba.domain.conversation.failureMessage
import com.nisaba.domain.ink.InkPoints
import com.nisaba.domain.ink.Stroke
import com.nisaba.domain.ink.StrokeOrigin
import com.nisaba.domain.ink.answerAt
import com.nisaba.domain.notebook.Page
import com.nisaba.domain.settings.Settings
import com.nisaba.domain.shared.DomainError
import com.nisaba.domain.shared.ExchangeId
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PagePoint
import com.nisaba.domain.shared.StrokeId
import com.nisaba.domain.shared.toPage
import com.nisaba.notebook.NotebookViewModel.Companion.ANSWER_HIT
import com.nisaba.notebook.NotebookViewModel.Companion.CUT_SHORT_NOTICE
import com.nisaba.notebook.NotebookViewModel.Companion.MEMORY_NOTE_MILLIS
import com.nisaba.notebook.NotebookViewModel.Companion.SPILL_SETTLE_MILLIS
import com.nisaba.notebook.NotebookViewModel.Companion.STROKE_WIDTH
import com.nisaba.scribe.motion.TimedStroke
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch


// The ask loop seen from the notebook: sessions, the hand's callbacks, one answer's actions (erase, ask again), tidy, notes.
fun NotebookViewModel.toggleListening() {
    usedToolbar()
    val nb = notebook?.notebook ?: return
    ownScope.launch { deps.setListening(nb.id, !nb.listening) }
}

fun NotebookViewModel.askNow() { session?.onAskNow() }

/** A clean, structured copy of this page's notes, written on a new page right after it (the original stays). */
fun NotebookViewModel.tidyPage() {
    val nb = notebook ?: return
    val source = currentPage() ?: return
    if (source.size.isEndless || deps.sessions == null) return
    ownScope.launch {
        if (!insertPageAfter(nb, source.id)) return@launch
        openPage(pageIndex + 1, full = true)
        currentPage()?.let { session?.tidy(source.id, it.id) }
    }
}
/** A finger held on the page: if it is on an answer, offer what can be done with it. */
fun NotebookViewModel.onLongPress(viewX: Float, viewY: Float) {
    val page = currentPage() ?: return
    val ex = answerAt(edit?.ink?.strokes.orEmpty(), affine.toPage(viewX, viewY), ANSWER_HIT) ?: return
    ownScope.launch {
        val saved = deps.sources.exchange(ex)
        val again = deps.sessions != null && saved?.focus != null && saved.pageId == page.id
        mutableState.update { it.copy(answerMenu = AnswerMenu(ex, canAskAgain = again)) }
    }
}

fun NotebookViewModel.dismissAnswerMenu() = mutableState.update { it.copy(answerMenu = null) }

fun NotebookViewModel.eraseAnswer(ex: ExchangeId) {
    dismissAnswerMenu()
    ownScope.launch { eraseAnswerEverywhere(ex); effectSink.emit(NotebookEffect.Refresh(full = false)) }
}

/** Erases the answer and asks the same writing again (only offered on the page it was asked on). */
fun NotebookViewModel.askAgain(ex: ExchangeId, how: AgainHow = AgainHow.Afresh) {
    dismissAnswerMenu()
    ownScope.launch {
        val saved = deps.sources.exchange(ex) ?: return@launch
        val page = currentPage()?.takeIf { it.id == saved.pageId } ?: return@launch
        val answer = edit?.ink?.strokes.orEmpty().filter { (it.origin as? StrokeOrigin.Oracle)?.exchangeId == ex }
        if (!how.keepsOld) { eraseAnswerEverywhere(ex); effectSink.emit(NotebookEffect.Refresh(full = false)) }
        session?.askAgain(saved, page.id, how, utteranceBounds(answer))
    }
}

/** The second opinion: pick the other voice first (another than this notebook's). */
fun NotebookViewModel.pickOtherVoice() = mutableState.update { s ->
    val other = deps.sources.voices().all.first { it.id != s.personaId }.id
    s.copy(answerMenu = s.answerMenu?.copy(pickVoice = true, otherVoice = other))
}

/** The answer's words, as plain text, on the clipboard. */
fun NotebookViewModel.copyAnswer(ex: ExchangeId) {
    dismissAnswerMenu()
    ownScope.launch {
        val saved = deps.sources.exchange(ex) ?: return@launch
        effectSink.emit(NotebookEffect.Copy(plainText(saved.answerMarkup)))
        mutableState.update { it.copy(notice = "Copied the answer's words.") }
    }
}

/** Something wrong with an answer: the question and answer go to the share sheet, to send wherever they choose. */
fun NotebookViewModel.reportAnswer(ex: ExchangeId) {
    dismissAnswerMenu()
    ownScope.launch {
        val saved = deps.sources.exchange(ex) ?: return@launch
        val voice = deps.sources.voices().of(saved.personaId).name
        val text = "Question: ${saved.heard}\nAnswer ($voice, prompt v${saved.promptVersion}): ${plainText(saved.answerMarkup)}"
        effectSink.emit(NotebookEffect.Share("Nisaba: an answer by $voice", text))
    }
}

/** An answer may have spilled onto the pages after the one it started on: erase it there too. */
internal suspend fun NotebookViewModel.eraseAnswerEverywhere(ex: ExchangeId) {
    deps.changes.markErased(ex) // it leaves the conversation's history too
    val pages = notebook?.pages ?: return
    val here = pageIndex
    val touched = ArrayList<com.nisaba.domain.shared.PageId>()
    for (i in here downTo 0) if (eraseAnswerOn(pages[i].id, ex)) touched += pages[i].id else if (i < here) break
    for (i in here + 1 until pages.size) if (eraseAnswerOn(pages[i].id, ex)) touched += pages[i].id else break
    dropEmptyPages(touched) // a spill page the answer filled alone is not left behind blank
}

internal suspend fun NotebookViewModel.eraseAnswerOn(pageId: PageId, ex: ExchangeId): Boolean {
    var changed = false
    editAnyPage(pageId) { s -> deps.ink.eraseAnswer(s, ex).also { changed = it !== s } }
    return changed
}
fun NotebookViewModel.retry() { session?.onTapRetry() }
fun NotebookViewModel.continueScribe() { mutableState.update { it.copy(resumeMark = null) }; session?.onTapContinue() }

/**
 * The hand finished everything planned so far (up to block [ref]) on [pageId]. Commits what it wrote as ONE undoable
 * command and returns the new stroke ids synchronously so the view does not draw them a second time.
 */
fun NotebookViewModel.onScribeDrained(written: List<TimedStroke>, ref: BlockRef?, pageId: PageId?): List<StrokeId> {
    val strokes = answerStrokes(written, ref)
    ownScope.launch {
        commitAnswer(pageId, strokes)
        if (ref != null && ref.session == sessionGen) session?.onScribeFinished(ref.reqId, ref.seq)
    }
    return strokes.map { it.id }
}

/** The hand was stopped by the pen: keep what it wrote and offer to continue where it stopped. */
fun NotebookViewModel.onScribeStopped(written: List<TimedStroke>, ref: BlockRef?, pageId: PageId?): List<StrokeId> {
    val strokes = answerStrokes(written, ref)
    val last = written.lastOrNull()?.points?.let { PagePoint(it.xs.last(), it.ys.last()) }
    ownScope.launch {
        commitAnswer(pageId, strokes)
        if (pageId == currentPage()?.id) mutableState.update { it.copy(resumeMark = last) }
    }
    return strokes.map { it.id }
}

/** The hand was dropped (the page turned under it): keep what it wrote, nothing to continue. */
fun NotebookViewModel.onScribeAbandoned(written: List<TimedStroke>, ref: BlockRef?, pageId: PageId?) {
    val strokes = answerStrokes(written, ref)
    ownScope.launch { commitAnswer(pageId, strokes) }
}

/**
 * The notebook screen went away (Settings, back) while the view model lives on. What the hand wrote is kept; a
 * question in flight or an answer being written is interrupted as if the pen touched the page, since nobody is
 * there to draw the rest. [onScreenShown] picks the page up again.
 */
fun NotebookViewModel.onScreenLeft(written: List<TimedStroke>, ref: BlockRef?, pageId: PageId?) {
    onScribeAbandoned(written, ref, pageId)
    mutableState.update { it.copy(resumeMark = null) }
    session?.onPenDown()
}

/** Back on the page: an unanswered utterance gets its pause again. */
fun NotebookViewModel.onScreenShown() { session?.onPenAborted() }

/** The app went to the background: the hand stops as at a pen-down (the Stop that follows commits its ink). */
fun NotebookViewModel.onAppHidden() { session?.onPenDown() }

/** Back in front: a pause cut short by [onAppHidden] runs again. */
fun NotebookViewModel.onAppShown() { session?.onPenAborted() }

/** A block of a longer answer is written: commit it now (merges with the answer's earlier blocks into one undo step). */
fun NotebookViewModel.onScribeBlockWritten(written: List<TimedStroke>, ref: BlockRef?, pageId: PageId?): List<StrokeId> {
    val strokes = answerStrokes(written, ref)
    ownScope.launch { commitAnswer(pageId, strokes) }
    return strokes.map { it.id }
}

internal fun NotebookViewModel.answerStrokes(written: List<TimedStroke>, ref: BlockRef?): List<Stroke> {
    val exchange = ref?.exchangeId ?: ExchangeId(deps.idGen.next())
    return written.map { t ->
        val pen = answerPen(t.tone, settings.answerInk, settings.codeHighlight, ref?.ink)
        val p = t.points
        val t0 = p.ts.first()
        val points = InkPoints(p.xs, p.ys, p.ps, IntArray(p.size) { p.ts[it] - t0 })
        Stroke(StrokeId(deps.idGen.next()), points, STROKE_WIDTH * pen.widthScale, pen.color, StrokeOrigin.Oracle(exchange))
    }
}

/**
 * The answer is longer than the page: keep what was written, add a blank page right after this one, turn to it and
 * let the hand carry on. Returns the ids of the committed strokes synchronously (the view must not redraw them).
 */
fun NotebookViewModel.onScribePageBreak(written: List<TimedStroke>, toPage: Int, ref: BlockRef?, pageId: PageId?): List<StrokeId> {
    val strokes = answerStrokes(written, ref)
    ownScope.launch {
        commitAnswer(pageId, strokes)
        val nb = notebook
        val from = nb?.pages?.indexOfFirst { it.id == pageId } ?: -1
        if (nb == null || from < 0 || from != pageIndex) return@launch // the user turned away; the hand was reset
        if (nb.pages[from].size.isEndless) return@launch // a canvas has no next page: its column only grows
        if (!insertPageAfter(nb, pageId)) {
            session?.onPenDown() // could not add the page: end the answer here, as interrupted
            return@launch
        }
        openPage(from + 1, full = true, keepAnswer = true)
        delay(SPILL_SETTLE_MILLIS) // let the view lay out the new page before the hand touches it
        currentPage()?.let { scribeSink.emit(ScribeCommand.PageReady(toPage, it.id)) }
    }
    return strokes.map { it.id }
}

internal suspend fun NotebookViewModel.commitAnswer(pageId: PageId?, strokes: List<Stroke>) {
    if (pageId == null || strokes.isEmpty()) return
    editAnyPage(pageId) { deps.ink.commitMany(it, strokes) }
    deps.changes.touch(deps.notebookId)
}

internal fun NotebookViewModel.failure(error: DomainError?) {
    if (error != null) requestedStep = null // a page of a book walk that failed may be asked for again
    mutableState.update {
        it.copy(
            failure = error?.let { e -> failureMessage(e, voice().oops) }, // "Bah. The ink wouldn't flow…"
            failureRetries = error?.let { e -> com.nisaba.domain.conversation.retryHelps(e) } ?: true,
        )
    }
}

/** The voice Nisaba answers in here (their own voices included). */
internal fun NotebookViewModel.voice() = deps.sources.voices().of(notebook?.notebook?.personaId)

internal fun NotebookViewModel.memoryNote(note: String) {
    mutableState.update { it.copy(memoryNote = note) }
    memoryNoteJob?.cancel()
    memoryNoteJob = ownScope.launch { delay(MEMORY_NOTE_MILLIS); mutableState.update { it.copy(memoryNote = null) } }
}

fun NotebookViewModel.dismissMemoryNote() { memoryNoteJob?.cancel(); mutableState.update { it.copy(memoryNote = null) } }

fun NotebookViewModel.dismissNotice() = mutableState.update { it.copy(notice = null) }

internal fun NotebookViewModel.startSession(page: Page) {
    // writing still waiting for an answer is kept for its page, and asked when the page is back
    session?.let { old -> old.pendingUtterance()?.let { deps.memory.savePending(it.pageId, it) } }
    session?.close(); session = null; answerScribe?.close(); answerScribe = null
    val factory = deps.sessions ?: return
    val scribe = AnswerScribeSession(
        ++sessionGen, page.id, deps.hands, { settings }, { currentPage()?.size ?: page.size }, { edit?.ink?.strokes.orEmpty() },
        { printedOccupancy },
        { scribeSink.tryEmit(it) }, ::failure,
        ScribeNotices(
            ::memoryNote, onCutShort = { mutableState.update { it.copy(notice = CUT_SHORT_NOTICE) } },
            onReminder = { r -> mutableState.update { it.copy(reminder = r) } }, onBookNext = { nextBookStep() },
        ),
        voices = { deps.sources.voices().of(it) }, paper = { notebook?.notebook?.paper ?: com.nisaba.domain.notebook.Paper.Blank },
    )
    answerScribe = scribe
    val context = object : ConversationContextPorts {
        override suspend fun documentPages(): List<String> = deps.sources.documentPages()
        override val currentDocumentPage: Int? get() = documentPageIndex
        override val teachGoal: String? get() = notebook?.notebook?.teachGoal
        override val persona get() = voice()
        override val isPrivate get() = notebook?.notebook?.isPrivate == true
        override fun voice(id: String) = deps.sources.voices().of(id)
        override val style get() = notebook?.notebook?.style ?: com.nisaba.domain.notebook.AnswerStyle()
        override val book get() = mutableState.value.book?.takeIf { it.ready }
        override val bookConcept get() = bookConcept()
    }
    val created = factory.create(scribe, ownScope, settings, notebook?.notebook?.listening ?: true, context)
    session = created
    statusJob?.cancel()
    statusJob = ownScope.launch {
        created.status.collect { st ->
            val waiting = st.state is ConvState.Pausing || st.state is ConvState.Held
            val working = st.state is ConvState.Thinking || st.state is ConvState.Scribing
            mutableState.update {
                if (it.askable == waiting && it.working == working) it else it.copy(askable = waiting, working = working)
            }
        }
    }
    deps.memory.loadPending(page.id)?.let { utt -> deps.memory.savePending(page.id, null); created.askPending(utt) }
    // a lesson notebook: Nisaba opens a fresh lesson, or recaps when they come back after a while (the session decides)
    if (notebook?.notebook?.teachGoal != null) created.beginLesson(page.id, edit?.ink?.strokes?.maxOfOrNull { it.bounds.bottom })
    if (notebook?.notebook?.book == true) maybeOpenWalk() // a book whose map came while no page was open
}
