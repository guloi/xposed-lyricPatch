package com.lyricpatch.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock

/**
 * 模块 App ↔ SystemUI 的配置通道，另外兼一个“心跳”接口。
 *
 *  - read   ：SystemUI 侧的 hook 来拿配置文本（JSON；没有 JSON 时退回旧 conf）
 *  - report ：hook 装上了 / 收到 ping 时回写一次，界面拿它显示“已启用”
 *  - status ：（可选）读当前报活信息
 *
 * 内容只有歌词源 / 开关，不含隐私，所以没设权限（设了 SystemUI 也拿不到）。
 */
class ConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val reply = Bundle()
        when (method) {
            ConfigStore.METHOD_READ -> read(reply)
            ConfigStore.METHOD_REPORT -> {
                lastReportAt = SystemClock.elapsedRealtime()
                lastReportVersion = extras?.getString("version").orEmpty()
                reply.putBoolean("ok", true)
            }
            ConfigStore.METHOD_STATUS -> {
                reply.putBoolean("ok", true)
                reply.putLong("at", lastReportAt)
                reply.putString("version", lastReportVersion)
            }
            else -> reply.putBoolean("ok", false)
        }
        return reply
    }

    private fun read(reply: Bundle) {
        val ctx = context
        if (ctx == null) {
            reply.putBoolean("ok", false)
            return
        }
        val raw = ConfigStore.rawText(ctx)
        reply.putBoolean("ok", true)
        reply.putString("text", raw.text)
        reply.putLong("mtime", raw.mtime)
        reply.putString("format", if (raw.json) "json" else "conf")
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        /** hook 侧最近一次报活的时间（elapsedRealtime，同一台机器可比） */
        @Volatile
        var lastReportAt: Long = 0L
            private set

        @Volatile
        var lastReportVersion: String = ""
            private set
    }
}
