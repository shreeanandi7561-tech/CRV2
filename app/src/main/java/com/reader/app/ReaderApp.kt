package com.reader.app

import android.app.Application
import com.reader.app.di.NotificationHelper
import com.reader.app.di.ServiceLocator
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class ReaderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Application class initialized. Triggers change tracking.
        // PdfBox-Android needs its asset bundle wired up exactly once before
        // any PDF is loaded. Calling this in onCreate is the canonical place;
        // doing it later (e.g. lazily on first import) sometimes races with
        // background imports started immediately after launch.
        PDFBoxResourceLoader.init(this)
        ServiceLocator.init(this)
        // Pre-create the "Generation results" notification channel so the
        // very first time GenerationManager finishes, the system already
        // knows where to route the notification. Cheap; idempotent.
        NotificationHelper.ensureChannels(this)
    }
}
