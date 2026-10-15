package com.nisaba.app

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PageRect
import com.nisaba.search.SearchScreen
import com.nisaba.search.SearchViewModel
import com.nisaba.library.LibraryActions
import com.nisaba.library.LibraryScreen
import com.nisaba.library.LibraryViewModel
import com.nisaba.memory.MemoryScreen
import com.nisaba.memory.MemoryViewModel
import com.nisaba.notebook.NotebookScreen
import com.nisaba.notebook.NotebookViewModel
import com.nisaba.notebook.debugWriteAsUser
import com.nisaba.settings.SettingsScreen
import com.nisaba.settings.SettingsViewModel
import com.nisaba.welcome.WelcomeScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.produceState

private const val LIBRARY = "library"
private const val NOTEBOOK = "notebook/{id}?page={page}&mark={mark}"
private const val SEARCH = "search"
private const val SETTINGS = "settings"
private const val WELCOME = "welcome"
private const val MEMORY = "memory"
private const val DEBUG_SETTLE_MILLIS = 1500L

/** No animated transitions anywhere (e-ink): route changes are instant swaps. */
@Composable
fun NisabaNavHost(graph: AppGraph) {
    val nav = rememberNavController()
    // Blank paper for the instant it takes DataStore to say whether this is the first run.
    val start by produceState<String?>(null) {
        val skipWelcome = graph.debug.debugOpenFirst || graph.debug.debugImportPdf != null
        value = if (graph.settings.observe().first().onboarded || skipWelcome) LIBRARY else WELCOME
    }
    val startRoute = start ?: return
    NavHost(
        nav, startDestination = startRoute,
        enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None }, popExitTransition = { ExitTransition.None },
    ) {
        composable(LIBRARY) { LibraryRoute(graph, nav) }
        val pageArg = navArgument("page") { type = NavType.StringType; nullable = true; defaultValue = null }
        val markArg = navArgument("mark") { type = NavType.StringType; nullable = true; defaultValue = null }
        composable(NOTEBOOK, arguments = listOf(pageArg, markArg)) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val startPage = entry.arguments?.getString("page")?.takeIf { it.isNotBlank() }?.let(::PageId)
            val mark = entry.arguments?.getString("mark")?.split(',')?.mapNotNull { it.toFloatOrNull() }
                ?.takeIf { it.size == 4 }?.let { PageRect(it[0], it[1], it[2], it[3]) }
            val factory = graph.notebookFactory(NotebookId(id), startPage, mark)
            val vm: NotebookViewModel = viewModel(key = id + startPage?.value.orEmpty(), factory = factory)
            val standardPen by produceState<Boolean?>(null) { value = graph.settings.observe().first().standardPen }
            val pen = remember(standardPen) { standardPen?.let { graph.createPen(it) } } ?: return@composable
            LaunchedEffect(id) {
                graph.debug.debugWriteText?.let { text ->
                    graph.debug.debugWriteText = null
                    kotlinx.coroutines.delay(DEBUG_SETTLE_MILLIS)
                    vm.debugWriteAsUser(text)
                }
                graph.debug.debugWrites.collect { vm.debugWriteAsUser(it) } // follow-ups while the notebook is open
            }
            NotebookScreen(
                vm, pen, graph.eink, graph.inkRenderer, graph.backgroundRenderer, graph::ensureRaster,
                onBack = { nav.popBackStack() }, onOpenSettings = { nav.navigate(SETTINGS) },
            )
        }
        composable(WELCOME) {
            val vm: SettingsViewModel = viewModel(factory = graph.settingsFactory())
            val state by vm.state.collectAsState()
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            WelcomeScreen(state, vm, onDone = {
                vm.finishOnboarding()
                scope.launch { graph.createStartHere() } // the library shows it as soon as it is there
                nav.navigate(LIBRARY) { popUpTo(WELCOME) { inclusive = true } }
            })
        }
        composable(SETTINGS) {
            val vm: SettingsViewModel = viewModel(factory = graph.settingsFactory())
            val state by vm.state.collectAsState()
            SettingsScreen(state, vm, onBack = { nav.popBackStack() }, onOpenMemory = { nav.navigate(MEMORY) })
        }
        composable(SEARCH) {
            val vm: SearchViewModel = viewModel(factory = graph.searchFactory())
            val state by vm.state.collectAsState()
            SearchScreen(state, vm, onBack = { nav.popBackStack() }, onOpen = { hit ->
                val mark = hit.focus?.let { "&mark=${it.left},${it.top},${it.right},${it.bottom}" }.orEmpty()
                nav.navigate("notebook/${hit.notebookId.value}?page=${hit.pageId.value}$mark")
            })
        }
        composable(MEMORY) {
            val vm: MemoryViewModel = viewModel(factory = graph.memoryFactory())
            val state by vm.state.collectAsState()
            MemoryScreen(state, vm, onBack = { nav.popBackStack() })
        }
    }
}

/** The library: notebooks, new notebook, open a PDF, learn a PDF. */
@Composable
private fun LibraryRoute(graph: AppGraph, nav: NavHostController) {
    val vm: LibraryViewModel = viewModel(factory = graph.libraryFactory())
    val state by vm.state.collectAsState()
    LaunchedEffect(state.notebooks) {
        val first = state.notebooks.firstOrNull()
        if (graph.debug.debugOpenFirst && first != null) { graph.debug.debugOpenFirst = false; nav.navigate("notebook/${first.id.value}") }
    }
    val open: (com.nisaba.domain.shared.NotebookId) -> Unit = { nav.navigate("notebook/${it.value}") }
    LaunchedEffect(Unit) {
        graph.debug.debugDeletePrefix?.let { prefix -> graph.debug.debugDeletePrefix = null; vm.debugDeleteNamed(prefix) }
        if (graph.debug.startHere) { graph.debug.startHere = false; graph.createStartHere() }
        graph.debug.debugNewNotebook?.let { name ->
            graph.debug.debugNewNotebook = null
            val kind = if (graph.debug.debugCanvas) com.nisaba.library.NotebookKind.Canvas else com.nisaba.library.NotebookKind.Pages
            val voice = graph.debug.debugPersona ?: com.nisaba.domain.persona.PersonaRegistry.DEFAULT_ID
            vm.createNotebook(name, kind, voice, onCreated = open)
        }
        graph.debug.debugImportPdf?.let { uri ->
            graph.debug.debugImportPdf = null
            val persona = graph.debug.debugPersona ?: com.nisaba.domain.persona.PersonaRegistry.DEFAULT_ID
            vm.importPdf(uri, graph.debug.debugLearnGoal, persona, onCreated = open)
        }
    }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> vm.onPdfPicked(uri?.toString(), open) }
    LaunchedEffect(Unit) { // "Open with Nisaba": choose who answers, then it imports (no system picker)
        graph.debug.incomingPdf?.let { graph.debug.incomingPdf = null; vm.offerIncomingPdf(it) }
        graph.debug.incoming.collect { graph.debug.incomingPdf = null; vm.offerIncomingPdf(it) }
    }
    LibraryScreen(
        state,
        LibraryActions(
            onChooseKind = vm::chooseKind, onSelectVoice = vm::selectVoice, onSelectStyle = vm::selectStyle, onBack = vm::back,
            onConfirmVoice = { vm.confirmVoice(open) { pickPdf.launch(arrayOf("application/pdf")) } },
            onCreate = { name -> vm.confirmCreate(name, open) },
            onLearn = { goal -> vm.confirmLearn(goal, open) },
            animateWordmark = !graph.wordmarkPlayed, onWordmarkDone = { graph.wordmarkPlayed = true },
            onOpen = open,
            onNew = vm::onNew, onMenu = vm::onMenu,
            onRename = vm::confirmRename, onDelete = vm::confirmDelete,
            onRenameRequested = vm::onRenameRequested, onDeleteRequested = vm::onDeleteRequested, onDismiss = vm::dismiss,
            onSettings = { nav.navigate(SETTINGS) },
            onSearch = { nav.navigate(SEARCH) },
            onGridPage = vm::gridPage, onPin = vm::pin, onTrash = vm::openTrash, onRestore = vm::restore,
            onDeleteForever = vm::deleteForever, onEmptyTrash = vm::emptyTrash,
        ),
    )
}
