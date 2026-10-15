package com.nisaba.app

import com.nisaba.domain.shared.PagePoint
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PageRect
import com.nisaba.notebook.NotebookMemory
import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nisaba.BuildConfig
import com.nisaba.device.EinkDevice
import com.nisaba.device.FallbackPenDevice
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
import com.nisaba.domain.persona.DraftVoiceUseCase
import com.nisaba.domain.persona.ForgetVoiceUseCase
import com.nisaba.domain.persona.KeepVoiceUseCase
import com.nisaba.domain.persona.Voices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
import com.nisaba.domain.ink.InkUseCases
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
    /** The debug harness's switches (adb extras in debug builds); all off in release. */
    val debug = DebugRegistry()


    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logger = AndroidLogClient()
    private val clock = object : Clock { override fun nowMillis() = System.currentTimeMillis() }
    private val idGen = object : IdGen { override fun next() = UUID.randomUUID().toString() }

    private val notebooks by lazy { RoomNotebookStore.open(app) }
    private val exchanges by lazy { RoomExchangeStore.open(app) }
    private val facts by lazy { RoomFactStore.open(app) }
    private val lessons by lazy { RoomLessonStore.open(app) }
    private val ownVoices by lazy { com.nisaba.infra.db.RoomOwnVoiceStore.open(app) }
    private val inkStore by lazy { InkFileStore(File(app.filesDir, "ink"), io, logger) }
    private val docsDir = File(app.filesDir, "docs")
    private val rasterDir = File(app.cacheDir, "raster")
    private val documents = FileDocumentStore(docsDir, rasterDir, app.contentResolver, idGen)
    private val rasters = DocumentRasterCache(docsDir, rasterDir)
    val settings by lazy { DataStoreSettingsStore(app) }

    /** Exports, backups and restores. */
    val files by lazy {
        val exports = com.nisaba.infra.export.ExportRenderer(notebooks, inkStore, exchanges, BackgroundRenderer(rasters = rasters), rasters)
        FileRegistry(app, settings, exports)
    }
    val secrets by lazy { KeystoreSecretStore(app, BuildConfig.DEV_DEEPSEEK_API_KEY) }
    private val oracle by lazy { DeepSeekClient(NisabaHttpClient.withProxy(debug.debugProxy), { secrets.apiKey() }) }

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

    /** The standard pen when asked for (Settings → Pen) or off a Boox; else the Onyx pen, falling back if it can't start. */
    fun createPen(standard: Boolean = false): PenDevice = when {
        debug.forceMotionPen || standard || !OnyxPenDevice.isAvailable() -> MotionEventPenDevice(acceptFinger = false)
        else -> FallbackPenDevice.onyx { logger.w("Pen", "the Onyx pen could not start: using the standard pen") }
    }

    /** Every voice, their own included, kept current for lookups anywhere (pickers, covers, the hand, the session). */
    val voices: kotlinx.coroutines.flow.StateFlow<Voices> by lazy {
        ownVoices.observe().map { Voices(it) }.stateIn(io, kotlinx.coroutines.flow.SharingStarted.Eagerly, Voices())
    }

    fun ownVoiceFactory(): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            com.nisaba.persona.OwnVoiceViewModel(
                DraftVoiceUseCase(oracle), KeepVoiceUseCase(ownVoices, idGen), ForgetVoiceUseCase(ownVoices), voices,
                { settings.observe().first().model },
            )
        }
    }

    fun libraryFactory(): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            LibraryViewModel(
                ListNotebooksUseCase(notebooks), CreateNotebookUseCase(notebooks, clock, idGen),
                RenameNotebookUseCase(notebooks, clock), DeleteNotebookUseCase(notebooks, inkStore, documents),
                ImportDocumentUseCase(documents, CreateNotebookUseCase(notebooks, clock, idGen)), settings,
                changes = libraryChanges(),
            )
        }
    }

    private val prefs by lazy { NotebookPrefsStore(app) }

    private val thumbnails by lazy { com.nisaba.canvas.ThumbnailRenderer(inkStore, BackgroundRenderer(rasters = rasters), rasters) }

    private fun pageChanges() = com.nisaba.notebook.PageChanges(
        com.nisaba.domain.notebook.DeletePageUseCase(notebooks, inkStore)::invoke,
        com.nisaba.domain.notebook.MovePageUseCase(notebooks)::invoke,
        { nb, page -> com.nisaba.domain.notebook.DuplicatePageUseCase(InsertPageUseCase(notebooks, idGen), inkStore, idGen)(nb, page) },
        com.nisaba.domain.notebook.SetPaperUseCase(notebooks)::invoke, com.nisaba.domain.notebook.SetTeachGoalUseCase(notebooks)::invoke,
    )

    private fun libraryChanges() = com.nisaba.library.LibraryChanges(
        com.nisaba.domain.notebook.PinNotebookUseCase(notebooks), com.nisaba.domain.notebook.TrashNotebookUseCase(notebooks, clock),
        com.nisaba.domain.notebook.RestoreNotebookUseCase(notebooks),
        com.nisaba.domain.notebook.EmptyTrashUseCase(notebooks, DeleteNotebookUseCase(notebooks, inkStore, documents), clock),
        com.nisaba.domain.notebook.ListTrashUseCase(notebooks),
    )

    /** Whether there is a network at all (a question that failed offline is asked again when it comes back). */
    val online: kotlinx.coroutines.flow.StateFlow<Boolean> by lazy {
        val flow = kotlinx.coroutines.flow.MutableStateFlow(false)
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
        runCatching {
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { flow.value = true }
                override fun onLost(network: android.net.Network) { flow.value = false }
            })
        }
        flow
    }
    private val touchNotebook by lazy { com.nisaba.domain.notebook.TouchNotebookUseCase(notebooks, clock) }

    fun settingsFactory(): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            com.nisaba.settings.SettingsViewModel(
                settings, secrets, oracle, ObserveMemoryUseCase(facts),
                usage = { summarizeUsage(exchanges.usage(), clock.nowMillis(), java.time.ZoneId.systemDefault()) },
                backups = com.nisaba.settings.SettingsBackups(files::backupTo, files::restoreFrom, files::setBackupFolder),
            )
        }
    }

    fun memoryFactory(): ViewModelProvider.Factory = viewModelFactory {
        initializer { MemoryViewModel(ObserveMemoryUseCase(facts), ForgetFactUseCase(facts)) }
    }

    fun searchFactory(): ViewModelProvider.Factory = viewModelFactory {
        initializer { SearchViewModel({ q, limit -> SearchUseCase(exchanges, notebooks, documents)(q, limit) }) }
    }

    fun notebookFactory(id: NotebookId, startPage: PageId? = null, mark: PageRect? = null): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            NotebookViewModel(
                NotebookDeps(
                    notebookId = id, observe = ObserveNotebookUseCase(notebooks), insertPage = InsertPageUseCase(notebooks, idGen),
                    rename = RenameNotebookUseCase(notebooks, clock),
                    ink = InkUseCases(
                        LoadPageInkUseCase(inkStore), CommitStrokeUseCase(inkStore), CommitStrokesUseCase(inkStore),
                        EraseUseCase(inkStore, idGen), UndoUseCase(inkStore), RedoUseCase(inkStore), EraseAnswerUseCase(inkStore),
                        com.nisaba.domain.ink.ReplaceStrokesUseCase(inkStore), com.nisaba.domain.ink.RemoveStrokesUseCase(inkStore),
                    ),
                    setListening = SetListeningUseCase(notebooks), idGen = idGen,
                    settings = settings.observe(), sessions = sessionFactory(id), hands = ::hand,
                    memory = NotebookMemory(
                        { settings.update { it.copy(hintSeen = true) } }, prefs::loadView, prefs::saveView,
                        { ink, size -> settings.update { it.copy(penInk = ink, penSize = size) } },
                        prefs::lastPage, prefs::saveLastPage, prefs::loadPending, prefs::savePending,
                        prefs::lastDaily, prefs::saveLastDaily,
                    ),
                    sources = NotebookSources(
                        { documentText(id) }, ::printedOccupancy, exchanges::get, lessons.observe(id), online, voices = { voices.value },
                        lastExchangeMillis = { exchanges.listByNotebook(id).maxOfOrNull { it.createdAtMillis } },
                        thumbnail = thumbnails::of, export = { asPdf, uri -> files.export(id, asPdf, uri) },
                    ),
                    changes = com.nisaba.notebook.NotebookChanges(
                        SetPersonaUseCase(notebooks), { touchNotebook(it) },
                        com.nisaba.domain.notebook.SetAnswerStyleUseCase(notebooks)::invoke,
                        com.nisaba.domain.conversation.MarkAnswerErasedUseCase(exchanges)::invoke,
                        com.nisaba.domain.notebook.SetPrivateUseCase(notebooks)::invoke, pageChanges(),
                    ),
                ),
                startPage = startPage, startMark = mark,
            )
        }
    }

    /** First run: a "Start here" notebook whose first page the hand has already written (once). */
    suspend fun createStartHere() {
        if (com.nisaba.welcome.StartHereRenderer.NAME in notebooks.allNames()) return
        val nb = CreateNotebookUseCase(notebooks, clock, idGen)(com.nisaba.welcome.StartHereRenderer.NAME, personaId = "plain")
        val page = notebooks.get(nb.id)?.pages?.firstOrNull() ?: return
        inkStore.save(page.id, com.nisaba.welcome.StartHereRenderer(hand(HandStyle.Casual), idGen).strokes(page.size))
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
                snapshotter = PageSnapshotRenderer(id, notebooks, inkStore, InkRenderer(), BackgroundRenderer(rasters = rasters), rasters),
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
