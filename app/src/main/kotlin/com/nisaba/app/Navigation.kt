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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nisaba.domain.shared.NotebookId
import com.nisaba.library.LibraryActions
import com.nisaba.library.LibraryScreen
import com.nisaba.library.LibraryViewModel
import com.nisaba.notebook.NotebookScreen
import com.nisaba.notebook.NotebookViewModel
import com.nisaba.settings.SettingsScreen
import com.nisaba.settings.SettingsViewModel
import com.nisaba.welcome.WelcomeScreen
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.produceState

private const val LIBRARY = "library"
private const val NOTEBOOK = "notebook/{id}"
private const val SETTINGS = "settings"
private const val WELCOME = "welcome"
private const val DEBUG_SETTLE_MILLIS = 1500L

/** No animated transitions anywhere (e-ink): route changes are instant swaps. */
@Composable
fun NisabaNavHost(graph: AppGraph) {
    val nav = rememberNavController()
    // Blank paper for the instant it takes DataStore to say whether this is the first run.
    val start by produceState<String?>(null) {
        value = if (graph.settings.observe().first().onboarded || graph.debugOpenFirst || graph.debugImportPdf != null) LIBRARY else WELCOME
    }
    val startRoute = start ?: return
    NavHost(
        nav, startDestination = startRoute,
        enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None }, popExitTransition = { ExitTransition.None },
    ) {
        composable(LIBRARY) {
            val vm: LibraryViewModel = viewModel(factory = graph.libraryFactory())
            val state by vm.state.collectAsState()
            LaunchedEffect(state.notebooks) {
                val first = state.notebooks.firstOrNull()
                if (graph.debugOpenFirst && first != null) { graph.debugOpenFirst = false; nav.navigate("notebook/${first.id.value}") }
            }
            LaunchedEffect(Unit) {
                graph.debugImportPdf?.let { uri ->
                    graph.debugImportPdf = null
                    vm.importPdf(uri) { nav.navigate("notebook/${it.value}") }
                }
            }
            val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) vm.importPdf(uri.toString()) { nav.navigate("notebook/${it.value}") }
            }
            LibraryScreen(
                state,
                LibraryActions(
                    onPickPdf = { pickPdf.launch(arrayOf("application/pdf")) },
                    animateWordmark = !graph.wordmarkPlayed, onWordmarkDone = { graph.wordmarkPlayed = true },
                    onOpen = { nav.navigate("notebook/${it.value}") },
                    onNew = vm::onNew, onMenu = vm::onMenu,
                    onCreate = { name, endless -> vm.confirmCreate(name, endless) { nav.navigate("notebook/${it.value}") } },
                    onRename = vm::confirmRename, onDelete = vm::confirmDelete,
                    onRenameRequested = vm::onRenameRequested, onDeleteRequested = vm::onDeleteRequested, onDismiss = vm::dismiss,
                    onSettings = { nav.navigate(SETTINGS) },
                ),
            )
        }
        composable(NOTEBOOK) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val vm: NotebookViewModel = viewModel(key = id, factory = graph.notebookFactory(NotebookId(id)))
            val pen = remember { graph.createPen() }
            LaunchedEffect(id) {
                graph.debugWriteText?.let { text ->
                    graph.debugWriteText = null
                    kotlinx.coroutines.delay(DEBUG_SETTLE_MILLIS)
                    vm.debugWriteAsUser(text)
                }
            }
            NotebookScreen(
                vm, pen, graph.eink, graph.inkRenderer, graph.backgroundRenderer, graph::ensureRaster,
                onBack = { nav.popBackStack() }, onOpenSettings = { nav.navigate(SETTINGS) },
            )
        }
        composable(WELCOME) {
            val vm: SettingsViewModel = viewModel(factory = graph.settingsFactory())
            val state by vm.state.collectAsState()
            WelcomeScreen(state, vm, onDone = {
                vm.finishOnboarding()
                nav.navigate(LIBRARY) { popUpTo(WELCOME) { inclusive = true } }
            })
        }
        composable(SETTINGS) {
            val vm: SettingsViewModel = viewModel(factory = graph.settingsFactory())
            val state by vm.state.collectAsState()
            SettingsScreen(state, vm, onBack = { nav.popBackStack() })
        }
    }
}
