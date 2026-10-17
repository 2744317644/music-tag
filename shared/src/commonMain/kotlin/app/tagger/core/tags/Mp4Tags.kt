package app.tagger.core.tags

import app.tagger.core.Crypto

import okio.Buffer
import okio.FileSystem
import okio.Path

/**
 * MP4/M4A 标签读写（iTunes 风格 ilst 原子）。
 *
 * 只重建 moov/udta/meta/ilst 这一棵子树，音频数据的 mdat 原样保留。
 * 支持的原子：
 *   ©nam 标题  ©ART 艺术家  ©alb 专辑  aART 专辑艺术家
 *   ©day 日期  ©gen 风格   ©wrt 作曲  cprt 版权
 *   ©lyr 歌词  trkn 音轨   disk 碟号   covr 封面
 */
internal object Mp4Tags {

    private const val ATOM_FTYP = "ftyp"
    private const val ATOM_MOOV = "moov"
    private const val ATOM_UDTA = "udta"
    private const val ATOM_META = "meta"
    private const val ATOM_ILST = "ilst"
    private const val ATOM_DATA = "data"
    private const val ATOM_HDLR = "hdlr"

    // 字段 -> ilst 原子名
    private val fieldAtoms = mapOf(
        "title" to "\u00A9nam",
        "artist" to "\u00A9ART",
        "album" to "\u00A9alb",
        "album_artist" to "aART",
        "year" to "\u00A9day",
        "genre" to "\u00A9gen",
        "composer" to "\u00A9wrt",
        "copyright" to "cprt",
        "comment" to "\u00A9cmt",
        "lyrics" to "\u00A9lyr",
    )

    private const val ATOM_TRACK = "trkn"
    private const val ATOM_DISC = "disk"
    private const val ATOM_COVER = "covr"

    private const val DATA_TYPE_IMPLICIT = 0
    private const val DATA_TYPE_UTF8 = 1
    private const val DATA_TYPE_JPEG = 13
    private const val DATA_TYPE_PNG = 14

    // ---------------------------------------------------------------- 读取

    fun read(fileSystem: FileSystem, path: Path): TagSnapshot {
        val size = fileSystem.metadata(path).size ?: error("无法读取文件大小")
        if (size < 16) return errorSnapshot(path, "文件过小")

        val top = parseAtoms(fileSystem, path, 0, size)
        val moov = top.firstOrNull { it.type == ATOM_MOOV } ?: return errorSnapshot(path, "不是有效的 MP4/M4A（缺少 moov）")
        val moovChildren = parseAtoms(fileSystem, path, moov.payloadStart, moov.payloadEnd)
        val udta = moovChildren.firstOrNull { it.type == ATOM_UDTA }
        val ilst = udta?.let {
            val udtaChildren = parseAtoms(fileSystem, path, it.payloadStart, it.payloadEnd)
            udtaChildren.firstOrNull { child -> child.type == ATOM_META }
                ?.let { meta ->
                    // meta 是 full box：跳过 4 字节版本/标志
                    val metaChildren = parseAtoms(fileSystem, path, meta.payloadStart + 4, meta.payloadEnd)
                    metaChildren.firstOrNull { child -> child.type == ATOM_ILST }
                }
        }

        val tags = mutableMapOf<String, String>()
        var cover: Pair<ByteArray, String>? = null
        val reverse = fieldAtoms.entries.associate { (field, atom) -> atom to field }
        if (ilst != null) {
            for (item in parseAtoms(fileSystem, path, ilst.payloadStart, ilst.payloadEnd)) {
                val dataAtoms = parseAtoms(fileSystem, path, item.payloadStart, item.payloadEnd)
                    .filter { it.type == ATOM_DATA }
                if (dataAtoms.isEmpty()) continue
                val dataAtom = dataAtoms.first()
                val header = Vorbis.readRange(fileSystem, path, dataAtom.payloadStart, 8)
                if (header.size < 8) continue
                val dataType = ((header[0].toInt() and 0xFF) shl 24) or
                    ((header[1].toInt() and 0xFF) shl 16) or
                    ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
                val payloadStart = dataAtom.payloadStart + 8
                val payloadLength = (dataAtom.payloadEnd - payloadStart).toInt()
                if (payloadLength <= 0) continue
                val payload = Vorbis.readRange(fileSystem, path, payloadStart, payloadLength)

                when (item.type) {
                    ATOM_COVER -> {
                        val mime = when (dataType) {
                            DATA_TYPE_PNG -> "image/png"
                            DATA_TYPE_JPEG -> "image/jpeg"
                            else -> payload.mime()
                        }
                        if (cover == null) cover = payload to mime
                    }
                    ATOM_TRACK -> {
                        if (payload.size >= 4) {
                            val number = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
                            val total = if (payload.size >= 6) {
                                ((payload[4].toInt() and 0xFF) shl 8) or (payload[5].toInt() and 0xFF)
                            } else 0
                            if (number > 0) tags["track"] = if (total > 0) "$number/$total" else "$number"
                        }
                    }
                    ATOM_DISC -> {
                        if (payload.size >= 4) {
                            val number = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
                            val total = if (payload.size >= 6) {
                                ((payload[4].toInt() and 0xFF) shl 8) or (payload[5].toInt() and 0xFF)
                            } else 0
                            if (number > 0) tags["disc"] = if (total > 0) "$number/$total" else "$number"
                        }
                    }
                    else -> {
                        val field = reverse[item.type] ?: continue
                        val text = payload.decodeToString().trimEnd('\u0000')
                        if (text.isNotEmpty()) tags[field] = text
                    }
                }
            }
        }

        val info = Mp4Info.parse(fileSystem, path, moov)
        return TagSnapshot(
            path = path.toString(),
            format = "M4A",
            durationSeconds = info.durationSeconds,
            bitrate = info.bitrate,
            sampleRate = info.sampleRate,
            channels = info.channels,
            bitsPerSample = info.bitsPerSample,
            tags = tags,
            hasCover = cover != null,
            coverMime = cover?.second ?: "",
            coverSize = cover?.first?.size ?: 0,
            coverMd5 = cover?.let { Crypto.md5(it.first).toHexString() } ?: "",
            writable = true,
            canEmbedCover = true,
        )
    }

    fun readCover(fileSystem: FileSystem, path: Path): Pair<ByteArray, String>? {
        val snapshot = read(fileSystem, path)
        if (!snapshot.hasCover) return null
        // 简化实现：再走一遍 ilst 解析
        val size = fileSystem.metadata(path).size ?: return null
        val top = parseAtoms(fileSystem, path, 0, size)
        val moov = top.firstOrNull { it.type == ATOM_MOOV } ?: return null
        val moovChildren = parseAtoms(fileSystem, path, moov.payloadStart, moov.payloadEnd)
        val udta = moovChildren.firstOrNull { it.type == ATOM_UDTA } ?: return null
        val udtaChildren = parseAtoms(fileSystem, path, udta.payloadStart, udta.payloadEnd)
        val meta = udtaChildren.firstOrNull { it.type == ATOM_META } ?: return null
        val metaChildren = parseAtoms(fileSystem, path, meta.payloadStart + 4, meta.payloadEnd)
        val ilst = metaChildren.firstOrNull { it.type == ATOM_ILST } ?: return null
        for (item in parseAtoms(fileSystem, path, ilst.payloadStart, ilst.payloadEnd)) {
            if (item.type != ATOM_COVER) continue
            val dataAtom = parseAtoms(fileSystem, path, item.payloadStart, item.payloadEnd)
                .firstOrNull { it.type == ATOM_DATA } ?: continue
            val header = Vorbis.readRange(fileSystem, path, dataAtom.payloadStart, 8)
            if (header.size < 8) continue
            val dataType = ((header[0].toInt() and 0xFF) shl 24) or
                ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            val payloadStart = dataAtom.payloadStart + 8
            val payloadLength = (dataAtom.payloadEnd - payloadStart).toInt()
            if (payloadLength <= 0) continue
            val payload = Vorbis.readRange(fileSystem, path, payloadStart, payloadLength)
            val mime = when (dataType) {
                DATA_TYPE_PNG -> "image/png"
                DATA_TYPE_JPEG -> "image/jpeg"
                else -> payload.mime()
            }
            return payload to mime
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
        val top = parseAtoms(fileSystem, path, 0, size)
        val moov = top.firstOrNull { it.type == ATOM_MOOV } ?: error("不是有效的 MP4/M4A")

        val moovChildren = parseAtoms(fileSystem, path, moov.payloadStart, moov.payloadEnd)
        val udta = moovChildren.firstOrNull { it.type == ATOM_UDTA }
        var metaChildren = emptyList<Atom>()
        var existingIlst: Atom? = null
        if (udta != null) {
            val udtaChildren = parseAtoms(fileSystem, path, udta.payloadStart, udta.payloadEnd)
            val meta = udtaChildren.firstOrNull { it.type == ATOM_META }
            if (meta != null) {
                metaChildren = parseAtoms(fileSystem, path, meta.payloadStart + 4, meta.payloadEnd)
                existingIlst = metaChildren.firstOrNull { it.type == ATOM_ILST }
            }
        }

        // 现有 ilst 条目：原子名 -> (数据类型, 原始 payload)
        val existing = LinkedHashMap<String, IlstEntry>()
        if (existingIlst != null) {
            for (item in parseAtoms(fileSystem, path, existingIlst.payloadStart, existingIlst.payloadEnd)) {
                val dataAtom = parseAtoms(fileSystem, path, item.payloadStart, item.payloadEnd)
                    .firstOrNull { it.type == ATOM_DATA } ?: continue
                val header = Vorbis.readRange(fileSystem, path, dataAtom.payloadStart, 8)
                if (header.size < 8) continue
                val dataType = ((header[0].toInt() and 0xFF) shl 24) or
                    ((header[1].toInt() and 0xFF) shl 16) or
                    ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
                val payloadStart = dataAtom.payloadStart + 8
                val payloadLength = (dataAtom.payloadEnd - payloadStart).toInt()
                if (payloadLength <= 0) continue
                val payload = Vorbis.readRange(fileSystem, path, payloadStart, payloadLength)
                existing[item.type] = IlstEntry(dataType and 0xFF, payload)
            }
        }

        // 应用修改
        for ((field, value) in spec) {
            val atom = fieldAtoms[field] ?: continue
            if (value.isEmpty()) {
                existing.remove(atom)
            } else {
                existing[atom] = IlstEntry(DATA_TYPE_UTF8, value.encodeToByteArray())
            }
        }
        spec["track"]?.let { value ->
            if (value.isEmpty()) existing.remove(ATOM_TRACK)
            else existing[ATOM_TRACK] = IlstEntry(DATA_TYPE_IMPLICIT, pairPayload(value, trackLike = true))
        }
        spec["disc"]?.let { value ->
            if (value.isEmpty()) existing.remove(ATOM_DISC)
            else existing[ATOM_DISC] = IlstEntry(DATA_TYPE_IMPLICIT, pairPayload(value, trackLike = false))
        }
        if (cover != null) {
            val typeByte = if (coverMime.contains("png", ignoreCase = true)) DATA_TYPE_PNG else DATA_TYPE_JPEG
            existing[ATOM_COVER] = IlstEntry(typeByte, cover)
        }

        // 构造新的 ilst：每个条目 = [长度][原子名][data 原子]
        val ilstBuffer = Buffer()
        for ((atom, entry) in existing) {
            val dataBuffer = Buffer()
            dataBuffer.writeInt(4)          // 类型
            dataBuffer.writeInt(0)          // locale
            dataBuffer.write(entry.payload)
            val dataPayload = dataBuffer.readByteArray()
            val dataAtomLength = 8 + dataPayload.size

            val itemBuffer = Buffer()
            itemBuffer.writeInt(8 + dataAtomLength)
            itemBuffer.write(atomToBytes(atom))
            itemBuffer.writeInt(dataAtomLength)
            itemBuffer.write(atomToBytes(ATOM_DATA))
            itemBuffer.write(dataPayload)
            ilstBuffer.write(itemBuffer.readByteArray())
        }
        val ilstBytes = ilstBuffer.readByteArray()

        // 重建 meta：保留已有的 hdlr 等非 ilst 子原子
        val metaBuffer = Buffer()
        metaBuffer.writeInt(0) // version + flags
        var hasHdlr = false
        for (child in metaChildren) {
            if (child.type == ATOM_ILST) continue
            if (child.type == ATOM_HDLR) hasHdlr = true
            val bytes = Vorbis.readRange(fileSystem, path, child.start, (child.end - child.start).toInt())
            metaBuffer.write(bytes)
        }
        if (!hasHdlr) metaBuffer.write(defaultHdlr())
        // ilst 原子
        metaBuffer.writeInt(8 + ilstBytes.size)
        metaBuffer.write(atomToBytes(ATOM_ILST))
        metaBuffer.write(ilstBytes)
        val metaBytes = metaBuffer.readByteArray()

        // 重建 udta / moov
        val udtaBuffer = Buffer()
        var udtaReplaced = false
        if (udta != null) {
            val udtaChildren = parseAtoms(fileSystem, path, udta.payloadStart, udta.payloadEnd)
            for (child in udtaChildren) {
                if (child.type == ATOM_META) {
                    val metaFull = Buffer()
                    metaFull.writeInt(8 + metaBytes.size)
                    metaFull.write(atomToBytes(ATOM_META))
                    metaFull.write(metaBytes)
                    udtaBuffer.write(metaFull.readByteArray())
                    udtaReplaced = true
                } else {
                    udtaBuffer.write(Vorbis.readRange(fileSystem, path, child.start, (child.end - child.start).toInt()))
                }
            }
        }
        if (!udtaReplaced) {
            val metaFull = Buffer()
            metaFull.writeInt(8 + metaBytes.size)
            metaFull.write(atomToBytes(ATOM_META))
            metaFull.write(metaBytes)
            udtaBuffer.write(metaFull.readByteArray())
        }
        val udtaBytes = udtaBuffer.readByteArray()

        val moovBuffer = Buffer()
        var udtaWritten = false
        for (child in moovChildren) {
            if (child.type == ATOM_UDTA) {
                moovBuffer.writeInt(8 + udtaBytes.size)
                moovBuffer.write(atomToBytes(ATOM_UDTA))
                moovBuffer.write(udtaBytes)
                udtaWritten = true
            } else {
                moovBuffer.write(Vorbis.readRange(fileSystem, path, child.start, (child.end - child.start).toInt()))
            }
        }
        if (!udtaWritten) {
            moovBuffer.writeInt(8 + udtaBytes.size)
            moovBuffer.write(atomToBytes(ATOM_UDTA))
            moovBuffer.write(udtaBytes)
        }
        val moovBytes = moovBuffer.readByteArray()

        // 拼装文件：moov 之前的原子 + 新 moov + moov 之后的原子
        val output = Buffer()
        for (child in top) {
            if (child.type == ATOM_MOOV) {
                output.writeInt(8 + moovBytes.size)
                output.write(atomToBytes(ATOM_MOOV))
                output.write(moovBytes)
            } else {
                output.write(Vorbis.readRange(fileSystem, path, child.start, (child.end - child.start).toInt()))
            }
        }
        Vorbis.replaceFromOffset(fileSystem, path, output.readByteArray(), 0)
    }

    /** ilst 里的一个条目：数据类型 + 原始 payload 字节。 */
    private data class IlstEntry(val dataType: Int, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is IlstEntry && other.dataType == dataType && other.payload.contentEquals(payload)

        override fun hashCode(): Int = 31 * dataType + payload.contentHashCode()
    }

    /**
     * trkn / disk 在 MP4 里都是 8 字节：
     * [2 字节保留][2 字节序号][2 字节总数][2 字节保留]。
     */
    private fun pairPayload(value: String, trackLike: Boolean): ByteArray {
        val parts = value.split('/')
        val number = parts.getOrNull(0)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
        val total = parts.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
        val buffer = Buffer()
        buffer.writeByte(0)
        buffer.writeByte(0)
        buffer.writeByte((number shr 8) and 0xFF)
        buffer.writeByte(number and 0xFF)
        buffer.writeByte((total shr 8) and 0xFF)
        buffer.writeByte(total and 0xFF)
        buffer.writeByte(0)
        buffer.writeByte(0)
        return buffer.readByteArray()
    }

    private fun defaultHdlr(): ByteArray {
        val body = Buffer()
        body.writeInt(0) // version/flags
        body.writeInt(0) // pre_defined
        body.write("mdir".encodeToByteArray())
        body.write("appl".encodeToByteArray())
        body.writeInt(0)
        body.writeInt(0)
        body.writeByte(0)
        val bodyBytes = body.readByteArray()
        val full = Buffer()
        full.writeInt(8 + bodyBytes.size)
        full.write(atomToBytes("hdlr"))
        full.write(bodyBytes)
        return full.readByteArray()
    }

    // ---------------------------------------------------------------- 原子解析

    internal data class Atom(
        val type: String,
        val start: Long,
        val payloadStart: Long,
        val payloadEnd: Long,
    ) {
        val end: Long get() = payloadEnd
    }

    /** 原子名 → 4 字节：按 ISO-8859-1 逐字符取低字节，保证 © 只占 1 字节。 */
    private fun atomToBytes(atom: String): ByteArray =
        ByteArray(4) { index -> if (index < atom.length) (atom[index].code and 0xFF).toByte() else 0 }

    private fun parseAtoms(fileSystem: FileSystem, path: Path, start: Long, end: Long): List<Atom> {
        val atoms = mutableListOf<Atom>()
        var offset = start
        while (offset + 8 <= end) {
            val header = Vorbis.readRange(fileSystem, path, offset, 8)
            if (header.size < 8) break
            var size = ((header[0].toInt() and 0xFF) shl 24) or
                ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            val type = header.safeSlice(4, 8).toLatin1()
            if (!type.all { it.isLetterOrDigit() || it == '\u00A9' }) break

            var headerSize = 8L
            if (size == 1) {
                // 64 位长度
                val extended = Vorbis.readRange(fileSystem, path, offset + 8, 8)
                if (extended.size < 8) break
                var value = 0L
                for (i in 0 until 8) value = (value shl 8) or (extended[i].toLong() and 0xFF)
                size = value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                headerSize = 16
            } else if (size == 0) {
                size = (end - offset).toInt()
            }
            if (size < headerSize || offset + size > end) break
            atoms += Atom(type, offset, offset + headerSize, offset + size)
            offset += size
        }
        return atoms
    }

    private fun errorSnapshot(path: Path, message: String) = TagSnapshot(
        path = path.toString(),
        format = "M4A",
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

    /** 从 mvhd 里读时长（movie timescale）。 */
    internal object Mp4Info {
        data class Info(
            val durationSeconds: Double = 0.0,
            val bitrate: Int = 0,
            val sampleRate: Int = 0,
            val channels: Int = 0,
            val bitsPerSample: Int = 0,
        )

        fun parse(fileSystem: FileSystem, path: Path, moov: Atom): Info {
            return try {
                val children = parseAtoms(fileSystem, path, moov.payloadStart, moov.payloadEnd)
                val mvhd = children.firstOrNull { it.type == "mvhd" } ?: return Info()
                val length = (mvhd.payloadEnd - mvhd.payloadStart).toInt()
                if (length < 20) return Info()
                val data = Vorbis.readRange(fileSystem, path, mvhd.payloadStart, length)
                val version = data[0].toInt() and 0xFF
                var timescale: Long
                var duration: Long
                if (version == 1) {
                    if (data.size < 32) return Info()
                    timescale = readInt64(data, 20)
                    duration = readInt64(data, 24)
                } else {
                    timescale = readInt32(data, 12).toLong()
                    duration = readInt32(data, 16).toLong()
                }
                val seconds = if (timescale > 0) duration.toDouble() / timescale else 0.0
                Info(seconds)
            } catch (ex: Exception) {
                Info()
            }
        }

        private fun readInt32(data: ByteArray, offset: Int): Int {
            if (offset + 4 > data.size) return 0
            return ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
        }

        private fun readInt64(data: ByteArray, offset: Int): Long {
            if (offset + 8 > data.size) return 0
            var value = 0L
            for (i in 0 until 8) value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
            return value
        }
    }
}
