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
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            NisabaTheme { CompositionLocalProvider(LocalHandText provides graph.handText) { NisabaNavHost(graph) } }
        }
    }

    override fun onPause() {
        graph.flushInk()
        super.onPause()
    }
}
