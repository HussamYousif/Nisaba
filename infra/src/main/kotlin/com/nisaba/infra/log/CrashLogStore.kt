package com.nisaba.infra.log

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last crash, kept on the device (nothing is reported anywhere): the uncaught-exception handler writes it, Settings
 * → About shows it, `scripts/device.sh crash` pulls it.
 */
class CrashLogStore(private val dir: File) {
    private val file get() = File(dir, "last.txt")

    /** Records any uncaught exception (synchronously: the process is about to die), then hands it on to [next]. */
    fun install(build: String, next: Thread.UncaughtExceptionHandler?) {
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(build, thread.name, error) }
            next?.uncaughtException(thread, error)
        }
    }

    /** Replaces the last crash with this one (whole file or nothing: written aside, then renamed). */
    fun write(build: String, thread: String, error: Throwable, nowMillis: Long = System.currentTimeMillis()) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(nowMillis))
        dir.mkdirs()
        val tmp = File(dir, "last.txt.tmp")
        tmp.writeText("Nisaba $build · $stamp · thread $thread\n$trace")
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    /** The last crash as written (first line: build, time, thread; then the stack trace), or null. */
    fun last(): String? = file.takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    fun clear() { file.delete() }
}
