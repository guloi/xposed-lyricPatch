package com.lyricpatch.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 页面与 lyricpatch.json 之间唯一的读写入口。
 *
 * 保存由用户操作触发：开关、选项、输入、滑杆的 `onValueChange` 调用 [set]，
 * 这里把改动标脏后合并一次写入（[SAVE_DEBOUNCE_MS]）。没有“每帧检查值变没变”
 * 的监听，也没有手动保存按钮；值没变或和上次落盘内容一样时直接跳过写入。
 *
 * 整个 App 共用一个实例：首页和设置页看到的是同一份状态，返回时不需要重新加载。
 */
object ConfigSession {

    /** 连续改动的合并窗口：拖滑杆 / 输入时只写最后一次 */
    private const val SAVE_DEBOUNCE_MS = 180L

    private var appContext: Context? = null
    private var worker: Job? = null

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveSignal = Channel<Unit>(Channel.CONFLATED)
    private val main = Dispatchers.Main.immediate

    /** 上一次真正写进文件的内容，用来跳过无意义的重复写入 */
    private var lastPersisted: Map<String, String>? = null

    val values = mutableStateMapOf<String, String>()

    var loaded by mutableStateOf(false)
        private set

    var saving by mutableStateOf(false)
        private set

    /** 配置文件最后一次写入的时间（毫秒）；0 = 还没写过 */
    var savedAt by mutableStateOf(0L)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** 加载 / 迁移时给页面看的一次性提示 */
    var notice by mutableStateOf<String?>(null)
        private set

    var configPath by mutableStateOf("")
        private set

    /** null = 还没探到；true = hook 在响应 */
    var active by mutableStateOf<Boolean?>(null)
        private set

    /** SystemUI 侧上报的 hook 版本 */
    var hookVersion by mutableStateOf("")
        private set

    /** 两个 Activity 都会调；只有第一次会真正启动保存循环 */
    fun attach(context: Context) {
        appContext = context.applicationContext
        if (worker != null) return
        synchronized(this) {
            if (worker != null) return
            worker = io.launch {
                for (ignored in saveSignal) {
                    withContext(main) { saving = true }
                    delay(SAVE_DEBOUNCE_MS)
                    // 合并窗口内的后续改动已经由 CONFLATED 通道合掉
                    while (saveSignal.tryReceive().isSuccess) {
                        // drain
                    }
                    persist()
                }
            }
        }
    }

    /**
     * 从磁盘重新读取。只在进入页面 / 手动重新载入时调用，不在组合期反复读。
     * @return false 表示内存里还有未落盘的改动，这次不覆盖
     */
    fun load(): Boolean {
        val context = appContext ?: return false
        // 内存里还有没落盘的改动时不要回读：否则刚按下的开关会被旧文件覆盖
        if (loaded && lastPersisted != null && values.toMap() != lastPersisted) return false
        io.launch {
            val result = ConfigStore.load(context)
            withContext(main) {
                values.clear()
                values.putAll(result.values)
                configPath = result.path
                savedAt = result.updatedAt
                loaded = true
                error = null
                notice = result.notice
                lastPersisted = result.values
            }
            // 旧版 key=value 配置：读出来后写一份 JSON，再删掉旧文件
            if (result.migratedFrom != null) {
                val migrated = ConfigStore.save(context, result.values)
                if (migrated.ok) {
                    ConfigStore.deleteLegacy(context)
                    withContext(main) { savedAt = migrated.updatedAt }
                }
            }
        }
        return true
    }

    /** 任意配置项改动：值没变就直接返回，真正写入交给保存循环 */
    fun set(key: String, value: String) {
        if (values[key] == value) return
        values[key] = value
        saveSignal.trySend(Unit)
    }

    fun setFlag(key: String, on: Boolean) = set(key, if (on) "true" else "false")

    fun setNumber(key: String, value: Int) = set(key, value.toString())

    /** 完整重置：歌词配置回到默认值（文件里未知键也会被清掉） */
    fun reset() {
        values.clear()
        values.putAll(ConfigStore.DEFAULTS)
        notice = null
        error = null
        saveSignal.trySend(Unit)
    }

    fun resetUiPrefs(context: Context) = UiPrefs.reset(context)

    fun consumeNotice() {
        notice = null
    }

    /** 探一次 hook 是否活着（页面每 10 秒调一次，不是每帧） */
    suspend fun refreshStatus() {
        val context = appContext ?: return
        val alive = withContext(Dispatchers.IO) { ConfigStore.isActive(context) }
        val version = ConfigProvider.lastReportVersion
        withContext(main) {
            active = alive
            hookVersion = version
        }
    }

    fun raw(key: String): String = values[key] ?: ConfigStore.DEFAULTS[key].orEmpty()

    fun flag(key: String): Boolean {
        val def = (ConfigStore.DEFAULTS[key] ?: "false").toBooleanLoose(false)
        return raw(key).toBooleanLoose(def)
    }

    fun number(key: String, fallback: Int = 0): Int =
        raw(key).toIntOrNull() ?: ConfigStore.DEFAULTS[key]?.toIntOrNull() ?: fallback

    private suspend fun persist() {
        val context = appContext
        if (context == null) {
            withContext(main) { saving = false }
            return
        }
        val snapshot = withContext(main) { values.toMap() }
        if (snapshot == lastPersisted) {
            withContext(main) { saving = false }
            return
        }

        val result = ConfigStore.save(context, snapshot)
        withContext(main) {
            saving = false
            if (result.ok) {
                savedAt = result.updatedAt
                lastPersisted = snapshot
                error = null
            } else {
                error = result.message.ifBlank { "未知错误" }
            }
        }
    }
}

private fun String.toBooleanLoose(def: Boolean): Boolean = when (trim().lowercase()) {
    "1", "true", "yes", "on" -> true
    "0", "false", "no", "off" -> false
    else -> def
}
