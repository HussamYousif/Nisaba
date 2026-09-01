package com.nisaba.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.nisaba.R
import com.nisaba.ink.InkSurfaceView
import com.nisaba.llm.AnswerParser
import com.nisaba.llm.AppSettings
import com.nisaba.llm.ChatContract
import com.nisaba.llm.DeepSeekClient
import com.nisaba.llm.PageImagePreparer
import com.nisaba.llm.PromptBuilder
import com.nisaba.magic.Fonts
import com.nisaba.magic.MagicTheme
import com.nisaba.notebook.HandlerSaveScheduler
import com.nisaba.notebook.NotebookSession
import com.nisaba.notebook.SummonController
import com.nisaba.notebook.model.AnswerGroup
import com.nisaba.notebook.model.Notebook
import com.nisaba.notebook.model.NotebookPage
import com.nisaba.notebook.model.Stroke
import com.nisaba.notebook.model.TranscriptEntry
import com.nisaba.pdf.PageBackgroundCache
import com.nisaba.render.AnswerInk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.math.max

/**
 * The notebook page (docs/09 §3): view wiring + summon orchestration only.
 * All state (pages, undo/redo, versions, autosave) lives in [NotebookSession]
 * (docs/architecture-review.md D-A); the screen listens and re-renders.
 */
class NotebookScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private var settings: AppSettings? = null
    private var onBack: (() -> Unit)? = null
    private var session: NotebookSession? = null

    private lateinit var inkView: InkSurfaceView
    private lateinit var pageLabel: TextView
    private lateinit var titleView: TextView
    private lateinit var eraserBtn: TextView
    private lateinit var undoBtn: TextView
    private lateinit var redoBtn: TextView

    private var bgCache: PageBackgroundCache? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var summonJob: Job? = null

    private var pendingSummon = false
    private var summonGeneration = 0
    private var revealNextAnswer = false

    /**
     * An answer that is saved but must not draw yet: the reached-out ink is
     * still being drunk, and the answer writes into a clean page (docs/09 §3).
     */
    private var holdRevealFor: String? = null

    private lateinit var summonController: SummonController
    private val client: DeepSeekClient by lazy { DeepSeekClient(settings!!) }

    init {
        orientation = VERTICAL
    }

    fun bind(notebook: Notebook, settings: AppSettings, onBack: () -> Unit, onSave: (Notebook, thumbnail: Boolean) -> Unit) {
        this.settings = settings
        this.onBack = onBack
        val nbSession = NotebookSession(notebook, HandlerSaveScheduler(), onSave)
        session = nbSession

        LayoutInflater.from(context).inflate(R.layout.notebook_screen, this, true)

        inkView = findViewById(R.id.inkView)
        pageLabel = findViewById(R.id.nbPageLabel)
        titleView = findViewById(R.id.nbTitle)
        eraserBtn = findViewById(R.id.nbEraser)
        undoBtn = findViewById(R.id.nbUndo)
        redoBtn = findViewById(R.id.nbRedo)


        titleView.text = notebook.title
        titleView.typeface = Fonts.kalamBold
        pageLabel.typeface = Fonts.kalam
        eraserBtn.typeface = Fonts.kalam
        undoBtn.typeface = Fonts.kalam
        redoBtn.typeface = Fonts.kalam

        // On Boox firmware without an EPD API, the writing feel depends on the
        // per-app refresh mode — hint once per run (docs/04 §2).
        if (!com.nisaba.epd.Epd.available && !epdHintShown) {
            epdHintShown = true
            Toast.makeText(context, R.string.epd_hint, Toast.LENGTH_LONG).show()
        }

        // PDF notebooks load page backgrounds through the LRU cache.
        val nbDir = File(context.filesDir, "notebooks/${notebook.id}")
        val pdf = notebook.source?.file?.let { File(nbDir, it) }
        bgCache = PageBackgroundCache(nbDir, pdf)

        // State events → view updates. The session is the only mutator.
        nbSession.listener = object : NotebookSession.Listener {
            override fun onChanged(reason: NotebookSession.Reason) {
                when (reason) {
                    NotebookSession.Reason.STROKES -> {
                        inkView.invalidate()
                        updateToolbar()
                    }
                    NotebookSession.Reason.PAGE,
                    NotebookSession.Reason.STRUCTURE -> {
                        // A held answer belongs to the old page; its drain can
                        // never complete here (the view reset its ink), so stop
                        // holding it or it would stay filtered out forever.
                        holdRevealFor = null
                        inkView.page = nbSession.currentPage()
                        loadBackground()
                        renderAnswerInk(animateLast = false)
                        updateToolbar()
                        scrollToTop()
                    }
                    NotebookSession.Reason.ANSWERS -> {
                        renderAnswerInk(animateLast = revealNextAnswer)
                        revealNextAnswer = false
                        updateToolbar()
                    }
                }
            }
        }

        // Summon detection: 3 s idle → auto-send; writing cancels a pending
        // request (F3.1/F3.2, docs/09 §3).
        summonController = SummonController(
            scheduler = HandlerSaveScheduler(),
            idleTimeoutSeconds = { settings.idlePauseSeconds },
            autoSummonEnabled = { settings.autoSummonEnabled },
        ).apply {
            listener = object : SummonController.Listener {
                override fun onIdleSummon() = startSummon(manual = false)
                override fun onManualSummon() = startSummon(manual = true)
                override fun onRequestCancelledByWriting() = onSummonCancelled()
            }
            hasNewContent = {
                val s = session
                if (s == null) false else s.contentVersion > s.summonedVersion
            }
        }

        inkView.page = nbSession.currentPage()
        loadBackground()
        renderAnswerInk(animateLast = false)
        inkView.onStrokePoint = {
            summonController.onStrokePoint()
            nbSession.requestSave()
        }
        inkView.onStrokeComplete = ::onStrokeFinished
        inkView.onEraserButton = { held -> updateEraserVisual(held) }
        // The thread has been drunk and the page is clean: now the answer writes.
        inkView.onSummonInkDrained = {
            if (holdRevealFor != null) {
                holdRevealFor = null
                renderAnswerInk(animateLast = true)
            }
        }

        findViewById<TextView>(R.id.nbBack).apply {
            typeface = Fonts.kalam
            setOnClickListener {
                saveNow()
                onBack()
            }
        }
        findViewById<TextView>(R.id.nbPrev).apply { setOnClickListener { nbSession.turnPage(-1) } }
        findViewById<TextView>(R.id.nbNext).apply { setOnClickListener { nbSession.turnPage(1) } }
        findViewById<TextView>(R.id.nbNewPage).apply { setOnClickListener { nbSession.addPage() } }
        undoBtn.setOnClickListener { nbSession.undo() }
        redoBtn.setOnClickListener { nbSession.redo() }
        eraserBtn.setOnClickListener {
            inkView.eraserActive = !inkView.eraserActive
            updateEraserVisual(inkView.eraserActive)
        }

        findViewById<ImageButton>(R.id.quillButton).setOnClickListener {
            summonController.manualSummon()
        }

        // One tap anywhere skips the answer ink reveal (docs/08 §5).
        findViewById<InkScrollView>(R.id.pageScroll).setOnTouchListener { _, _ ->
            inkView.skipAnswerReveal()
            false
        }

        updateToolbar()
    }

    // ------------------------------------------------------------ strokes

    private fun onStrokeFinished(stroke: Stroke) {
        val s = session ?: return
        if (stroke.tool == "eraser") {
            s.eraseStroke(stroke.points) // session fires STROKES + saves
        } else {
            s.addStroke(stroke)
        }
    }

    // ------------------------------------------------------------ summon

    /** The summon flow: image → DeepSeek → parse → render (docs/07). */
    private fun startSummon(manual: Boolean) {
        val s = session ?: return
        if (settings?.effectiveApiKey().isNullOrBlank()) {
            Toast.makeText(context, R.string.summon_no_key, Toast.LENGTH_LONG).show()
            return
        }
        // An answer still waiting on a drain must not be lost when a new summon
        // interrupts it: the drain callback will never fire, so draw it now.
        if (holdRevealFor != null) {
            holdRevealFor = null
            renderAnswerInk(animateLast = false)
        }
        // Restart semantics: a summon while one is pending replaces it.
        if (pendingSummon) {
            summonJob?.cancel()
            pendingSummon = false
            summonGeneration++
        }
        // Manual summon re-answers even without new strokes; the gate only
        // applies to the automatic idle summon (F3.4).
        s.markSummoned()
        pendingSummon = true
        // The ink reaches out: a thread flows from the last stroke's tail to
        // the spot where the answer will write itself (docs/09 §3).
        val locusPage = s.currentPage() ?: return
        val tail = lastStrokeTail(locusPage, locusPage.width.toFloat(), locusPage.height.toFloat())
        val locusY = contentBottom(locusPage) + 190f
        inkView.beginSummonInk(tail.x, tail.y, 190f, locusY, tail.dx, tail.dy)
        summonController.clearPendingRequest()

        val generation = summonGeneration
        summonJob = scope.launch {
            val groupId = "a-" + UUID.randomUUID().toString().take(8)
            try {
                val page = s.currentPage() ?: return@launch
                val bg = bgCache?.getSuspending(page.index)
                val imageUri = withContext(Dispatchers.IO) {
                    PageImagePreparer.prepare(page, bg)
                }
                val groupsByPage = s.notebook.pages.associate { it.index to it.answers }
                val messages = PromptBuilder.buildMessages(imageUri, s.notebook.transcript, groupsByPage)
                val request = ChatContract.ChatRequest(
                    model = settings!!.model,
                    temperature = settings!!.temperature,
                    max_tokens = settings!!.maxTokens,
                    messages = messages,
                )
                val raw = client.chat(request)
                val blocks = try {
                    AnswerParser.parse(raw)
                } catch (e: AnswerParser.ParseException) {
                    // One repair retry (docs/07 §6–7).
                    val repair = ChatContract.ChatRequest(
                        model = settings!!.model,
                        temperature = settings!!.temperature,
                        max_tokens = settings!!.maxTokens,
                        messages = messages + ChatContract.textMessage(
                            role = "user",
                            text = "Your previous reply was not valid JSON. Return ONLY the JSON object."
                        ),
                    )
                    AnswerParser.parse(client.chat(repair))
                }

                val group = AnswerGroup(
                    id = groupId,
                    summonedAt = Instant.now().toString(),
                    questionPage = page.index,
                    model = settings!!.model,
                    status = "complete",
                    blocks = blocks,
                )
                // Persist immediately — a Boox will kill us — but hold the
                // reveal until the thread has been drunk.
                revealNextAnswer = true
                holdRevealFor = groupId
                s.appendAnswer(group)
                s.appendTranscript(
                    listOf(
                        TranscriptEntry(role = "user", at = Instant.now().toString(), pageIndex = page.index),
                        TranscriptEntry(role = "assistant", at = Instant.now().toString(), pageIndex = page.index, answerGroupId = groupId),
                    )
                )
                pendingSummon = false
                inkView.endSummonInk(keep = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // A newer summon owns the ink now — leave everything to it.
                if (generation != summonGeneration) throw e
                // User cancellation (writing, back): not an event worth
                // persisting (audit P0-5) — no junk group in notebook.json.
                // The ink draws back; onSummonCancelled may already have
                // started it — endSummonInk is idempotent.
                pendingSummon = false
                holdRevealFor = null
                inkView.endSummonInk(keep = false)
                throw e
            } catch (e: Throwable) {
                // Throwable, not Exception: an OutOfMemoryError mid-render must
                // surface as a failed summon, not kill the app (audit P1-7).
                if (generation != summonGeneration) return@launch // superseded
                recordAborted(s, groupId, "error", e.message)
            }
        }
        summonController.setPendingRequest { summonJob?.cancel() }
    }

    private fun recordAborted(s: NotebookSession, groupId: String, status: String, error: String?) {
        val page = s.currentPage()
        if (page != null && page.answers.none { it.id == groupId }) {
            revealNextAnswer = false
            s.appendAnswer(
                AnswerGroup(
                    id = groupId,
                    questionPage = page.index,
                    status = status,
                    error = error,
                )
            )
        }
        pendingSummon = false
        holdRevealFor = null
        inkView.endSummonInk(keep = false)
        if (status == "error") {
            Toast.makeText(context, R.string.summon_error, Toast.LENGTH_LONG).show()
        }
    }

    private fun onSummonCancelled() {
        // The controller already cancelled the job; the coroutine records the
        // cancelled group and the thread draws back into the stroke.
        if (pendingSummon) {
            holdRevealFor = null
            inkView.endSummonInk(keep = false)
        }
    }

    // ------------------------------------------------------------ answers

    /** Lays all complete answers out as canvas ink below the last content. */
    private fun renderAnswerInk(animateLast: Boolean) {
        val page = session?.currentPage() ?: return
        val held = holdRevealFor
        val groups = page.answers.filter { it.status == "complete" && it.id != held }
        if (groups.isEmpty()) {
            // Nothing to lay out: stale ink from the previous page/answer must
            // not linger (audit P0-2). While an answer is held for the drain
            // the canvas already shows it, so a blank would blank a live
            // reveal — only clear when nothing is held.
            if (held == null) inkView.setAnswers(emptyList(), page.height.toFloat(), animateLast = false)
            return
        }
        var y = contentBottom(page) + 140f
        val layouts = ArrayList<AnswerInk.Layout>(groups.size)
        for (g in groups) {
            val l = AnswerInk.layout(g, page.width.toFloat(), y, context)
            layouts.add(l)
            y += l.heightPx + 60f
        }
        inkView.setAnswers(layouts, y, animateLast)
    }

    /** The end of the last pen stroke — where the ink reaches out from. */
    /** Where the last stroke ended, and which way the pen was still going. */
    private class Tail(val x: Float, val y: Float, val dx: Float, val dy: Float)

    private fun lastStrokeTail(page: NotebookPage, pageW: Float, pageH: Float): Tail {
        for (stroke in page.strokes.asReversed()) {
            if (stroke.tool != "pen") continue
            val pts = stroke.points
            val last = pts.lastOrNull() ?: continue
            // The exit tangent: the pen's direction over the last few samples.
            val prev = pts.getOrNull(pts.size - 1 - EXIT_TANGENT_SAMPLES) ?: pts.firstOrNull()
            val dx = if (prev != null) last.x - prev.x else 0f
            val dy = if (prev != null) last.y - prev.y else 0f
            return Tail(last.x, last.y, dx, dy)
        }
        // No strokes yet (blank summon): reach from the upper page area, downward.
        return Tail(pageW / 2f, 160f, 0f, 1f)
    }

    private fun contentBottom(page: NotebookPage): Float {
        var bottom = 80f
        for (stroke in page.strokes) {
            for (pt in stroke.points) bottom = max(bottom, pt.y)
        }
        return bottom
    }

    private fun scrollToTop() {
        val scroll = findViewById<InkScrollView>(R.id.pageScroll)
        scroll?.post { scroll.scrollTo(0, 0) }
    }

    // ------------------------------------------------------------ background

    private fun loadBackground() {
        val page = session?.currentPage() ?: return
        inkView.background = null
        val rel = page.background ?: return
        val cache = bgCache ?: return
        cache.get(page.index) { bmp ->
            if (session?.currentPage()?.index == page.index) {
                inkView.background = bmp
            }
        }
    }

    // ------------------------------------------------------------ lifecycle

    fun saveNow() {
        session?.saveNow()
    }

    /** Called when the screen is removed (activity back/pause). */
    fun onClose() {
        summonGeneration++ // the pending coroutine must not record after close
        summonJob?.cancel()
        summonController.shutdown()
        inkView.skipAnswerReveal()
        session?.close()
        scope.cancel()
        bgCache?.shutdown()
    }

    private fun updateEraserVisual(active: Boolean) {
        if (active) {
            eraserBtn.setTextColor(android.graphics.Color.WHITE)
            eraserBtn.setBackgroundColor(MagicTheme.ACCENT_RED)
        } else {
            eraserBtn.setTextColor(MagicTheme.INK)
            eraserBtn.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
    }

    private fun updateToolbar() {
        val s = session ?: return
        pageLabel.text = context.getString(R.string.page_label, s.pageIndex + 1, s.notebook.pages.size)
        findViewById<TextView>(R.id.nbPrev).alpha = if (s.pageIndex > 0) 1f else 0.3f
        findViewById<TextView>(R.id.nbNext).alpha = if (s.pageIndex < s.notebook.pages.lastIndex) 1f else 0.3f
        undoBtn.alpha = if (s.canUndo()) 1f else 0.3f
        redoBtn.alpha = if (s.canRedo()) 1f else 0.3f
    }

    companion object {
        /** How far back to look for the pen's exit direction at a stroke's tail. */
        private const val EXIT_TANGENT_SAMPLES = 3
        private var epdHintShown = false
    }
}
