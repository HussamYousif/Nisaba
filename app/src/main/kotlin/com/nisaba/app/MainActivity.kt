package com.nisaba.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.nisaba.BuildConfig
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.CompositionLocalProvider
import com.nisaba.ui.brand.LocalHandText
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nisaba.persona.LocalOwnVoices
import com.nisaba.persona.LocalVoices
import com.nisaba.persona.OwnVoiceHost
import com.nisaba.persona.OwnVoiceViewModel
import com.nisaba.ui.theme.NisabaTheme

class MainActivity : ComponentActivity() {
    private val graph get() = (application as NisabaApp).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) {
            graph.forceMotionPen = intent.getBooleanExtra("pen_motion", false)
            graph.debugWriteText = intent.getStringExtra("write")
            graph.debugOpenFirst = intent.getBooleanExtra("open_first", false)
            graph.debugImportPdf = intent.getStringExtra("import_pdf")
            graph.debugLearnGoal = intent.getStringExtra("learn_goal") // with import_pdf: a lesson notebook ("" = no goal)
            graph.debugPersona = intent.getStringExtra("persona") // with import_pdf: its voice (a PersonaRegistry id)
            graph.debugProxy = intent.getStringExtra("proxy") // "127.0.0.1:8899": scripts/dev_proxy.py over adb reverse
            graph.debugNewNotebook = intent.getStringExtra("new_notebook") // a fresh test notebook, opened at once
            graph.debugCanvas = intent.getBooleanExtra("canvas", false)
            graph.debugDeletePrefix = intent.getStringExtra("delete_prefix") // test notebooks to remove
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            val voices by graph.voices.collectAsState()
            val ownVoices: OwnVoiceViewModel = viewModel(factory = graph.ownVoiceFactory())
            NisabaTheme {
                CompositionLocalProvider(
                    LocalHandText provides graph.handText, LocalVoices provides voices, LocalOwnVoices provides ownVoices,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        NisabaNavHost(graph)
                        OwnVoiceHost(ownVoices) // above any dialog that asked for it
                    }
                }
            }
        }
    }

    /** Debug: `am start --activity-single-top --es write '…'` writes into the notebook already open. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (BuildConfig.DEBUG) intent.getStringExtra("write")?.let { graph.debugWrites.tryEmit(it) }
    }

    override fun onPause() {
        graph.flushInk()
        super.onPause()
    }
}
