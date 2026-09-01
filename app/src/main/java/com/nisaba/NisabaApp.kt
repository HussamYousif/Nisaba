package com.nisaba

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * App entry point: initializes engine-wide resources. Today that is the
 * PdfBox-Android font loader (needed by the PDF fallback renderer).
 */
class NisabaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
    }
}
