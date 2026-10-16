package com.nisaba.app

import android.app.Application
import android.os.Build
import com.nisaba.BuildConfig
import com.nisaba.infra.document.PdfBoxClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File

/** Where the last crash is kept (`files/crash/last.txt`). */
fun crashLog(app: Application) = com.nisaba.infra.log.CrashLogStore(File(app.filesDir, "crash"))

/** Settings → About for this build: its name, and the crash log read and cleared off the main thread. */
fun settingsAbout(app: Application) = com.nisaba.settings.SettingsAbout(
    "${BuildConfig.VERSION_NAME} ${BuildConfig.BUILD_TYPE} · prompt v${com.nisaba.domain.conversation.PROMPT_VERSION}",
    { withContext(Dispatchers.IO) { crashLog(app).last() } },
    { withContext(Dispatchers.IO) { crashLog(app).clear() } },
)

class NisabaApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // the last crash stays on the device, for Settings → About (nothing is reported anywhere)
        crashLog(this).install("${BuildConfig.VERSION_NAME} ${BuildConfig.BUILD_TYPE}", Thread.getDefaultUncaughtExceptionHandler())
        // Onyx pen SDK reaches into hidden framework APIs on Android 11+ (verified in spike S1).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
        PdfBoxClient.init(this)
        graph = AppGraph(this)
        // a weekly backup, if a folder was chosen and the last one is a week old (quietly; never in the way)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { runCatching { graph.files.backupToFolderIfDue() } }
    }
}
