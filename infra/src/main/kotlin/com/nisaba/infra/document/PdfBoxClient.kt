package com.nisaba.infra.document

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/** PdfBox needs its font/glyph resources loaded once per process before any document is opened. */
object PdfBoxClient {
    @Volatile private var ready = false

    fun init(context: Context) {
        if (ready) return
        synchronized(this) {
            if (!ready) { PDFBoxResourceLoader.init(context.applicationContext); ready = true }
        }
    }
}
