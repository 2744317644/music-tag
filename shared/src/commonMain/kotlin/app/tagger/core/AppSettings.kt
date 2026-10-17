package app.tagger.core

import okio.FileSystem
import okio.Path
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 应用设置。持久化到用户数据目录下的 settings.json（三端同一套逻辑）。
 */
data class AppSettings(
    val recursive: Boolean = true,
    val lyricsMode: String = "merged",
    val writeLrc: Boolean = true,
    val lrcEncoding: String = "utf-8",
    val writeFields: List<String> = listOf(
        "title", "artist", "album", "album_artist", "track", "year", "genre", "lyrics",
    ),
    val workers: Int = 4,
    val autoConfirmThreshold: Double = 0.72,
    val backup: Boolean = true,
    val writeCover: Boolean = true,
    val coverSize: Int = 600,
    val rateLimit: Double = 3.0,
    val cookie: String = "",
    val lastDirectory: String = "",
) {
    companion object {
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

        fun settingsPath(appName: String = "NeteaseTagger"): String =
            "${Platform.appDataDirectory(appName)}/settings.json"

        fun load(fileSystem: FileSystem, path: String = settingsPath()): AppSettings {
            return try {
                val target = okio.Path.Companion.run { path.toPath() }
                if (!fileSystem.exists(target)) return AppSettings()
                val text = fileSystem.read(target) { readUtf8() }
                fromJson(text)
            } catch (ex: Exception) {
                AppSettings()
            }
        }

        fun fromJson(text: String): AppSettings {
            return try {
                // 容忍 UTF-8 BOM：不少编辑器（含 PowerShell）保存时会加 BOM，
                // 直接 parse 会抛异常并静默退回默认值，导致设置"丢失"。
                val cleaned = text.removePrefix("\uFEFF").trim()
                val obj = json.parseToJsonElement(cleaned).jsonObject
                val defaults = AppSettings()
                fun str(key: String, fallback: String) = obj[key]?.jsonPrimitive?.content ?: fallback
                fun bool(key: String, fallback: Boolean) = obj[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: fallback
                fun int(key: String, fallback: Int) = obj[key]?.jsonPrimitive?.content?.toIntOrNull() ?: fallback
                fun dbl(key: String, fallback: Double) = obj[key]?.jsonPrimitive?.content?.toDoubleOrNull() ?: fallback
                val fields = obj["writeFields"]?.let { element ->
                    (element as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content }
                } ?: defaults.writeFields
                AppSettings(
                    recursive = bool("recursive", defaults.recursive),
                    lyricsMode = str("lyricsMode", defaults.lyricsMode),
                    writeLrc = bool("writeLrc", defaults.writeLrc),
                    lrcEncoding = str("lrcEncoding", defaults.lrcEncoding),
                    writeFields = fields,
                    workers = int("workers", defaults.workers),
                    autoConfirmThreshold = dbl("autoConfirmThreshold", defaults.autoConfirmThreshold),
                    backup = bool("backup", defaults.backup),
                    writeCover = bool("writeCover", defaults.writeCover),
                    coverSize = int("coverSize", defaults.coverSize),
                    rateLimit = dbl("rateLimit", defaults.rateLimit),
                    cookie = str("cookie", defaults.cookie),
                    lastDirectory = str("lastDirectory", defaults.lastDirectory),
                )
            } catch (ex: Exception) {
                AppSettings()
            }
        }

        fun toJson(settings: AppSettings): String {
            val obj: JsonObject = buildJsonObject {
                put("recursive", settings.recursive)
                put("lyricsMode", settings.lyricsMode)
                put("writeLrc", settings.writeLrc)
                put("lrcEncoding", settings.lrcEncoding)
                put("writeFields", kotlinx.serialization.json.JsonArray(settings.writeFields.map { JsonPrimitive(it) }))
                put("workers", settings.workers)
                put("autoConfirmThreshold", settings.autoConfirmThreshold)
                put("backup", settings.backup)
                put("writeCover", settings.writeCover)
                put("coverSize", settings.coverSize)
                put("rateLimit", settings.rateLimit)
                put("cookie", settings.cookie)
                put("lastDirectory", settings.lastDirectory)
            }
            return json.encodeToString(JsonObject.serializer(), obj)
        }

        fun save(fileSystem: FileSystem, settings: AppSettings, path: String = settingsPath()) {
            try {
                val target = okio.Path.Companion.run { path.toPath() }
                target.parent?.let { fileSystem.createDirectories(it) }
                fileSystem.write(target) { write(toJson(settings).encodeToByteArray()) }
            } catch (ex: Exception) {
                // 保存失败不阻塞使用
            }
        }
    }
}

/** 运行日志：内存里保留最近若干条，同时可选写到文件。 */
class RunLog(private val fileSystem: FileSystem? = null, private val filePath: String? = null) {
    data class Entry(val atSeconds: Long, val level: String, val message: String)

    /**
     * 用 StateFlow 承载日志：update 是原子操作，三端都可用
     * （JVM 的 synchronized 在 Kotlin/Native 上没有）。
     */
    private val entries = kotlinx.coroutines.flow.MutableStateFlow<List<Entry>>(emptyList())

    val snapshot: List<Entry> get() = entries.value

    fun info(message: String) = add("info", message)
    fun warn(message: String) = add("warn", message)
    fun error(message: String) = add("error", message)

    private fun add(level: String, message: String) {
        @OptIn(kotlin.time.ExperimentalTime::class)
        val nowSeconds = kotlin.time.Clock.System.now().epochSeconds
        val entry = Entry(nowSeconds, level, message)
        entries.update { old ->
            val merged = old + entry
            if (merged.size > 2000) merged.takeLast(1500) else merged
        }
        if (fileSystem != null && filePath != null) {
            try {
                val target = okio.Path.Companion.run { filePath.toPath() }
                target.parent?.let { fileSystem.createDirectories(it) }
                fileSystem.appendingSink(target).useCloseable { sink ->
                    val buffer = okio.Buffer().write("$level: $message\n".encodeToByteArray())
                    sink.write(buffer, buffer.size)
                }
            } catch (ex: Exception) {
                // 忽略日志写盘失败
            }
        }
    }

    fun clear() {
        entries.value = emptyList()
    }
}
