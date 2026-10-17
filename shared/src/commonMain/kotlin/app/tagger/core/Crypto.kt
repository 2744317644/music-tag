package app.tagger.core

/**
 * 编码与哈希工具：全部用 Kotlin 标准库实现，保证三端（Windows/Android/iOS）行为一致。
 */
object Crypto {

    private const val HEX = "0123456789abcdef"

    fun toHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    fun fromHex(text: String): ByteArray {
        val clean = text.filter { !it.isWhitespace() }
        require(clean.length % 2 == 0) { "十六进制长度必须是偶数" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    // ---------------------------------------------------------------- Base64

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun base64Encode(data: ByteArray): String {
        val out = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else -1
            out.append(B64[b0 ushr 2])
            if (b1 >= 0) {
                out.append(B64[((b0 and 0x03) shl 4) or (b1 ushr 4)])
                if (b2 >= 0) {
                    out.append(B64[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
                    out.append(B64[b2 and 0x3F])
                } else {
                    out.append(B64[(b1 and 0x0F) shl 2])
                    out.append('=')
                }
            } else {
                out.append(B64[(b0 and 0x03) shl 4])
                out.append("==")
            }
            i += 3
        }
        return out.toString()
    }

    fun base64Decode(text: String): ByteArray {
        val clean = text.filter { !it.isWhitespace() && it != '=' }
        val out = ByteArray(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        var index = 0
        for (c in clean) {
            val value = B64.indexOf(c)
            require(value >= 0) { "非法 Base64 字符: $c" }
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[index++] = ((buffer ushr bits) and 0xFF).toByte()
            }
        }
        return if (index == out.size) out else out.copyOf(index)
    }

    // ---------------------------------------------------------------- MD5

    private val MD5_S = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

    private val MD5_K = IntArray(64) { i ->
        val v = kotlin.math.sin(i + 1.0)
        ((kotlin.math.abs(v) * 4294967296.0).toLong() and 0xFFFFFFFFL).toInt()
    }

    private fun rotateLeft(value: Int, bits: Int): Int =
        (value shl bits) or (value ushr (32 - bits))

    fun md5(data: ByteArray): ByteArray {
        val bitLength = data.size.toLong() * 8
        val padded = run {
            val total = ((data.size + 8) / 64 + 1) * 64
            val buffer = ByteArray(total)
            data.copyInto(buffer)
            buffer[data.size] = 0x80.toByte()
            for (i in 0 until 8) {
                buffer[total - 8 + i] = ((bitLength ushr (8 * i)) and 0xFF).toByte()
            }
            buffer
        }

        var a0 = 0x67452301
        var b0 = 0xEFCDAB89.toInt()
        var c0 = 0x98BADCFE.toInt()
        var d0 = 0x10325476

        val m = IntArray(16)
        var offset = 0
        while (offset < padded.size) {
            for (i in 0 until 16) {
                m[i] = (padded[offset + i * 4].toInt() and 0xFF) or
                    ((padded[offset + i * 4 + 1].toInt() and 0xFF) shl 8) or
                    ((padded[offset + i * 4 + 2].toInt() and 0xFF) shl 16) or
                    ((padded[offset + i * 4 + 3].toInt() and 0xFF) shl 24)
            }

            var a = a0
            var b = b0
            var c = c0
            var d = d0

            for (i in 0 until 64) {
                var f: Int
                var g: Int
                when (i) {
                    in 0..15 -> { f = (b and c) or (b.inv() and d); g = i }
                    in 16..31 -> { f = (d and b) or (d.inv() and c); g = (5 * i + 1) % 16 }
                    in 32..47 -> { f = b xor c xor d; g = (3 * i + 5) % 16 }
                    else -> { f = c xor (b or d.inv()); g = (7 * i) % 16 }
                }
                f += a + MD5_K[i] + m[g]
                a = d
                d = c
                c = b
                b += rotateLeft(f, MD5_S[i])
            }

            a0 += a
            b0 += b
            c0 += c
            d0 += d
            offset += 64
        }

        val out = ByteArray(16)
        for ((index, value) in intArrayOf(a0, b0, c0, d0).withIndex()) {
            for (i in 0 until 4) {
                out[index * 4 + i] = ((value ushr (8 * i)) and 0xFF).toByte()
            }
        }
        return out
    }

    fun md5Hex(text: String): String = toHex(md5(text.encodeToByteArray()))

    // ---------------------------------------------------------------- RSA（教科书式，无填充）

    private const val RSA_MODULUS =
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7" +
        "b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280" +
        "104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932" +
        "575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b" +
        "3ece0462db0a22b8e7"

    private const val RSA_EXPONENT = 0x010001L

    /**
     * 以 16 位为一肢的无符号大整数。用纯 Kotlin 实现，Kotlin/Native 上也无需平台 BigInteger。
     */
    private class Big(val limbs: IntArray) {

        companion object {
            const val BASE = 1 shl 16
            const val MASK = BASE - 1

            fun of(value: Int) = Big(intArrayOf(value and MASK))

            fun fromHex(hex: String): Big {
                val clean = hex.trim().removePrefix("0x").ifEmpty { "0" }
                val limbs = IntArray((clean.length + 3) / 4)
                var end = clean.length
                var index = 0
                while (end > 0) {
                    val start = maxOf(0, end - 4)
                    limbs[index++] = clean.substring(start, end).toInt(16)
                    end = start
                }
                return Big(trim(limbs))
            }

            fun fromBigEndianBytes(bytes: ByteArray): Big = fromHex(toHex(bytes))

            fun trim(limbs: IntArray): IntArray {
                var end = limbs.size
                while (end > 1 && limbs[end - 1] == 0) end--
                return if (end == limbs.size) limbs else limbs.copyOf(end)
            }
        }

        fun compareTo(other: Big): Int {
            if (limbs.size != other.limbs.size) return limbs.size.compareTo(other.limbs.size)
            for (i in limbs.indices.reversed()) {
                if (limbs[i] != other.limbs[i]) return limbs[i].compareTo(other.limbs[i])
            }
            return 0
        }

        fun subtract(other: Big): Big {
            val result = IntArray(maxOf(limbs.size, other.limbs.size))
            var borrow = 0
            for (i in result.indices) {
                val a = if (i < limbs.size) limbs[i] else 0
                val b = if (i < other.limbs.size) other.limbs[i] else 0
                var diff = a - b - borrow
                borrow = 0
                if (diff < 0) {
                    diff += BASE
                    borrow = 1
                }
                result[i] = diff
            }
            return Big(trim(result))
        }

        fun multiply(other: Big): Big {
            val result = IntArray(limbs.size + other.limbs.size)
            for (i in limbs.indices) {
                var carry = 0L
                for (j in other.limbs.indices) {
                    val cur = result[i + j].toLong() + limbs[i].toLong() * other.limbs[j] + carry
                    result[i + j] = (cur and MASK.toLong()).toInt()
                    carry = cur ushr 16
                }
                var k = i + other.limbs.size
                while (carry > 0L) {
                    val cur = result[k].toLong() + carry
                    result[k] = (cur and MASK.toLong()).toInt()
                    carry = cur ushr 16
                    k++
                }
            }
            return Big(trim(result))
        }

        fun shiftLeftOne(): Big {
            val result = IntArray(limbs.size + 1)
            var carry = 0
            for (i in limbs.indices) {
                result[i] = ((limbs[i] shl 1) or carry) and MASK
                carry = (limbs[i] ushr 15) and 1
            }
            result[limbs.size] = carry
            return Big(trim(result))
        }

        /** 返回 (商, 余)。二进制长除法，足够处理 RSA-1024 的规模。 */
        fun divMod(divisor: Big): Pair<Big, Big> {
            if (divisor.limbs.size == 1) {
                val d = divisor.limbs[0]
                val quotient = IntArray(limbs.size)
                var remainder = 0L
                for (i in limbs.indices.reversed()) {
                    val cur = (remainder shl 16) or limbs[i].toLong()
                    quotient[i] = (cur / d).toInt()
                    remainder = cur % d
                }
                return Big(trim(quotient)) to Big.of(remainder.toInt())
            }

            var remainder = Big.of(0)
            val quotient = IntArray(limbs.size)
            // 按位处理：逐位把被除数搬进余数，余数够大就减去除数并在商上记 1
            for (i in limbs.indices.reversed()) {
                for (bit in 15 downTo 0) {
                    remainder = remainder.shiftLeftOne()
                    if (((limbs[i] ushr bit) and 1) == 1) {
                        remainder = remainder.orOne()
                    }
                    // 商整体左移一位
                    var carry = 0
                    for (j in quotient.indices) {
                        val next = (quotient[j] ushr 15) and 1
                        quotient[j] = ((quotient[j] shl 1) or carry) and MASK
                        carry = next
                    }
                    if (remainder.compareTo(divisor) >= 0) {
                        remainder = remainder.subtract(divisor)
                        quotient[i] = quotient[i] or 1
                    }
                }
            }
            return Big(trim(quotient)) to remainder
        }

        /** 最低位加一。 */
        fun orOne(): Big {
            val copy = limbs.copyOf()
            var i = 0
            var carry = 1
            while (carry != 0 && i < copy.size) {
                val sum = copy[i] + carry
                copy[i] = sum and MASK
                carry = sum ushr 16
                i++
            }
            return if (carry != 0) Big(copy.copyOf(copy.size + 1).also { it[copy.size] = carry }) else Big(copy)
        }

        fun modPow(exponent: Long, modulus: Big): Big {
            var result = Big.of(1)
            var base = divMod(modulus).second
            var exp = exponent
            while (exp > 0L) {
                if (exp and 1L == 1L) result = result.multiply(base).divMod(modulus).second
                base = base.multiply(base).divMod(modulus).second
                exp = exp shr 1
            }
            return result
        }

        fun toHexPadded(byteLength: Int): String {
            val builder = StringBuilder()
            for (i in limbs.indices.reversed()) {
                builder.append(limbs[i].toString(16).padStart(4, '0'))
            }
            val text = builder.toString().trimStart('0').ifEmpty { "0" }
            return text.padStart(byteLength * 2, '0')
        }
    }

    // ---------------------------------------------------------------- 随机

    private val RANDOM_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    /**
     * 生成 weapi 用的 16 位随机密钥。只用一次，不需要密码学强度。
     */
    fun randomSecretKey(length: Int = 16): String {
        val random = kotlin.random.Random.Default
        return buildString {
            repeat(length) { append(RANDOM_ALPHABET[random.nextInt(RANDOM_ALPHABET.length)]) }
        }
    }

    /**
     * 网易云的 RSA：把反转后的字符串当作大端整数，用公钥指数 65537 做无填充模幂，
     * 输出固定 256 位十六进制（对应 1024 位密钥）。
     */
    fun rsaEncrypt(text: String): String {
        val reversed = text.reversed()
        val message = Big.fromBigEndianBytes(reversed.encodeToByteArray())
        val modulus = Big.fromHex(RSA_MODULUS)
        return message.modPow(RSA_EXPONENT, modulus).toHexPadded(128).lowercase()
    }
}
