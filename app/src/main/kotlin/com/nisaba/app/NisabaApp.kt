package com.nisaba.app

import android.app.Application
import android.os.Build
import com.nisaba.infra.document.PdfBoxClient
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass

class NisabaApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // Onyx pen SDK reaches into hidden framework APIs on Android 11+ (verified in spike S1).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
        PdfBoxClient.init(this)
        graph = AppGraph(this)
        // a weekly backup, if a folder was chosen and the last one is a week old (quietly; never in the way)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { runCatching { graph.files.backupToFolderIfDue() } }
    }
}
