package com.infor.scoresync

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))

            val logFile = File(context.getExternalFilesDir(null), "crash_log.txt")
            logFile.writeText(sw.toString())
        } catch (e: Exception) {
            // if writing the log itself fails, fall through to default handler
        }

        defaultHandler?.uncaughtException(thread, throwable)
    }
}
