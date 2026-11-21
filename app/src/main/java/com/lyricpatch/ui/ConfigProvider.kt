package com.lyricpatch.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * 模块 App → SystemUI 的只读取配置口。
 *
 * 配置存在本 App 的内部存储里，SystemUI 是另一个进程、另一个 uid，
 * 直接读文件会被权限和 SELinux 拦住，走 ContentProvider 就没这个麻烦。
 *
 * 内容只有歌词源 / 开关，不含隐私，所以没设权限（设了 SystemUI 也拿不到）。
 */
class ConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val reply = Bundle()
        if (method != ConfigStore.METHOD_READ) {
            reply.putBoolean("ok", false)
            return reply
        }
        val ctx = context
        if (ctx == null) {
            reply.putBoolean("ok", false)
            return reply
        }

        val target = ConfigStore.file(ctx)
        val exists = target.isFile
        val text = if (exists) {
            runCatching { target.readText() }.getOrDefault("")
        } else {
            ""
        }

        reply.putBoolean("ok", true)
        reply.putString("text", text)
        reply.putLong("mtime", if (exists) target.lastModified() else 0L)
        return reply
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
}
