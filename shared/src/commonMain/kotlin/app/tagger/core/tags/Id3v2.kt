package app.tagger.core.tags

import app.tagger.core.useCloseable

import app.tagger.core.Crypto

import okio.Buffer
import okio.FileSystem
import okio.Path

/**
 * ID3v2 读写（支持 v2.3 / v2.4，读取兼容 v2.2）。
 *
 * 只重写文件开头的 ID3 标签区，音频帧原样保留 —— 这样不会破坏音频数据，
 * 也不需要解码任何音频格式。
 */
internal object Id3v2 {

    private const val HEADER_SIZE = 10

    // 文本帧 ID -> 字段名
    private val textFrames = mapOf(
        "TIT2" to "title",
        "TT2" to "title",
        "TPE1" to "artist",
        "TP1" to "artist",
        "TALB" to "album",
        "TAL" to "album",
        "TPE2" to "album_artist",
        "TP2" to "album_artist",
        "TRCK" to "track",
        "TRK" to "track",
        "TPOS" to "disc",
        "TPA" to "disc",
        "TDRC" to "year",
        "TYER" to "year",
        "TYE" to "year",
        "TCON" to "genre",
        "TCO" to "genre",
        "TCOM" to "composer",
        "TCM" to "composer",
        "TCOP" to "copyright",
        "TCR" to "copyright",
    )

    // 写入时用 v2.3 帧（兼容性最好）
    private val writeFrames = mapOf(
        "title" to "TIT2",
        "artist" to "TPE1",
        "album" to "TALB",
        "album_artist" to "TPE2",
        "track" to "TRCK",
        "disc" to "TPOS",
        "year" to "TDRC",
        "genre" to "TCON",
        "composer" to "TCOM",
        "copyright" to "TCOP",
    )

    private const val USLT = "USLT"
    private const val APIC = "APIC"

    // ---------------------------------------------------------------- 读取

    fun read(fileSystem: FileSystem, path: Path): TagSnapshot {
        val size = fileSystem.metadata(path).size ?: 0L
        if (size <= 0) return emptySnapshot(path, "文件为空")

        val head = readAt(fileSystem, path, 0, minOf(size, 10L).toInt())
        if (head.size < 10 || head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) {
            // 没有 ID3 标签也允许：只是没标签
            val info = Mp3Info.parse(fileSystem, path, 0)
            return TagSnapshot(
                path = path.toString(),
                format = "MP3",
                durationSeconds = info.durationSeconds,
                bitrate = info.bitrate,
                sampleRate = info.sampleRate,
                channels = info.channels,
                bitsPerSample = 0,
                tags = emptyMap(),
                hasCover = false,
                coverMime = "",
                coverSize = 0,
                coverMd5 = "",
                writable = true,
                canEmbedCover = true,
            )
        }

        val version = head[3].toInt() and 0xFF
        val flags = head[5].toInt() and 0xFF
        val tagSize = syncSafe(head, 6, 4)
        // 空标签（size=0）是合法的：只有头部没有帧。之前当成"非法"会让这类文件变成不可写。
        if (tagSize < 0 || tagSize > size - 10) return emptySnapshot(path, "ID3 标签长度非法")
        if (tagSize == 0) {
            // 只有头部、没有帧：等价于"没有标签"，但保持可写
            val info = Mp3Info.parse(fileSystem, path, 10)
            return TagSnapshot(
                path = path.toString(),
                format = "MP3",
                durationSeconds = info.durationSeconds,
                bitrate = info.bitrate,
                sampleRate = info.sampleRate,
                channels = info.channels,
                bitsPerSample = 0,
                tags = emptyMap(),
                hasCover = false,
                coverMime = "",
                coverSize = 0,
                coverMd5 = "",
                writable = true,
                canEmbedCover = true,
            )
        }

        val body = readAt(fileSystem, path, 10L, minOf(tagSize, (size - 10).toInt().coerceAtLeast(0)))
        val bodyData = if ((flags and 0x80) != 0) removeUnsync(body) else body
        val contentStart = if ((flags and 0x40) != 0) extendedHeaderOffset(bodyData, version) else 0

        val tags = mutableMapOf<String, String>()
        var cover: Pair<ByteArray, String>? = null

        var offset = contentStart
        val frameHeaderSize = if (version == 2) 6 else 10
        while (offset + frameHeaderSize <= bodyData.size) {
            val frameId = bodyData.safeSlice(offset, offset + 4).decodeToString().trimEnd()
            if (frameId.isEmpty() || !frameId.all { it.isLetterOrDigit() }) break

            val frameSize: Int
            val frameFlags: Int
            if (version == 2) {
                frameSize = ((bodyData[offset + 3].toInt() and 0xFF) shl 16) or
                    ((bodyData[offset + 4].toInt() and 0xFF) shl 8) or
                    (bodyData[offset + 5].toInt() and 0xFF)
                frameFlags = 0
            } else {
                val raw = ((bodyData[offset + 4].toInt() and 0xFF) shl 24) or
                    ((bodyData[offset + 5].toInt() and 0xFF) shl 16) or
                    ((bodyData[offset + 6].toInt() and 0xFF) shl 8) or
                    (bodyData[offset + 7].toInt() and 0xFF)
                // v2.4 的帧长度是 syncsafe
                frameSize = if (version >= 4) syncSafe(bodyData, offset + 4, 4) else raw
                frameFlags = ((bodyData[offset + 8].toInt() and 0xFF) shl 8) or (bodyData[offset + 9].toInt() and 0xFF)
            }

            val dataStart = offset + frameHeaderSize
            val dataEnd = dataStart + frameSize
            if (frameSize <= 0 || dataEnd > bodyData.size) break

            var payload = bodyData.safeSlice(dataStart, dataEnd)
            // 数据长度指示器 / 压缩 / 加密的帧直接跳过（很少见）
            if (version >= 3) {
                if ((frameFlags and 0x0001) != 0 || (frameFlags and 0x0040) != 0) {
                    offset = dataEnd
                    continue
                }
                if ((frameFlags and 0x0002) != 0) {
                    payload = removeUnsync(payload)
                }
                if (version >= 4 && (frameFlags and 0x0001) != 0 && payload.isNotEmpty()) {
                    payload = payload.safeSlice(4, payload.size)
                }
            }

            val key = textFrames[frameId]
            if (key != null) {
                val text = decodeTextFrame(payload)
                if (text.isNotEmpty()) tags[key] = text
            } else if (frameId == USLT) {
                val text = decodeTextFrame(payload, skipLanguageAndDescriptor = true)
                if (text.isNotEmpty()) tags["lyrics"] = text
            } else if (frameId == APIC) {
                cover = decodeApic(payload)
            } else if (frameId in setOf("COMM", "COM")) {
                // 注释帧暂不入库
            }
            offset = dataEnd
        }

        val info = Mp3Info.parse(fileSystem, path, 10L + tagSize)
        val coverMd5 = cover?.let { Crypto.md5(it.first).toHexString() } ?: ""

        return TagSnapshot(
            path = path.toString(),
            format = "MP3",
            durationSeconds = info.durationSeconds,
            bitrate = info.bitrate,
            sampleRate = info.sampleRate,
            channels = info.channels,
            bitsPerSample = 0,
            tags = tags,
            hasCover = cover != null,
            coverMime = cover?.second ?: "",
            coverSize = cover?.first?.size ?: 0,
            coverMd5 = coverMd5,
            writable = true,
            canEmbedCover = true,
        )
    }

    fun readCover(fileSystem: FileSystem, path: Path): Pair<ByteArray, String>? {
        val snapshot = read(fileSystem, path)
        if (!snapshot.hasCover) return null
        // 重新解析一次取原始字节
        val size = fileSystem.metadata(path).size ?: return null
        val head = readAt(fileSystem, path, 0, 10)
        if (head.size < 10) return null
        val tagSize = syncSafe(head, 6, 4)
        if (tagSize <= 0) return null
        val body = readAt(fileSystem, path, 10L, minOf(tagSize, (size - 10).toInt().coerceAtLeast(0)))
        val version = head[3].toInt() and 0xFF
        var offset = 0
        val frameHeaderSize = if (version == 2) 6 else 10
        while (offset + frameHeaderSize <= body.size) {
            val frameId = body.safeSlice(offset, offset + 4).decodeToString()
            val frameSize = if (version == 2) {
                ((body[offset + 3].toInt() and 0xFF) shl 16) or
                    ((body[offset + 4].toInt() and 0xFF) shl 8) or (body[offset + 5].toInt() and 0xFF)
            } else if (version >= 4) {
                syncSafe(body, offset + 4, 4)
            } else {
                ((body[offset + 4].toInt() and 0xFF) shl 24) or ((body[offset + 5].toInt() and 0xFF) shl 16) or
                    ((body[offset + 6].toInt() and 0xFF) shl 8) or (body[offset + 7].toInt() and 0xFF)
            }
            if (frameSize <= 0 || offset + frameHeaderSize + frameSize > body.size) break
            if (frameId == APIC) {
                return decodeApic(body.safeSlice(offset + frameHeaderSize, offset + frameHeaderSize + frameSize))
            }
            offset += frameHeaderSize + frameSize
        }
        return null
    }

    // ---------------------------------------------------------------- 写入

    fun write(
        fileSystem: FileSystem,
        path: Path,
        spec: Map<String, String>,
        cover: ByteArray?,
        coverMime: String,
    ) {
        val size = fileSystem.metadata(path).size ?: error("无法读取文件大小")
        val head = readAt(fileSystem, path, 0, 10)
        val hasId3 = head.size >= 10 && head[0] == 'I'.code.toByte() &&
            head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()
        val oldTagSize = if (hasId3) syncSafe(head, 6, 4) else 0
        val audioStart = if (hasId3) 10L + oldTagSize else 0L
        if (audioStart > size) error("ID3 标签长度异常")

        // 读现有帧，保留未改动的
        val existing = mutableMapOf<String, ByteArray>()
        var existingCover: Pair<ByteArray, String>? = null
        if (hasId3) {
            collectFrames(fileSystem, path, head[3].toInt() and 0xFF, oldTagSize, existing) { existingCover = it }
        }

        // 写入新值
        for ((key, value) in spec) {
            val frameId = writeFrames[key]
            if (frameId != null) {
                if (value.isEmpty()) existing.remove(frameId) else existing[frameId] = textFrame(frameId, value)
            } else if (key == "lyrics") {
                if (value.isEmpty()) existing.remove(USLT) else existing[USLT] = lyricsFrame(value)
            } else if (key == "comment") {
                if (value.isEmpty()) existing.remove("COMM") else existing["COMM"] = commentFrame(value)
            }
        }
        if (cover != null) {
            existing[APIC] = apicFrame(cover, coverMime.ifEmpty { cover.mime() })
        }

        val body = Buffer()
        for ((frameId, frame) in existing) {
            body.write(frame)
        }
        val bodyBytes = body.readByteArray()
        val padded = padTo(bodyBytes, 2048)

        val header = Buffer()
        header.write("ID3".encodeToByteArray())
        header.writeByte(3) // v2.3
        header.writeByte(0)
        header.writeByte(0) // flags
        header.write(syncSafeBytes(padded.size))
        header.write(padded)

        writeReplaced(fileSystem, path, header.readByteArray(), audioStart, size)
    }

    /** 用新的头部字节替换文件原来的头部区（audioStart 之前的部分）。 */
    private fun writeReplaced(fileSystem: FileSystem, path: Path, newHeader: ByteArray, audioStart: Long, size: Long) {
        val temporary = (path.parent ?: error("无父目录")) / (path.name + ".tagger-tmp")
        try {
            fileSystem.sink(temporary).useCloseable { sink ->
                sink.write(Buffer().write(newHeader), newHeader.size.toLong())
                if (size > audioStart) {
                    val audio = readAt(fileSystem, path, audioStart, (size - audioStart).toInt().coerceAtLeast(0))
                    sink.write(Buffer().write(audio), audio.size.toLong())
                }
            }
            // okio 的多平台没有原子 move，先删后移（备份机制已保证可恢复）
            fileSystem.delete(path, mustExist = false)
            fileSystem.atomicMove(temporary, path)
        } catch (ex: Exception) {
            fileSystem.delete(temporary, mustExist = false)
            throw ex
        }
    }

    private fun collectFrames(
        fileSystem: FileSystem,
        path: Path,
        version: Int,
        tagSize: Int,
        out: MutableMap<String, ByteArray>,
        onCover: (Pair<ByteArray, String>) -> Unit,
    ) {
        if (tagSize <= 0) return
        val body = readAt(fileSystem, path, 10L, tagSize)
        var offset = 0
        val frameHeaderSize = if (version == 2) 6 else 10
        while (offset + frameHeaderSize <= body.size) {
            val frameId = body.safeSlice(offset, offset + 4).decodeToString()
            if (frameId.isEmpty() || !frameId.all { it.isLetterOrDigit() }) break
            val frameSize = if (version == 2) {
                ((body[offset + 3].toInt() and 0xFF) shl 16) or
                    ((body[offset + 4].toInt() and 0xFF) shl 8) or (body[offset + 5].toInt() and 0xFF)
            } else if (version >= 4) {
                syncSafe(body, offset + 4, 4)
            } else {
                ((body[offset + 4].toInt() and 0xFF) shl 24) or ((body[offset + 5].toInt() and 0xFF) shl 16) or
                    ((body[offset + 6].toInt() and 0xFF) shl 8) or (body[offset + 7].toInt() and 0xFF)
            }
            if (frameSize <= 0 || offset + frameHeaderSize + frameSize > body.size) break
            val frameBytes = body.safeSlice(offset, offset + frameHeaderSize + frameSize)
            // 保留所有帧（含文本帧），写入时只覆盖被修改的那几个；
            // 否则第二次写入会把之前写好的标题 / 专辑等全部清空。
            if (frameId == APIC) {
                decodeApic(body.safeSlice(offset + frameHeaderSize, offset + frameHeaderSize + frameSize))?.let(onCover)
            } else if (frameId !in out) {
                out[frameId] = frameBytes
            }
            offset += frameHeaderSize + frameSize
        }
    }

    // ---------------------------------------------------------------- 帧构造

    private fun syncSafeBytes(value: Int): ByteArray = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )

    private fun frame(id: String, payload: ByteArray): ByteArray {
        val buffer = Buffer()
        buffer.write(id.encodeToByteArray())
        buffer.writeByte((payload.size shr 24) and 0xFF)
        buffer.writeByte((payload.size shr 16) and 0xFF)
        buffer.writeByte((payload.size shr 8) and 0xFF)
        buffer.writeByte(payload.size and 0xFF)
        buffer.writeByte(0)
        buffer.writeByte(0)
        buffer.write(payload)
        return buffer.readByteArray()
    }

    /** UTF-16（带 BOM）文本帧：对中文最稳。 */

    private fun textFrame(id: String, value: String): ByteArray {
        val payload = Buffer()
        payload.writeByte(1) // encoding: UTF-16 with BOM
        payload.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        payload.write(value.encodeToByteArray().toUtf16Le())
        return frame(id, payload.readByteArray())
    }

    private fun lyricsFrame(value: String): ByteArray {
        val payload = Buffer()
        payload.writeByte(1) // UTF-16
        payload.write("eng".encodeToByteArray())
        payload.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        // 空描述符（UTF-16 下是两个 0）
        payload.write(byteArrayOf(0x00, 0x00))
        payload.write(value.encodeToByteArray().toUtf16Le())
        return frame(USLT, payload.readByteArray())
    }

    private fun commentFrame(value: String): ByteArray {
        val payload = Buffer()
        payload.writeByte(1)
        payload.write("eng".encodeToByteArray())
        payload.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        payload.write(byteArrayOf(0x00, 0x00))
        payload.write(value.encodeToByteArray().toUtf16Le())
        return frame("COMM", payload.readByteArray())
    }

    private fun apicFrame(cover: ByteArray, mime: String): ByteArray {
        val payload = Buffer()
        payload.writeByte(0) // ISO-8859-1 for mime/desc
        payload.write(mime.encodeToByteArray())
        payload.writeByte(0)
        payload.writeByte(3) // front cover
        payload.writeByte(0) // empty desc
        payload.write(cover)
        return frame(APIC, payload.readByteArray())
    }

    private fun padTo(data: ByteArray, multiple: Int): ByteArray {
        val target = ((data.size + multiple - 1) / multiple) * multiple
        if (target == data.size) return data
        val out = ByteArray(target)
        data.copyInto(out)
        return out
    }

    // ---------------------------------------------------------------- 解码

    private fun decodeTextFrame(payload: ByteArray, skipLanguageAndDescriptor: Boolean = false): String {
        if (payload.isEmpty()) return ""
        val encoding = payload[0].toInt() and 0xFF
        var start = 1
        if (skipLanguageAndDescriptor) {
            if (payload.size < 4) return ""
            start = 4 // 3 字节语言码
            // 描述符
            start = if (encoding == 1 || encoding == 2) {
                // UTF-16：找到双零终止
                var i = start
                while (i + 1 < payload.size) {
                    if (payload[i].toInt() == 0 && payload[i + 1].toInt() == 0) {
                        i += 2
                        break
                    }
                    i += 2
                }
                i
            } else {
                var i = start
                while (i < payload.size && payload[i].toInt() != 0) i++
                i + 1
            }
        }
        if (start >= payload.size) return ""
        return decodeWithEncoding(encoding, payload.safeSlice(start, payload.size)).trimEnd('\u0000')
    }

    private fun decodeWithEncoding(encoding: Int, data: ByteArray): String = when (encoding) {
        0 -> data.decodeToString() // ISO-8859-1，尽量按 UTF-8 读取
        1 -> decodeUtf16(data, littleEndian = true)
        2 -> decodeUtf16(data, littleEndian = false)
        3 -> data.decodeToString()
        else -> data.decodeToString()
    }

    private fun decodeUtf16(data: ByteArray, littleEndian: Boolean): String {
        var start = 0
        var little = littleEndian
        if (data.size >= 2) {
            val b0 = data[0].toInt() and 0xFF
            val b1 = data[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) {
                little = true
                start = 2
            } else if (b0 == 0xFE && b1 == 0xFF) {
                little = false
                start = 2
            }
        }
        val chars = ArrayList<Char>((data.size - start) / 2)
        var i = start
        while (i + 1 < data.size) {
            val code = if (little) {
                (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)
            } else {
                ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            }
            if (code == 0) break
            chars.add(code.toChar())
            i += 2
        }
        return chars.joinToString("")
    }

    private fun decodeApic(payload: ByteArray): Pair<ByteArray, String>? {
        if (payload.isEmpty()) return null
        val encoding = payload[0].toInt() and 0xFF
        var offset = 1
        // MIME
        val mimeEnd = payload.indexOfZero(offset)
        if (mimeEnd < 0) return null
        val mime = payload.safeSlice(offset, mimeEnd).decodeToString()
        offset = mimeEnd + 1
        if (offset >= payload.size) return null
        offset++ // picture type
        // 描述符
        offset = if (encoding == 1 || encoding == 2) {
            var i = offset
            while (i + 1 < payload.size) {
                if (payload[i].toInt() == 0 && payload[i + 1].toInt() == 0) {
                    i += 2
                    break
                }
                i += 2
            }
            i
        } else {
            val end = payload.indexOfZero(offset)
            if (end < 0) payload.size else end + 1
        }
        if (offset >= payload.size) return null
        val data = payload.safeSlice(offset, payload.size)
        if (data.isEmpty()) return null
        return data to mime.ifEmpty { data.mime() }
    }

    private fun ByteArray.indexOfZero(from: Int): Int {
        for (i in from until size) if (this[i].toInt() == 0) return i
        return -1
    }

    private fun removeUnsync(data: ByteArray): ByteArray {
        val out = Buffer()
        var i = 0
        while (i < data.size) {
            val value = data[i].toInt() and 0xFF
            out.writeByte(value)
            if (value == 0xFF && i + 1 < data.size && data[i + 1].toInt() == 0) {
                i += 2 // 跳过填充的 0
            } else {
                i++
            }
        }
        return out.readByteArray()
    }

    private fun extendedHeaderOffset(body: ByteArray, version: Int): Int {
        if (body.size < 4) return 0
        return if (version >= 4) {
            val size = syncSafe(body, 0, 4)
            size.coerceAtMost(body.size)
        } else {
            val size = ((body[0].toInt() and 0xFF) shl 24) or ((body[1].toInt() and 0xFF) shl 16) or
                ((body[2].toInt() and 0xFF) shl 8) or (body[3].toInt() and 0xFF)
            (size + 4).coerceAtMost(body.size)
        }
    }

    // ---------------------------------------------------------------- 工具

    internal fun syncSafe(data: ByteArray, offset: Int, length: Int): Int {
        var value = 0
        for (i in 0 until length) {
            if (offset + i >= data.size) break
            value = (value shl 7) or (data[offset + i].toInt() and 0x7F)
        }
        return value
    }

    internal fun readAt(fileSystem: FileSystem, path: Path, offset: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val buffer = Buffer()
        fileSystem.source(path).useCloseable { source ->
            skipSource(source, offset)
            var remaining = length.toLong()
            while (remaining > 0) {
                val read = source.read(buffer, remaining)
                if (read <= 0) break
                remaining -= read
            }
        }
        return buffer.readByteArray()
    }

    private fun emptySnapshot(path: Path, error: String) = TagSnapshot(
        path = path.toString(),
        format = "MP3",
        durationSeconds = 0.0,
        bitrate = 0,
        sampleRate = 0,
        channels = 0,
        bitsPerSample = 0,
        tags = emptyMap(),
        hasCover = false,
        coverMime = "",
        coverSize = 0,
        coverMd5 = "",
        writable = true,
        canEmbedCover = true,
        error = error,
    )
}

/** 把 UTF-8 字节按 UTF-16LE 编码（ID3 文本帧用）。 */
internal fun ByteArray.toUtf16Le(): ByteArray {
    val text = this.decodeToString()
    val out = ByteArray(text.length * 2)
    for (i in text.indices) {
        val code = text[i].code
        out[i * 2] = (code and 0xFF).toByte()
        out[i * 2 + 1] = ((code shr 8) and 0xFF).toByte()
    }
    return out
}

/**
 * 估算 MP3 的时长/码率：解析第一帧头，按 CBR 估算。
 * 只用于界面展示，不追求精确。
 */
internal object Mp3Info {

    data class Info(val durationSeconds: Double, val bitrate: Int, val sampleRate: Int, val channels: Int)

    private val bitratesV1L3 = intArrayOf(
        0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0,
    )
    private val bitratesV2L3 = intArrayOf(
        0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0,
    )
    private val sampleRates = mapOf(
        3 to intArrayOf(44100, 48000, 32000),
        2 to intArrayOf(22050, 24000, 16000),
        0 to intArrayOf(11025, 12000, 8000),
    )

    fun parse(fileSystem: FileSystem, path: Path, startOffset: Long): Info {
        return try {
            val size = fileSystem.metadata(path).size ?: 0L
            val probe = Id3v2.readAt(fileSystem, path, startOffset, 64 * 1024)
            var i = 0
            while (i + 4 < probe.size) {
                if ((probe[i].toInt() and 0xFF) == 0xFF && (probe[i + 1].toInt() and 0xFF) and 0xE0 == 0xE0) {
                    val b1 = probe[i + 1].toInt() and 0xFF
                    val b2 = probe[i + 2].toInt() and 0xFF
                    val b3 = probe[i + 3].toInt() and 0xFF
                    val versionBits = (b1 shr 3) and 0x03
                    val layerBits = (b1 shr 1) and 0x03
                    val bitrateIndex = (b2 shr 4) and 0x0F
                    val sampleIndex = (b2 shr 2) and 0x03
                    val padding = (b2 shr 1) and 0x01
                    val channelMode = (b3 shr 6) and 0x03

                    val rates = sampleRates[versionBits]
                    if (rates != null && bitrateIndex in 1..14 && sampleIndex < 3) {
                        val sampleRate = rates[sampleIndex]
                        val bitrateTable = if (versionBits == 3) bitratesV1L3 else bitratesV2L3
                        val bitrate = bitrateTable[bitrateIndex]
                        val channels = if (channelMode == 3) 1 else 2
                        val frameLength = if (versionBits == 3) {
                            144 * bitrate * 1000 / sampleRate + padding
                        } else {
                            72 * bitrate * 1000 / sampleRate + padding
                        }
                        val audioBytes = (size - startOffset).coerceAtLeast(0)
                        val frames = if (frameLength > 0) audioBytes / frameLength else 0
                        val samplesPerFrame = if (versionBits == 3) 1152 else 576
                        val duration = if (sampleRate > 0) frames * samplesPerFrame.toDouble() / sampleRate else 0.0
                        return Info(duration, bitrate, sampleRate, channels)
                    }
                }
                i++
            }
            Info(0.0, 0, 0, 0)
        } catch (ex: Exception) {
            Info(0.0, 0, 0, 0)
        }
    }
}

