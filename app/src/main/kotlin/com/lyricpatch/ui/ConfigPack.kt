package com.lyricpatch.ui

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 配置包：一份 zip，里面两文件。
 *
 *   lyricpatch.json  SystemUI 侧配置（源、翻译、逐字、滚动）
 *   ui_prefs.json    App 外观（取色、底栏、动画、更新检测）
 */
object ConfigPack {

    const val CONF_ENTRY = "lyricpatch.json"
    const val UI_ENTRY = "ui_prefs.json"

    fun write(out: OutputStream, conf: String, ui: String) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(CONF_ENTRY))
            zip.write(conf.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(UI_ENTRY))
            zip.write(ui.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    /** 只读这两个文件，其它条目忽略；不是 zip / 读不出时返回空 map */
    fun read(input: InputStream): Map<String, String> {
        val out = linkedMapOf<String, String>()
        runCatching {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.substringAfterLast('/')
                    if (name == CONF_ENTRY || name == UI_ENTRY) {
                        out[name] = zip.readBytes().toString(Charsets.UTF_8)
                    }
                    zip.closeEntry()
                }
            }
        }
        return out
    }

    /** zip 头是 PK\x03\x04 */
    fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
}
