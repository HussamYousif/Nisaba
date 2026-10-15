package com.nisaba.app

import com.nisaba.domain.shared.PagePoint
import com.nisaba.domain.shared.PageId
import com.nisaba.notebook.NotebookMemory
import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nisaba.BuildConfig
import com.nisaba.device.EinkDevice
import com.nisaba.device.MotionEventPenDevice
import com.nisaba.device.NoopEinkDevice
import com.nisaba.device.OnyxEinkDevice
import com.nisaba.device.OnyxPenDevice
import com.nisaba.device.PenDevice
import com.nisaba.domain.conversation.ConversationDeps
import com.nisaba.domain.conversation.ConversationSession
import com.nisaba.domain.document.ImportDocumentUseCase
import com.nisaba.domain.ink.CommitStrokeUseCase
import com.nisaba.domain.ink.CommitStrokesUseCase
import com.nisaba.domain.ink.EraseAnswerUseCase
import com.nisaba.domain.ink.EraseUseCase
import com.nisaba.domain.ink.LoadPageInkUseCase
import com.nisaba.domain.ink.RedoUseCase
import com.nisaba.domain.ink.UndoUseCase
import com.nisaba.domain.memory.ForgetFactUseCase
import com.nisaba.domain.memory.ObserveMemoryUseCase
import com.nisaba.domain.notebook.CreateNotebookUseCase
import com.nisaba.domain.notebook.DeleteNotebookUseCase
import com.nisaba.domain.notebook.InsertPageUseCase
import com.nisaba.domain.notebook.ListNotebooksUseCase
import com.nisaba.domain.notebook.ObserveNotebookUseCase
import com.nisaba.domain.notebook.Page
import com.nisaba.domain.notebook.PageBackground
import com.nisaba.domain.notebook.RenameNotebookUseCase
import com.nisaba.domain.notebook.SetListeningUseCase
import com.nisaba.domain.notebook.SetPersonaUseCase
import com.nisaba.domain.settings.HandStyle
import com.nisaba.domain.shared.Clock
import com.nisaba.domain.shared.IdGen
import com.nisaba.domain.shared.NotebookId
import com.nisaba.infra.db.RoomExchangeStore
import com.nisaba.infra.db.RoomFactStore
import com.nisaba.infra.db.RoomLessonStore
import com.nisaba.infra.db.RoomNotebookStore
import com.nisaba.infra.deepseek.DeepSeekClient
import com.nisaba.infra.document.DocumentRasterCache
import com.nisaba.infra.document.FileDocumentStore
import com.nisaba.infra.http.NisabaHttpClient
import com.nisaba.infra.inkfile.InkFileStore
import com.nisaba.infra.log.AndroidLogClient
import com.nisaba.infra.render.BackgroundRenderer
import com.nisaba.infra.render.InkRenderer
import com.nisaba.infra.render.OccupancyRenderer
import com.nisaba.infra.settings.DataStoreSettingsStore
import com.nisaba.infra.settings.KeystoreSecretStore
import com.nisaba.infra.snapshot.PageSnapshotRenderer
import com.nisaba.library.LibraryViewModel
import com.nisaba.memory.MemoryViewModel
import com.nisaba.notebook.InkUseCases
import com.nisaba.notebook.NotebookDeps
import com.nisaba.notebook.NotebookViewModel
import com.nisaba.notebook.NotebookSources
import com.nisaba.notebook.SessionFactory
import com.nisaba.scribe.hand.GlyphSet
import com.nisaba.scribe.hand.GlyphSetParser
import com.nisaba.scribe.layout.Occupancy
import com.nisaba.domain.search.SearchUseCase
import com.nisaba.domain.usage.summarizeUsage
import com.nisaba.search.SearchViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Composition root: the only place that knows every concrete class (DESIGN D13). */
class AppGraph(private val app: Application) {
    /** Debug only: drive the page from plain stylus MotionEvents (adb input) instead of the Onyx raw layer. */
    var forceMotionPen: Boolean = false

    /** Debug only: text for the app's own hand to write as the user's question when a notebook opens. */
    var debugWriteText: String? = null

    /** Debug only: a document location to import as soon as the library opens. */
    var debugImportPdf: String? = null
    var debugLearnGoal: String? = null

    /** Debug only: the voice of the debug-imported notebook (a PersonaRegistry id). */
    var debugPersona: String? = null

    /** Debug only: open the first notebook automatically. */
    var debugOpenFirst: Boolean = false

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logger = AndroidLogClient()
    private val clock = object : Clock { override fun nowMillis() = System.currentTimeMillis() }
    private val idGen = object : IdGen { override fun next() = UUID.randomUUID().toString() }

    private val notebooks by lazy { RoomNotebookStore.open(app) }
    private val exchanges by lazy { RoomExchangeStore.open(app) }
    private val facts by lazy { RoomFactStore.open(app) }
    private val lessons by lazy { RoomLessonStore.open(app) }
    private val inkStore by lazy { InkFileStore(File(app.filesDir, "ink"), io, logger) }
    private val docsDir = File(app.filesDir, "docs")
    private val rasterDir = File(app.cacheDir, "raster")
    private val documents = FileDocumentStore(docsDir, rasterDir, app.contentResolver, idGen)
    private val rasters = DocumentRasterCache(docsDir, rasterDir)
    val settings by lazy { DataStoreSettingsStore(app) }
    val secrets by lazy { KeystoreSecretStore(app, BuildConfig.DEV_DEEPSEEK_API_KEY) }
    private val oracle by lazy { DeepSeekClient(NisabaHttpClient.shared, { secrets.apiKey() }) }

    val inkRenderer = InkRenderer()
    val backgroundRenderer = BackgroundRenderer(rasters = rasters)
    val eink: EinkDevice by lazy { if (OnyxPenDevice.isAvailable()) OnyxEinkDevice() else NoopEinkDevice() }

    private val hands = HashMap<HandStyle, GlyphSet>()

    /** Hand-written text for the wordmark, covers and hints. */
    val handText by lazy { com.nisaba.ui.brand.HandTextCache { hand(HandStyle.Casual) } }

    /** The wordmark is written out once per launch; afterwards it just sits there. */
    var wordmarkPlayed = false

    /** The bundled stroke fonts (module resources of :scribe), parsed once per style. */
    fun hand(style: HandStyle): GlyphSet = synchronized(hands) {
        hands.getOrPut(style) {
            val name = if (style == HandStyle.Formal) "formal" else "casual"
            val text = GlyphSet::class.java.classLoader!!.getResourceAsStream("hands/$name.json")!!.bufferedReader().use { it.readText() }
            GlyphSetParser.parse(text)
        }
    }

    fun createPen(): PenDevice =
        if (!forceMotionPen && OnyxPenDevice.isAvailable()) OnyxPenDevice() else MotionEventPenDevice(acceptFinger = false)

    fun libraryFactory(): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            LibraryViewModel(
                ListNotebooksUseCase(notebooks), CreateNotebookUseCase(notebooks, clock, idGen),
                RenameNotebookUseCase(notebooks, clock), DeleteNotebookUseCase(notebooks, inkStore, documents),
                ImportDocumentUseCase(documents, CreateNotebookUseCase(notebooks, clock, idGen)), settings,
            )
        }
    }

    private val viewPrefs by lazy { app.getSharedPreferences("views", android.content.Context.MODE_PRIVATE) }

    private fun loadView(page: PageId): PagePoint? =
        viewPrefs.getString(page.value, null)?.split(',')?.let { if (it.size == 2) PagePoint(it[0].toFloat(), it[1].toFloat()) else null }

    private fun saveView(page: PageId, at: PagePoint) = viewPrefs.edit().putString(page.value, "${at.x},${at.y}").apply()

    fun settingsFactory(): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            com.nisaba.settings.SettingsViewModel(
                settings, secrets, oracle, ObserveMemoryUseCase(facts),
                usage = { summarizeUsage(exchanges.usage(), clock.nowMillis(), java.time.ZoneId.systemDefault()) },
            )
        }
    }

    fun memoryFactory(): ViewModelProvider.Factory = viewModelFactory {
        initializer { MemoryViewModel(ObserveMemoryUseCase(facts), ForgetFactUseCase(facts)) }
    }

    fun searchFactory(): ViewModelProvider.Factory = viewModelFactory {
        initializer { SearchViewModel({ q -> SearchUseCase(exchanges, notebooks)(q) }) }
    }

    fun notebookFactory(id: NotebookId, startPage: PageId? = null): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            NotebookViewModel(
                NotebookDeps(
                    notebookId = id, observe = ObserveNotebookUseCase(notebooks), insertPage = InsertPageUseCase(notebooks, idGen),
                    rename = RenameNotebookUseCase(notebooks, clock),
                    ink = InkUseCases(
                        LoadPageInkUseCase(inkStore), CommitStrokeUseCase(inkStore), CommitStrokesUseCase(inkStore),
                        EraseUseCase(inkStore, idGen), UndoUseCase(inkStore), RedoUseCase(inkStore), EraseAnswerUseCase(inkStore),
                    ),
                    setListening = SetListeningUseCase(notebooks), idGen = idGen,
                    settings = settings.observe(), sessions = sessionFactory(id), hands = ::hand,
                    memory = NotebookMemory(
                        { settings.update { it.copy(hintSeen = true) } }, ::loadView, ::saveView,
                        { ink, size -> settings.update { it.copy(penInk = ink, penSize = size) } },
                    ),
                    sources = NotebookSources({ documentText(id) }, ::printedOccupancy, exchanges::get, lessons.observe(id)),
                    setPersona = SetPersonaUseCase(notebooks),
                ),
                startPage = startPage,
            )
        }
    }

    /** Renders a document page's picture if needed (no-op for blank pages). */
    suspend fun ensureRaster(background: PageBackground, @Suppress("UNUSED_PARAMETER") pageIndex: Int) {
        if (background !is PageBackground.DocumentPage) return
        rasters.ensure(background.documentId, background.index)
        rasters.ensure(background.documentId, background.index + 1) // the next page is usually wanted soon
    }

    private suspend fun documentText(id: NotebookId): List<String> =
        notebooks.get(id)?.notebook?.documentId?.let { documents.pages(it) }.orEmpty()

    private suspend fun printedOccupancy(page: Page): Occupancy? {
        val bg = page.background as? PageBackground.DocumentPage ?: return null
        val raster = rasters.ensure(bg.documentId, bg.index) ?: return null
        return withContext(Dispatchers.Default) { OccupancyRenderer.of(raster, page.size) }
    }

    private fun sessionFactory(id: NotebookId) = SessionFactory { surface, scope, s, listening, context ->
        ConversationSession(
            ConversationDeps(
                notebookId = id, oracle = oracle,
                snapshotter = PageSnapshotRenderer(id, notebooks, inkStore, inkRenderer, backgroundRenderer, rasters),
                exchanges = exchanges, surface = surface, context = context, clock = clock, idGen = idGen, logger = logger,
                memory = facts, lessons = lessons,
            ),
            scope, s, listening,
        )
    }

    /** Writes pending ink now (called when the app leaves the foreground). */
    /** Writes pending ink now, off the UI thread (the app scope outlives the activity). */
    fun flushInk() { io.launch { inkStore.flush() } }
}
