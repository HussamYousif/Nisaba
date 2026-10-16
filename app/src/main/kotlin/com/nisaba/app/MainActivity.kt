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
            graph.debug.forceMotionPen = intent.getBooleanExtra("pen_motion", false)
            graph.debug.debugWriteText = intent.getStringExtra("write")
            graph.debug.debugOpenFirst = intent.getBooleanExtra("open_first", false)
            graph.debug.debugImportPdf = intent.getStringExtra("import_pdf")
            graph.debug.debugLearnGoal = intent.getStringExtra("learn_goal") // with import_pdf: a lesson notebook ("" = no goal)
            graph.debug.debugBook = intent.getBooleanExtra("book", false) // with import_pdf: a book walk
            graph.debug.debugPersona = intent.getStringExtra("persona") // with import_pdf: its voice (a PersonaRegistry id)
            graph.debug.debugProxy = intent.getStringExtra("proxy") // "127.0.0.1:8899": scripts/dev_proxy.py over adb reverse
            graph.debug.debugNewNotebook = intent.getStringExtra("new_notebook") // a fresh test notebook, opened at once
            graph.debug.debugCanvas = intent.getBooleanExtra("canvas", false)
            graph.debug.debugDeletePrefix = intent.getStringExtra("delete_prefix") // test notebooks to remove
            graph.debug.startHere = intent.getBooleanExtra("start_here", false)
        }
        incomingPdf(intent)?.let { graph.debug.incomingPdf = it }
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

    /** A PDF handed over by another app (View or Share), as a location to import. */
    private fun incomingPdf(i: android.content.Intent?): String? = when (i?.action) {
        android.content.Intent.ACTION_VIEW -> i.data?.toString()
        android.content.Intent.ACTION_SEND -> androidx.core.content.IntentCompat
            .getParcelableExtra(i, android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)?.toString()
        else -> null
    }

    /** Debug: `am start --activity-single-top --es write '…'` writes into the notebook already open. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        incomingPdf(intent)?.let { graph.debug.incomingPdf = it; graph.debug.incoming.tryEmit(it) }
        if (BuildConfig.DEBUG) intent.getStringExtra("write")?.let { graph.debug.debugWrites.tryEmit(it) }
    }

    override fun onResume() {
        super.onResume()
        com.nisaba.device.ReminderDevice.catchUp(this) // a reminder whose alarm the device refused is posted now
    }

    override fun onPause() {
        graph.flushInk()
        super.onPause()
    }
}
