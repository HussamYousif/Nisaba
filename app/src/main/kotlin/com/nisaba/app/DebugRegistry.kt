package com.nisaba.app

import kotlinx.coroutines.flow.MutableSharedFlow

/** The debug harness: switches set from adb extras in debug builds (`MainActivity`), read once where they apply. */
class DebugRegistry {
    /** Debug only: drive the page from plain stylus MotionEvents (adb input) instead of the Onyx raw layer. */
    var forceMotionPen: Boolean = false

    /** Debug only: text for the app's own hand to write as the user's question when a notebook opens. */
    var debugWriteText: String? = null

    /** Debug only: more writing for the open notebook (`am start --activity-single-top --es write …` while it is open). */
    val debugWrites = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Debug only: a document location to import as soon as the library opens. */
    var debugImportPdf: String? = null
    var debugLearnGoal: String? = null

    /** Debug only: the voice of the debug-imported notebook (a PersonaRegistry id). */
    var debugPersona: String? = null

    /** Debug only: create a notebook with this name (voice [debugPersona], [debugCanvas]) and open it. */
    var debugNewNotebook: String? = null
    var debugCanvas: Boolean = false

    /** Debug only: delete every notebook whose name starts with this (test notebooks) when the library opens. */
    var debugDeletePrefix: String? = null

    /** Debug only: "host:port" of an HTTP proxy for the model (scripts/dev_proxy.py over `adb reverse`, for an offline Boox). */
    var debugProxy: String? = null

    /** Debug only: open the first notebook automatically. */
    var debugOpenFirst: Boolean = false

    /** Debug only: write the "Start here" notebook now (as the first run does). */
    var startHere: Boolean = false

    /** Not debug: a PDF another app handed over ("Open with Nisaba"), waiting for the library to ask about its voice. */
    var incomingPdf: String? = null

    /** A PDF handed over while the app was already open. */
    val incoming = MutableSharedFlow<String>(extraBufferCapacity = 1)
}
