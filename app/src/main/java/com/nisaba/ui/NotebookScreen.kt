package com.nisaba.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
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
import com.nisaba.notebook.model.Stroke
import com.nisaba.notebook.model.TranscriptEntry
import com.nisaba.pdf.PageBackgroundCache
import com.nisaba.render.AnswerSectionRenderer
import com.nisaba.render.InkReveal
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
    private lateinit var answerSection: LinearLayout
    private lateinit var stirIndicator: TextView
    private var bgCache: PageBackgroundCache? = null

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val reveal = InkReveal(handler)
    private var summonJob: Job? = null

    private var pendingSummon = false
    private var summonGeneration = 0
    private var revealNextAnswer = false

    private lateinit var summonController: SummonController
    private val client: DeepSeekClient by lazy { DeepSeekClient(settings!!) }

    init {
        orientation = VERTICAL
    }

    fun bind(notebook: Notebook, settings: AppSettings, onBack: () -> Unit, onSave: (Notebook) -> Unit) {
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
        answerSection = findViewById(R.id.answerSection)
        stirIndicator = findViewById(R.id.stirIndicator)

        titleView.text = notebook.title
        titleView.typeface = Fonts.kalamBold
        pageLabel.typeface = Fonts.kalam
        eraserBtn.typeface = Fonts.kalam
        undoBtn.typeface = Fonts.kalam
        redoBtn.typeface = Fonts.kalam
        stirIndicator.typeface = Fonts.kalam

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
                        inkView.page = nbSession.currentPage()
                        loadBackground()
                        renderAnswers(reveal = false)
                        updateToolbar()
                        scrollToTop()
                    }
                    NotebookSession.Reason.ANSWERS -> {
                        renderAnswers(reveal = revealNextAnswer)
                        revealNextAnswer = false
                        updateToolbar()
                        scrollToBottom()
                    }
                }
            }
        }

        // Summon detection: 3 s idle → auto-send; writing cancels a pending
        // request (F3.1/F3.2, docs/09 §3).
        summonController = SummonController(
            handler = handler,
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
        renderAnswers(reveal = false)
        inkView.onStrokePoint = {
            summonController.onStrokePoint()
            nbSession.requestSave()
        }
        inkView.onStrokeComplete = ::onStrokeFinished
        inkView.onEraserButton = { held -> updateEraserVisual(held) }

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

        // One tap anywhere skips the ink reveal (docs/08 §5).
        findViewById<InkScrollView>(R.id.pageScroll).setOnTouchListener { _, _ ->
            if (reveal.isActive()) reveal.skip()
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
        if (settings?.apiKey.isNullOrBlank()) {
            Toast.makeText(context, R.string.summon_no_key, Toast.LENGTH_LONG).show()
            return
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
        setStirring(true)
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
                revealNextAnswer = true
                s.appendAnswer(group)
                s.appendTranscript(
                    listOf(
                        TranscriptEntry(role = "user", at = Instant.now().toString(), pageIndex = page.index),
                        TranscriptEntry(role = "assistant", at = Instant.now().toString(), pageIndex = page.index, answerGroupId = groupId),
                    )
                )
                pendingSummon = false
                setStirring(false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (generation != summonGeneration) return@launch // superseded
                recordAborted(s, groupId, "cancelled", e.message)
            } catch (e: Exception) {
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
        setStirring(false)
    }

    private fun onSummonCancelled() {
        // The controller already cancelled the job; the coroutine records the
        // cancelled group. Show a brief "…as you were." before hiding.
        if (pendingSummon) {
            stirIndicator.setText(R.string.summon_as_you_were)
            handler.postDelayed({ if (!pendingSummon) setStirring(false) }, 1200L)
        }
    }

    private fun setStirring(active: Boolean) {
        stirIndicator.visibility = if (active) View.VISIBLE else View.GONE
        if (active) stirIndicator.setText(R.string.stir_thinking)
    }

    // ------------------------------------------------------------ answers

    private fun renderAnswers(reveal: Boolean) {
        if (this.reveal.isActive()) this.reveal.skip() // stale steps reference old views
        answerSection.removeAllViews()
        val page = session?.currentPage() ?: return
        val groups = page.answers
        val lastIndex = groups.lastIndex
        for ((i, group) in groups.withIndex()) {
            val built = AnswerSectionRenderer.buildGroupView(
                context,
                group,
                reveal = reveal && i == lastIndex && group.status == "complete",
            )
            answerSection.addView(built.view)
            if (built.revealSteps.isNotEmpty()) {
                this.reveal.start(built.revealSteps)
            }
        }
    }

    private fun scrollToTop() {
        val scroll = findViewById<InkScrollView>(R.id.pageScroll)
        scroll?.post { scroll.scrollTo(0, 0) }
    }

    private fun scrollToBottom() {
        val scroll = findViewById<InkScrollView>(R.id.pageScroll)
        scroll?.post { scroll.fullScroll(View.FOCUS_DOWN) }
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
        reveal.skip()
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
}
