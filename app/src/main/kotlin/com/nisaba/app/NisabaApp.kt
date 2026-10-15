package com.nisaba.app

import android.app.Application
import android.os.Build
import com.nisaba.infra.document.PdfBoxClient
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass

class NisabaApp : Application() {
    lateinit var graph: AppGraph
        private set

    @Suppress("SpreadOperator") // once per process
    override fun onCreate() {
        super.onCreate()
        // The Onyx pen SDK reaches into hidden framework APIs: only those are opened up, not everything.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching { HiddenApiBypass.addHiddenApiExemptions(*ONYX_HIDDEN_APIS) }
        PdfBoxClient.init(this)
        graph = AppGraph(this)
        // a weekly backup, if a folder was chosen and the last one is a week old (quietly; never in the way)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { runCatching { graph.files.backupToFolderIfDue() } }
    }
}

/** What the Onyx SDK reaches for (each found by running without it and reading the denials in the log). */
private val ONYX_HIDDEN_APIS = arrayOf(
    "Landroid/onyx/", // the EPD and pen helpers themselves
    "Landroid/view/View;->invalidate", "Landroid/view/View;->postInvalidate", // EPD-aware redraws
    "Landroid/hardware/input/InputManager;", // touch off while the pen writes (palm rejection)
    "Landroid/app/ActivityManager;", "Landroid/os/Environment;", "Landroid/os/PowerManager;", // its device helpers
)
