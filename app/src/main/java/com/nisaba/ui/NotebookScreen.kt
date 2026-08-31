package com.nisaba.ui

import android.content.Context
import android.graphics.Color
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
import com.nisaba.ink.Eraser
import com.nisaba.ink.InkSurfaceView
import com.nisaba.llm.AnswerParser
import com.nisaba.llm.AppSettings
import com.nisaba.llm.ChatContract
import com.nisaba.llm.DeepSeekClient
import com.nisaba.llm.PageImagePreparer
import com.nisaba.llm.PromptBuilder
import com.nisaba.magic.Fonts
import com.nisaba.magic.MagicTheme
import com.nisaba.notebook.SummonController
import com.nisaba.notebook.model.AnswerGroup
import com.nisaba.notebook.model.Notebook
import com.nisaba.notebook.model.NotebookPage
import com.nisaba.notebook.model.Stroke
import com.nisaba.notebook.model.TranscriptEntry
import com.nisaba.pdf.PageBackgroundCache
import com.nisaba.render.AnswerSectionRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID

/**
 * The notebook page (docs/03 §4, docs/09 §3): toolbar + scrollable page +
 * answer section + quill button. Owns undo/redo, page navigation, autosave
 * and the summon flow (idle detection → DeepSeek → answer rendering).
 */
class NotebookScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** A reversible notebook mutation (undo/redo, docs/05 §5). */
    private class Mutation(val undo: () -> Unit, val redo: () -> Unit)

    private var notebook: Notebook? = null
    private var settings: AppSettings? = null
    private var pageIndex = 0
    private var onBack: (() -> Unit)? = null
    private var onSave: ((Notebook) -> Unit)? = null

    private val undoStack = ArrayDeque<Mutation>()
    private val redoStack = ArrayDeque<Mutation>()

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
    private var summonJob: Job? = null

    private var contentVersion = 0
    private var summonedVersion = -1
    private var pendingSummon = false
    private var summonGeneration = 0

    private lateinit var summonController: SummonController
    private val client: DeepSeekClient by lazy { DeepSeekClient(settings!!) }

    private val saveHandler = Handler(Looper.getMainLooper())
    private val autosave = Runnable {
        onSave?.invoke(notebook ?: return@Runnable)
    }

    init {
        orientation = VERTICAL
    }

    fun bind(notebook: Notebook, settings: AppSettings, onBack: () -> Unit, onSave: (Notebook) -> Unit) {
        this.notebook = notebook
        this.settings = settings
        this.onBack = onBack
        this.onSave = onSave
        pageIndex = notebook.lastOpenedPage.coerceIn(0, (notebook.pages.size - 1).coerceAtLeast(0))

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
            hasNewContent = { contentVersion > summonedVersion }
        }

        inkView.page = currentPage()
        loadBackground()
        renderAnswers()
        inkView.onStrokePoint = {
            summonController.onStrokePoint()
            scheduleAutosave()
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
        findViewById<TextView>(R.id.nbPrev).apply { setOnClickListener { turnPage(-1) } }
        findViewById<TextView>(R.id.nbNext).apply { setOnClickListener { turnPage(1) } }
        findViewById<TextView>(R.id.nbNewPage).apply { setOnClickListener { addPage() } }
        undoBtn.setOnClickListener { undo() }
        redoBtn.setOnClickListener { redo() }
        eraserBtn.setOnClickListener {
            inkView.eraserActive = !inkView.eraserActive
            updateEraserVisual(inkView.eraserActive)
        }

        findViewById<ImageButton>(R.id.quillButton).setOnClickListener {
            summonController.manualSummon()
        }

        updatePageUi()
    }

    // ------------------------------------------------------------ pages

    private fun currentPage(): NotebookPage? = notebook?.pages?.getOrNull(pageIndex)

    /** Loads the current page's background (PDF notebooks) via the LRU cache. */
    private fun loadBackground() {
        val page = currentPage() ?: return
        inkView.background = null
        val rel = page.background ?: return
        val cache = bgCache ?: return
        cache.get(page.index) { bmp ->
            if (currentPage()?.index == page.index) {
                inkView.background = bmp
            }
        }
    }

    private fun turnPage(delta: Int) {
        val nb = notebook ?: return
        val target = (pageIndex + delta).coerceIn(0, nb.pages.lastIndex)
        if (target == pageIndex) return
        saveNow()
        summonController.cancelAll() // leave any pending request behind cleanly
        pageIndex = target
        nb.lastOpenedPage = target
        inkView.page = currentPage()
        loadBackground()
        renderAnswers()
        undoStack.clear()
        redoStack.clear()
        updatePageUi()
        scrollToTop()
    }

    private fun addPage() {
        val nb = notebook ?: return
        saveNow()
        val index = nb.pages.size
        nb.pages = nb.pages + NotebookPage(index = index)
        val oldIndex = pageIndex
        val newPage = nb.pages.last()
        push(
            undo = {
                nb.pages = nb.pages.filterNot { it.index == index }
                pageIndex = oldIndex
                nb.lastOpenedPage = oldIndex
                inkView.page = currentPage()
                loadBackground()
                renderAnswers()
                updatePageUi()
            },
            redo = {
                nb.pages = nb.pages + newPage
                pageIndex = index
                nb.lastOpenedPage = index
                inkView.page = currentPage()
                loadBackground()
                renderAnswers()
                updatePageUi()
            },
        )
        pageIndex = index
        nb.lastOpenedPage = index
        inkView.page = currentPage()
        loadBackground()
        renderAnswers()
        updatePageUi()
        scrollToTop()
        contentVersion++
    }

    // ------------------------------------------------------------ strokes

    private fun onStrokeFinished(stroke: Stroke) {
        val page = currentPage() ?: return
        if (stroke.tool == "eraser") {
            val removed = Eraser.erase(page, stroke.points)
            if (removed.isEmpty()) return
            push(
                undo = {
                    page.strokes = page.strokes + removed
                    inkView.invalidate()
                },
                redo = {
                    page.strokes = page.strokes.filterNot { s -> removed.any { it.id == s.id } }
                    inkView.invalidate()
                },
            )
        } else {
            page.strokes = page.strokes + stroke
            push(
                undo = {
                    page.strokes = page.strokes.filterNot { it.id == stroke.id }
                    inkView.invalidate()
                },
                redo = {
                    page.strokes = page.strokes + stroke
                    inkView.invalidate()
                },
            )
        }
        contentVersion++
        markDirty()
    }

    // ------------------------------------------------------------ undo/redo

    private fun push(undo: () -> Unit, redo: () -> Unit) {
        undoStack.addLast(Mutation(undo, redo))
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
        updatePageUi()
    }

    private fun undo() {
        val m = undoStack.pollLast() ?: return
        redoStack.addLast(Mutation(m.redo, m.undo))
        m.undo()
        contentVersion++
        markDirty()
    }

    private fun redo() {
        val m = redoStack.pollLast() ?: return
        undoStack.addLast(Mutation(m.undo, m.redo))
        m.redo()
        contentVersion++
        markDirty()
    }

    // ------------------------------------------------------------ summon

    /** The summon flow: image → DeepSeek → parse → render (docs/07). */
    private fun startSummon(manual: Boolean) {
        val nb = notebook ?: return
        val page = currentPage() ?: return
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
        summonedVersion = contentVersion
        pendingSummon = true
        setStirring(true)
        summonController.clearPendingRequest()

        val generation = summonGeneration
        summonJob = scope.launch {
            val groupId = "a-" + UUID.randomUUID().toString().take(8)
            try {
                val bg = bgCache?.getSuspending(page.index)
                val imageUri = withContext(Dispatchers.IO) {
                    PageImagePreparer.prepare(page, bg)
                }
                val groupsByPage = nb.pages.associate { it.index to it.answers }
                val messages = PromptBuilder.buildMessages(imageUri, nb.transcript, groupsByPage)
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
                page.answers = page.answers + group
                nb.transcript = nb.transcript + listOf(
                    TranscriptEntry(role = "user", at = Instant.now().toString(), pageIndex = page.index),
                    TranscriptEntry(role = "assistant", at = Instant.now().toString(), pageIndex = page.index, answerGroupId = groupId),
                )
                pendingSummon = false
                setStirring(false)
                renderAnswers()
                scrollToBottom()
                markDirty()
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (generation != summonGeneration) return@launch // superseded
                // Cancelled by writing (F3.2) or navigation; record it.
                val pageNow = currentPage()
                if (pageNow != null && pageNow.answers.none { it.id == groupId }) {
                    pageNow.answers = pageNow.answers + AnswerGroup(
                        id = groupId,
                        questionPage = pageNow.index,
                        status = "cancelled",
                    )
                }
                pendingSummon = false
                setStirring(false)
                renderAnswers()
                markDirty()
            } catch (e: Exception) {
                if (generation != summonGeneration) return@launch // superseded
                val pageNow = currentPage()
                if (pageNow != null && pageNow.answers.none { it.id == groupId }) {
                    pageNow.answers = pageNow.answers + AnswerGroup(
                        id = groupId,
                        questionPage = pageNow.index,
                        status = "error",
                        error = e.message,
                    )
                }
                pendingSummon = false
                setStirring(false)
                renderAnswers()
                markDirty()
            }
        }
        summonController.setPendingRequest { summonJob?.cancel() }
    }

    private fun onSummonCancelled() {
        // The controller already cancelled the job; the coroutine records the
        // cancelled group. The indicator is cleared there too.
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

    private fun renderAnswers() {
        answerSection.removeAllViews()
        val page = currentPage() ?: return
        for (group in page.answers) {
            answerSection.addView(AnswerSectionRenderer.buildGroupView(context, group))
        }
    }

    private fun scrollToTop() {
        findViewById<com.nisaba.ui.InkScrollView>(R.id.pageScroll)?.let {
            it.post { it.scrollTo(0, 0) }
        }
    }

    private fun scrollToBottom() {
        findViewById<com.nisaba.ui.InkScrollView>(R.id.pageScroll)?.let {
            it.post { it.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ------------------------------------------------------------ save

    private fun scheduleAutosave() {
        saveHandler.removeCallbacks(autosave)
        saveHandler.postDelayed(autosave, AUTOSAVE_MS)
    }

    /** Flushes pending autosave synchronously (back, page turn, onPause). */
    fun saveNow() {
        saveHandler.removeCallbacks(autosave)
        val nb = notebook ?: return
        nb.lastOpenedPage = pageIndex
        onSave?.invoke(nb)
    }

    /** Called when the screen is removed (activity back/pause). */
    fun onClose() {
        summonGeneration++ // the pending coroutine must not record after close
        summonJob?.cancel()
        summonController.shutdown()
        saveNow()
        scope.cancel()
        bgCache?.shutdown()
    }

    private fun updateEraserVisual(active: Boolean) {
        if (active) {
            eraserBtn.setTextColor(Color.WHITE)
            eraserBtn.setBackgroundColor(MagicTheme.ACCENT_RED)
        } else {
            eraserBtn.setTextColor(MagicTheme.INK)
            eraserBtn.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun updatePageUi() {
        val nb = notebook ?: return
        pageLabel.text = context.getString(R.string.page_label, pageIndex + 1, nb.pages.size)
        findViewById<TextView>(R.id.nbPrev).alpha = if (pageIndex > 0) 1f else 0.3f
        findViewById<TextView>(R.id.nbNext).alpha = if (pageIndex < nb.pages.lastIndex) 1f else 0.3f
        undoBtn.alpha = if (undoStack.isNotEmpty()) 1f else 0.3f
        redoBtn.alpha = if (redoStack.isNotEmpty()) 1f else 0.3f
    }

    private fun markDirty() {
        updatePageUi()
        scheduleAutosave()
    }

    companion object {
        private const val AUTOSAVE_MS = 800L
        private const val MAX_UNDO = 50
    }
}
