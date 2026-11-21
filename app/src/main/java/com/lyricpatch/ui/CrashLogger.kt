package com.lyricpatch.ui

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 把未捕获异常写进私有目录，下次启动时由设置页弹出来。
 *
 * 崩溃后进程已经没了，没法当场提示，所以只能存下来下次显示。
 */
object CrashLogger {

    private const val TAG = "LyricPatch"
    private const val FILE_NAME = "last_crash.txt"

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true

        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val buffer = StringWriter()
                throwable.printStackTrace(PrintWriter(buffer))
                File(app.filesDir, FILE_NAME).writeText(buffer.toString())
                Log.e(TAG, "crash captured", throwable)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 取出并删除上次的记录 */
    fun take(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull()
        runCatching { file.delete() }
        return text?.takeIf { it.isNotBlank() }
    }
}
