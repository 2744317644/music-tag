package app.tagger.core.tags

import app.tagger.core.useCloseable

import app.tagger.core.Crypto

import okio.Buffer
import okio.FileSystem
import okio.Path

/**
 * Vorbis Comment 读写（FLAC 与 OGG/Opus 共用）。
 *
 * FLAC 的结构是：fLaC + 一组 metadata block（最后一块标记 isLast），
 * 标签在 VORBIS_COMMENT（type 4）里，封面在 PICTURE（type 6）里。
 * 改写时只重建 metadata block 区，音频帧原样搬过去。
 */
internal object Vorbis {

    private const val BLOCK_STREAMINFO = 0
    private const val BLOCK_VORBIS_COMMENT = 4
    private const val BLOCK_PICTURE = 6

    // 字段 -> Vorbis 键
    private val keys = mapOf(
        "title" to "TITLE",
        "artist" to "ARTIST",
        "album" to "ALBUM",
        "album_artist" to "ALBUMARTIST",
        "track" to "TRACKNUMBER",
        "disc" to "DISCNUMBER",
        "year" to "DATE",
        "genre" to "GENRE",
        "composer" to "COMPOSER",
        "copyright" to "COPYRIGHT",
        "comment" to "COMMENT",
        "lyrics" to "LYRICS",
    )

    // ---------------------------------------------------------------- FLAC

    fun readFlac(fileSystem: FileSystem, path: Path): TagSnapshot {
        val blocks = parseFlacBlocks(fileSystem, path)
        // STREAMINFO 必须存在且有内容；缺失或为空说明文件结构损坏
        if (blocks.streamInfo == null || blocks.streamInfo.size < 18) {
            return errorSnapshot(path, "FLAC 结构损坏：缺少有效的 STREAMINFO 块")
        }

        val comments = parseVorbisComment(blocks.vorbisComment)
        val pictures = blocks.pictures
        val cover = pictures.firstOrNull()
        val info = parseStreamInfo(blocks.streamInfo)

        return TagSnapshot(
            path = path.toString(),
            format = "FLAC",
            durationSeconds = info.durationSeconds,
            bitrate = 0,
            sampleRate = info.sampleRate,
            channels = info.channels,
            bitsPerSample = info.bitsPerSample,
            tags = comments,
            hasCover = cover != null,
            coverMime = cover?.second ?: "",
            coverSize = cover?.first?.size ?: 0,
            coverMd5 = cover?.let { Crypto.md5(it.first).toHexString() } ?: "",
            writable = true,
            canEmbedCover = true,
        )
    }

    fun readFlacCover(fileSystem: FileSystem, path: Path): Pair<ByteArray, String>? =
        parseFlacBlocks(fileSystem, path).pictures.firstOrNull()

    fun writeFlac(
        fileSystem: FileSystem,
        path: Path,
        spec: Map<String, String>,
        cover: ByteArray?,
        coverMime: String,
    ) {
        val blocks = parseFlacBlocks(fileSystem, path)
        if (blocks.streamInfo == null) error("不是有效的 FLAC 文件")

                // 只保留原始大写键用于回写，避免把内部字段名也写进文件
        val comments = parseVorbisComment(blocks.vorbisComment)
            .filterKeys { it == it.uppercase() }
            .toMutableMap()
        for ((field, value) in spec) {
            val key = keys[field] ?: continue
            if (value.isEmpty()) comments.remove(key) else comments[key] = value
        }

        val pictures = if (cover != null) {
            listOf(lightweightPicture(cover, coverMime.ifEmpty { cover.mime() }))
        } else {
            blocks.pictures
        }

        // 重建 metadata 区：保留原始其它块（padding 丢掉）
        val output = Buffer()
        output.write("fLaC".encodeToByteArray())

        val blocksToWrite = ArrayList<Pair<Int, ByteArray>>()
        blocksToWrite += BLOCK_STREAMINFO to blocks.streamInfo!!
        blocksToWrite += BLOCK_VORBIS_COMMENT to buildVorbisComment(comments)
        for (picture in pictures) blocksToWrite += BLOCK_PICTURE to picture.first
        for ((type, data) in blocks.others) blocksToWrite += type to data

        blocksToWrite.forEachIndexed { index, (type, data) ->
            val isLast = index == blocksToWrite.lastIndex
            output.writeByte(if (isLast) type or 0x80 else type)
            output.writeByte((data.size shr 16) and 0xFF)
            output.writeByte((data.size shr 8) and 0xFF)
            output.writeByte(data.size and 0xFF)
            output.write(data)
        }

        replaceFromOffset(fileSystem, path, output.readByteArray(), blocks.audioStartOffset)
    }

    private class FlacBlocks(
        val streamInfo: ByteArray?,
        val vorbisComment: ByteArray?,
        val pictures: List<Pair<ByteArray, String>>,
        val others: List<Pair<Int, ByteArray>>,
        val audioStartOffset: Long,
    )

    private fun parseFlacBlocks(fileSystem: FileSystem, path: Path): FlacBlocks {
        val size = fileSystem.metadata(path).size ?: error("无法读取文件大小")
        val head = readRange(fileSystem, path, 0, minOf(size, 4L).toInt())
        if (head.size < 4 || head.decodeToString() != "fLaC") error("不是有效的 FLAC 文件")

        var offset = 4L
        var streamInfo: ByteArray? = null
        var vorbisComment: ByteArray? = null
        val pictures = mutableListOf<Pair<ByteArray, String>>()
        val others = mutableListOf<Pair<Int, ByteArray>>()

        while (offset + 4 <= size) {
            val header = readRange(fileSystem, path, offset, 4)
            if (header.size < 4) break
            val isLast = (header[0].toInt() and 0x80) != 0
            val type = header[0].toInt() and 0x7F
            val length = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            val dataStart = offset + 4
            if (dataStart + length > size) break
            val data = readRange(fileSystem, path, dataStart, length)
            when (type) {
                BLOCK_STREAMINFO -> streamInfo = data
                BLOCK_VORBIS_COMMENT -> vorbisComment = data
                BLOCK_PICTURE -> parsePicture(data)?.let { pictures += it }
                else -> others += type to data
            }
            offset = dataStart + length
            if (isLast) break
        }
        return FlacBlocks(streamInfo, vorbisComment, pictures, others, offset)
    }

    private fun parseStreamInfo(data: ByteArray): AudioStreamInfo {
        if (data.size < 18) return AudioStreamInfo()
        val sampleRate = ((data[10].toInt() and 0xFF) shl 12) or
            ((data[11].toInt() and 0xFF) shl 4) or
            ((data[12].toInt() and 0xFF) shr 4)
        val channels = (((data[12].toInt() and 0x0F) shl 1) or ((data[13].toInt() and 0xFF) shr 7)) + 1
        val bitsPerSample = (((data[13].toInt() and 0x7F) shr 3) shl 1) + 1 +
            (if ((data[13].toInt() and 0x04) != 0) 0 else 0)
        val totalSamples = ((data[13].toLong() and 0x0F) shl 32) or
            ((data[14].toLong() and 0xFF) shl 24) or
            ((data[15].toLong() and 0xFF) shl 16) or
            ((data[16].toLong() and 0xFF) shl 8) or
            (data[17].toLong() and 0xFF)
        val duration = if (sampleRate > 0) totalSamples.toDouble() / sampleRate else 0.0
        return AudioStreamInfo(duration, sampleRate, channels, bitsPerSample)
    }

    private fun parsePicture(data: ByteArray): Pair<ByteArray, String>? {
        if (data.size < 8) return null
        var offset = 0
        // type(4) + mime length(4)
        offset = 4
        val mimeLength = readInt(data, offset)
        offset += 4
        if (mimeLength < 0 || offset + mimeLength > data.size) return null
        val mime = data.safeSlice(offset, offset + mimeLength).decodeToString()
        offset += mimeLength
        // description
        val descLength = readInt(data, offset)
        offset += 4
        if (descLength < 0 || offset + descLength > data.size) return null
        offset += descLength
        // width(4) height(4) depth(4) colors(4) dataLength(4)
        if (offset + 20 > data.size) return null
        offset += 16
        val dataLength = readInt(data, offset)
        offset += 4
        if (dataLength < 0 || offset + dataLength > data.size) return null
        val image = data.safeSlice(offset, offset + dataLength)
        if (image.isEmpty()) return null
        return image to mime.ifEmpty { image.mime() }
    }

    private fun lightweightPicture(cover: ByteArray, mime: String): Pair<ByteArray, String> {
        // 这里返回的是"图片块数据（未加 block 头）"与 mime；写入时按块写出
        val buffer = Buffer()
        buffer.writeInt(3) // front cover
        val mimeBytes = mime.encodeToByteArray()
        buffer.writeInt(mimeBytes.size)
        buffer.write(mimeBytes)
        buffer.writeInt(0) // 空描述
        buffer.writeInt(0) // width
        buffer.writeInt(0) // height
        buffer.writeInt(0) // depth
        buffer.writeInt(0) // colors
        buffer.writeInt(cover.size)
        buffer.write(cover)
        return buffer.readByteArray() to mime
    }

    /** Vorbis 键（大写）反查内部字段名。 */
    private val reverseKeys: Map<String, String> = keys.entries.associate { (field, vorbisKey) -> vorbisKey to field }

    private fun parseVorbisComment(block: ByteArray?): Map<String, String> {
        if (block == null || block.size < 8) return emptyMap()
        val result = mutableMapOf<String, String>()
        var offset = 0
        val vendorLength = readIntLe(block, offset)
        offset += 4
        if (vendorLength < 0 || offset + vendorLength > block.size) return emptyMap()
        offset += vendorLength
        val count = readIntLe(block, offset)
        offset += 4
        repeat(count.coerceAtMost(10000)) {
            if (offset + 4 > block.size) return@repeat
            val length = readIntLe(block, offset)
            offset += 4
            if (length < 0 || offset + length > block.size) return@repeat
            val entry = block.safeSlice(offset, offset + length).decodeToString()
            offset += length
            val separator = entry.indexOf('=')
            if (separator > 0) {
                val key = entry.substring(0, separator).uppercase()
                val value = entry.substring(separator + 1)
                                if (key.isNotEmpty() && value.isNotEmpty() && key !in result) {
                    // 同时保留原始键与内部字段名，方便上层按字段读取
                    result[key] = value
                    val field = reverseKeys[key]
                    if (field != null && field !in result) result[field] = value
                }
            }
        }
        return result
    }

    private fun buildVorbisComment(comments: Map<String, String>): ByteArray {
        val buffer = Buffer()
        val vendor = "NeteaseTagger 1.0".encodeToByteArray()
        buffer.writeIntLe(vendor.size)
        buffer.write(vendor)
        val entries = comments.entries.filter { it.value.isNotEmpty() }
        buffer.writeIntLe(entries.size)
        for ((key, value) in entries) {
            val entry = "$key=$value".encodeToByteArray()
            buffer.writeIntLe(entry.size)
            buffer.write(entry)
        }
        return buffer.readByteArray()
    }

    private fun readInt(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return -1
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    private fun readIntLe(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return -1
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    // ---------------------------------------------------------------- OGG / Opus

    fun readOgg(fileSystem: FileSystem, path: Path): TagSnapshot {
        val size = fileSystem.metadata(path).size ?: error("无法读取文件大小")
        val data = readRange(fileSystem, path, 0, minOf(size, 1024L * 1024L).toInt())
        val comments = parseOggComments(data)
        val format = if (data.decodeToString(0, minOf(data.size, 64)).contains("OpusHead")) "OPUS" else "OGG"
        return TagSnapshot(
            path = path.toString(),
            format = format,
            durationSeconds = 0.0,
            bitrate = 0,
            sampleRate = 0,
            channels = 0,
            bitsPerSample = 0,
            tags = comments,
            hasCover = false,
            coverMime = "",
            coverSize = 0,
            coverMd5 = "",
            writable = true,
            canEmbedCover = false,
        )
    }

    /**
     * OGG 写入：在第一个 comment packet 位置用同样长度（必要时用 padding 补齐）替换，
     * 保持页面结构不变。复杂度较高，这里采用"只重写标签、长度完全相等"的策略，
     * 长度不一致时明确报错，避免破坏文件。
     */
    fun writeOgg(fileSystem: FileSystem, path: Path, spec: Map<String, String>) {
        val size = fileSystem.metadata(path).size ?: error("无法读取文件大小")
        val data = readRange(fileSystem, path, 0, size.toInt())
        val commentRange = findOggCommentRange(data) ?: error("未找到 OGG 注释块")
        val (start, end) = commentRange
        val existing = parseVorbisComment(data.safeSlice(start, end)).toMutableMap()
        for ((field, value) in spec) {
            val key = keys[field] ?: continue
            if (value.isEmpty()) existing.remove(key) else existing[key] = value
        }
        val rebuilt = buildVorbisComment(existing)
        val originalLength = end - start
        if (rebuilt.size != originalLength) {
            error("OGG 标签长度变化（$originalLength → ${rebuilt.size}），为避免损坏文件已中止；请改用 FLAC/MP3")
        }
        val output = data.copyOf()
        rebuilt.copyInto(output, start)
        replaceFromOffset(fileSystem, path, output, 0)
    }

    private fun findOggCommentRange(data: ByteArray): Pair<Int, Int>? {
        var offset = 0
        var packetIndex = 0
        while (offset + 27 < data.size) {
            if (data.safeSlice(offset, offset + 4).decodeToString() != "OggS") {
                offset++
                continue
            }
            val segmentCount = data[offset + 26].toInt() and 0xFF
            if (offset + 27 + segmentCount > data.size) return null
            var payloadStart = offset + 27 + segmentCount
            var payloadLength = 0
            for (i in 0 until segmentCount) {
                payloadLength += data[offset + 27 + i].toInt() and 0xFF
            }
            if (payloadStart + payloadLength > data.size) return null
            // 第二个 packet 是 comment（第一个是标识头）
            if (packetIndex == 1) {
                // Vorbis: 0x03 "vorbis" + vendor...；Opus: "OpusTags" + vendor...
                val head = data.safeSlice(payloadStart, minOf(payloadStart + 8, data.size))
                val isVorbisComment = head.size >= 7 && head[0].toInt() == 3 &&
                    head.decodeToString().contains("vorbis")
                val isOpusTags = head.decodeToString().startsWith("OpusTags")
                if (isVorbisComment) {
                    val vendorStart = payloadStart + 7
                    return vendorStart to payloadStart + payloadLength
                }
                if (isOpusTags) {
                    val vendorStart = payloadStart + 8
                    return vendorStart to payloadStart + payloadLength
                }
            }
            packetIndex++
            offset = payloadStart + payloadLength
        }
        return null
    }

    private fun parseOggComments(data: ByteArray): Map<String, String> {
        val range = findOggCommentRange(data) ?: return emptyMap()
        return parseVorbisComment(data.safeSlice(range.first, range.second))
    }

    // ---------------------------------------------------------------- 工具

    private data class AudioStreamInfo(
        val durationSeconds: Double = 0.0,
        val sampleRate: Int = 0,
        val channels: Int = 0,
        val bitsPerSample: Int = 0,
    )

    private fun errorSnapshot(path: Path, message: String) = TagSnapshot(
        path = path.toString(),
        format = "未知",
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
        writable = false,
        canEmbedCover = false,
        error = message,
    )

    /** 用 newHead 替换文件开头 [0, keepFrom) 的内容，其后原样保留。 */
    internal fun replaceFromOffset(fileSystem: FileSystem, path: Path, newHead: ByteArray, keepFrom: Long) {
        val size = fileSystem.metadata(path).size ?: error("无法读取文件大小")
        val tail = if (size > keepFrom) readRange(fileSystem, path, keepFrom, (size - keepFrom).toInt()) else ByteArray(0)
        val temporary = (path.parent ?: error("无父目录")) / (path.name + ".tagger-tmp")
        try {
            fileSystem.sink(temporary).useCloseable { sink ->
                sink.write(Buffer().write(newHead), newHead.size.toLong())
                if (tail.isNotEmpty()) sink.write(Buffer().write(tail), tail.size.toLong())
            }
            fileSystem.delete(path, mustExist = false)
            fileSystem.atomicMove(temporary, path)
        } catch (ex: Exception) {
            fileSystem.delete(temporary, mustExist = false)
            throw ex
        }
    }

    internal fun readRange(fileSystem: FileSystem, path: Path, offset: Long, length: Int): ByteArray {
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
}
