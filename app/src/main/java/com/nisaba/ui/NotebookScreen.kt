package com.nisaba.ui

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.nisaba.R
import com.nisaba.ink.Eraser
import com.nisaba.ink.InkSurfaceView
import com.nisaba.magic.Fonts
import com.nisaba.magic.MagicTheme
import com.nisaba.notebook.model.Notebook
import com.nisaba.notebook.model.NotebookPage
import com.nisaba.notebook.model.Stroke
import com.nisaba.pdf.PageBackgroundCache
import java.io.File
import java.util.ArrayDeque

/**
 * The notebook page (docs/03 §4): toolbar + writing surface + quill button.
 * Owns the undo/redo stacks, page navigation and the debounced autosave.
 */
class NotebookScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** A reversible notebook mutation (undo/redo, docs/05 §5). */
    private class Mutation(val undo: () -> Unit, val redo: () -> Unit)

    private var notebook: Notebook? = null
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
    private var bgCache: PageBackgroundCache? = null

    private val saveHandler = Handler(Looper.getMainLooper())
    private val autosave = Runnable {
        onSave?.invoke(notebook ?: return@Runnable)
    }

    init {
        orientation = VERTICAL
    }

    fun bind(notebook: Notebook, onBack: () -> Unit, onSave: (Notebook) -> Unit) {
        this.notebook = notebook
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

        titleView.text = notebook.title
        titleView.typeface = Fonts.kalamBold
        pageLabel.typeface = Fonts.kalam
        eraserBtn.typeface = Fonts.kalam
        undoBtn.typeface = Fonts.kalam
        redoBtn.typeface = Fonts.kalam

        // PDF notebooks load page backgrounds through the LRU cache.
        val nbDir = File(context.filesDir, "notebooks/${notebook.id}")
        val pdf = notebook.source?.file?.let { File(nbDir, it) }
        bgCache = PageBackgroundCache(nbDir, pdf)

        inkView.page = currentPage()
        loadBackground()
        inkView.onStrokePoint = { scheduleAutosave() }
        inkView.onStrokeComplete = ::onStrokeFinished
        inkView.onEraserButton = { held ->
            if (held) {
                eraserBtn.setTextColor(Color.WHITE)
                eraserBtn.setBackgroundColor(MagicTheme.ACCENT_RED)
            } else {
                eraserBtn.setTextColor(MagicTheme.INK)
                eraserBtn.setBackgroundColor(Color.TRANSPARENT)
            }
        }

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
            if (inkView.eraserActive) {
                eraserBtn.setTextColor(Color.WHITE)
                eraserBtn.setBackgroundColor(MagicTheme.ACCENT_RED)
            } else {
                eraserBtn.setTextColor(MagicTheme.INK)
                eraserBtn.setBackgroundColor(Color.TRANSPARENT)
            }
        }

        findViewById<ImageButton>(R.id.quillButton).setOnClickListener {
            Toast.makeText(context, R.string.summon_m3, Toast.LENGTH_SHORT).show()
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
            // Only apply if the user hasn't turned the page meanwhile.
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
        pageIndex = target
        nb.lastOpenedPage = target
        inkView.page = currentPage()
        loadBackground()
        undoStack.clear()
        redoStack.clear()
        updatePageUi()
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
                updatePageUi()
            },
            redo = {
                nb.pages = nb.pages + newPage
                pageIndex = index
                nb.lastOpenedPage = index
                inkView.page = currentPage()
                loadBackground()
                updatePageUi()
            },
        )
        pageIndex = index
        nb.lastOpenedPage = index
        inkView.page = currentPage()
        updatePageUi()
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
        markDirty()
    }

    private fun redo() {
        val m = redoStack.pollLast() ?: return
        undoStack.addLast(Mutation(m.undo, m.redo))
        m.redo()
        markDirty()
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
        saveNow()
        bgCache?.shutdown()
    }

    private fun updatePageUi() {
        val nb = notebook ?: return
        pageLabel.text = context.getString(R.string.page_label, pageIndex + 1, nb.pages.size)
        findViewById<TextView>(R.id.nbPrev).alpha =
            if (pageIndex > 0) 1f else 0.3f
        findViewById<TextView>(R.id.nbNext).alpha =
            if (pageIndex < nb.pages.lastIndex) 1f else 0.3f
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
