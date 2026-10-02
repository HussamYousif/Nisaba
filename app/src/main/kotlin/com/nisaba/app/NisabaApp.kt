package com.nisaba.app

import android.app.Application
import android.os.Build
import com.nisaba.infra.document.PdfBoxClient
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
    }
}
