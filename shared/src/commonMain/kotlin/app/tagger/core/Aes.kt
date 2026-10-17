package app.tagger.core

/**
 * 纯 Kotlin 的 AES-128 实现（CBC / ECB + PKCS#7）。
 *
 * 为什么手写而不是用平台 API：网易云 weapi 的加密必须在 commonMain 里跑，
 * 这样 Windows / Android / iOS 三端行为完全一致、都能跑单元测试。
 * 正确性由标准测试向量（FIPS-197 / NIST SP800-38A）保证，见 AesTest。
 */
object Aes {

    private val SBOX = IntArray(256)
    private val INV_SBOX = IntArray(256)

    init {
        // 用 GF(2^8) 求逆 + 仿射变换生成 S 盒，避免手抄表出错
        for (i in 0 until 256) {
            val inv = if (i == 0) 0 else gfPow(i, 254)
            var s = inv
            var x = inv
            for (bit in 1..4) {
                x = ((x shl 1) or (x ushr 7)) and 0xFF
                s = s xor x
            }
            s = s xor 0x63
            SBOX[i] = s
            INV_SBOX[s] = i
        }
    }

    private fun gfPow(value: Int, power: Int): Int {
        var result = 1
        var base = value
        var exp = power
        while (exp > 0) {
            if (exp and 1 == 1) result = gfMul(result, base)
            base = gfMul(base, base)
            exp = exp shr 1
        }
        return result
    }

    private fun gfMul(a: Int, b: Int): Int {
        var result = 0
        var x = a
        var y = b
        while (y > 0) {
            if (y and 1 == 1) result = result xor x
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11B
            y = y shr 1
        }
        return result and 0xFF
    }

    // ---------------------------------------------------------------- 密钥扩展

    private fun expandKey(key: ByteArray): Array<IntArray> {
        require(key.size == 16) { "AES-128 需要 16 字节密钥，实际 ${key.size}" }
        val w = Array(44) { IntArray(4) }
        for (i in 0 until 4) {
            for (j in 0 until 4) w[i][j] = key[i * 4 + j].toInt() and 0xFF
        }
        var rcon = 1
        for (i in 4 until 44) {
            var temp = w[i - 1].copyOf()
            if (i % 4 == 0) {
                // RotWord + SubWord + Rcon
                temp = intArrayOf(
                    SBOX[temp[1]] xor rcon,
                    SBOX[temp[2]],
                    SBOX[temp[3]],
                    SBOX[temp[0]],
                )
                rcon = gfMul(rcon, 2)
            }
            for (j in 0 until 4) w[i][j] = w[i - 4][j] xor temp[j]
        }
        // 组合成轮密钥
        val roundKeys = Array(11) { IntArray(16) }
        for (round in 0..10) {
            for (col in 0 until 4) {
                for (row in 0 until 4) {
                    roundKeys[round][col * 4 + row] = w[round * 4 + col][row]
                }
            }
        }
        return roundKeys
    }

    // ---------------------------------------------------------------- 单块加解密

    private fun addRoundKey(state: IntArray, roundKey: IntArray) {
        for (i in 0 until 16) state[i] = state[i] xor roundKey[i]
    }

    private fun subBytes(state: IntArray) {
        for (i in 0 until 16) state[i] = SBOX[state[i]]
    }

    private fun invSubBytes(state: IntArray) {
        for (i in 0 until 16) state[i] = INV_SBOX[state[i]]
    }

    // state 按列存放：state[col*4 + row]
    private fun shiftRows(state: IntArray) {
        val copy = state.copyOf()
        for (row in 0 until 4) {
            for (col in 0 until 4) {
                state[col * 4 + row] = copy[((col + row) % 4) * 4 + row]
            }
        }
    }

    private fun invShiftRows(state: IntArray) {
        val copy = state.copyOf()
        for (row in 0 until 4) {
            for (col in 0 until 4) {
                state[col * 4 + row] = copy[((col - row + 4) % 4) * 4 + row]
            }
        }
    }

    private fun mixColumns(state: IntArray) {
        for (col in 0 until 4) {
            val a0 = state[col * 4]
            val a1 = state[col * 4 + 1]
            val a2 = state[col * 4 + 2]
            val a3 = state[col * 4 + 3]
            state[col * 4] = gfMul(a0, 2) xor gfMul(a1, 3) xor a2 xor a3
            state[col * 4 + 1] = a0 xor gfMul(a1, 2) xor gfMul(a2, 3) xor a3
            state[col * 4 + 2] = a0 xor a1 xor gfMul(a2, 2) xor gfMul(a3, 3)
            state[col * 4 + 3] = gfMul(a0, 3) xor a1 xor a2 xor gfMul(a3, 2)
        }
    }

    private fun invMixColumns(state: IntArray) {
        for (col in 0 until 4) {
            val a0 = state[col * 4]
            val a1 = state[col * 4 + 1]
            val a2 = state[col * 4 + 2]
            val a3 = state[col * 4 + 3]
            state[col * 4] = gfMul(a0, 14) xor gfMul(a1, 11) xor gfMul(a2, 13) xor gfMul(a3, 9)
            state[col * 4 + 1] = gfMul(a0, 9) xor gfMul(a1, 14) xor gfMul(a2, 11) xor gfMul(a3, 13)
            state[col * 4 + 2] = gfMul(a0, 13) xor gfMul(a1, 9) xor gfMul(a2, 14) xor gfMul(a3, 11)
            state[col * 4 + 3] = gfMul(a0, 11) xor gfMul(a1, 13) xor gfMul(a2, 9) xor gfMul(a3, 14)
        }
    }

    private fun encryptBlock(input: ByteArray, offset: Int, roundKeys: Array<IntArray>, output: ByteArray, outOffset: Int) {
        val state = IntArray(16)
        for (i in 0 until 16) state[i] = input[offset + i].toInt() and 0xFF

        addRoundKey(state, roundKeys[0])
        for (round in 1..9) {
            subBytes(state)
            shiftRows(state)
            mixColumns(state)
            addRoundKey(state, roundKeys[round])
        }
        subBytes(state)
        shiftRows(state)
        addRoundKey(state, roundKeys[10])

        for (i in 0 until 16) output[outOffset + i] = state[i].toByte()
    }

    private fun decryptBlock(input: ByteArray, offset: Int, roundKeys: Array<IntArray>, output: ByteArray, outOffset: Int) {
        val state = IntArray(16)
        for (i in 0 until 16) state[i] = input[offset + i].toInt() and 0xFF

        addRoundKey(state, roundKeys[10])
        for (round in 9 downTo 1) {
            invShiftRows(state)
            invSubBytes(state)
            addRoundKey(state, roundKeys[round])
            invMixColumns(state)
        }
        invShiftRows(state)
        invSubBytes(state)
        addRoundKey(state, roundKeys[0])

        for (i in 0 until 16) output[outOffset + i] = state[i].toByte()
    }

    // ---------------------------------------------------------------- 模式

    private fun pkcs7Pad(data: ByteArray): ByteArray {
        val pad = 16 - data.size % 16
        val out = ByteArray(data.size + pad)
        data.copyInto(out)
        for (i in data.size until out.size) out[i] = pad.toByte()
        return out
    }

    private fun pkcs7Unpad(data: ByteArray): ByteArray {
        if (data.isEmpty() || data.size % 16 != 0) return data
        val pad = data.last().toInt() and 0xFF
        if (pad !in 1..16 || pad > data.size) return data
        return data.copyOf(data.size - pad)
    }

    fun cbcEncrypt(plain: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(iv.size == 16) { "IV 必须 16 字节" }
        val roundKeys = expandKey(key)
        val padded = pkcs7Pad(plain)
        val output = ByteArray(padded.size)
        val block = ByteArray(16)
        var previous = iv.copyOf()
        var offset = 0
        while (offset < padded.size) {
            for (i in 0 until 16) block[i] = (padded[offset + i].toInt() xor previous[i].toInt()).toByte()
            encryptBlock(block, 0, roundKeys, output, offset)
            previous = output.copyOfRange(offset, offset + 16)
            offset += 16
        }
        return output
    }

    fun cbcDecrypt(cipher: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(iv.size == 16) { "IV 必须 16 字节" }
        require(cipher.size % 16 == 0) { "密文长度必须是 16 的倍数" }
        val roundKeys = expandKey(key)
        val output = ByteArray(cipher.size)
        var previous = iv.copyOf()
        var offset = 0
        while (offset < cipher.size) {
            decryptBlock(cipher, offset, roundKeys, output, offset)
            for (i in 0 until 16) output[offset + i] = (output[offset + i].toInt() xor previous[i].toInt()).toByte()
            previous = cipher.copyOfRange(offset, offset + 16)
            offset += 16
        }
        return pkcs7Unpad(output)
    }

    fun ecbEncrypt(plain: ByteArray, key: ByteArray): ByteArray {
        val roundKeys = expandKey(key)
        val padded = pkcs7Pad(plain)
        val output = ByteArray(padded.size)
        var offset = 0
        while (offset < padded.size) {
            encryptBlock(padded, offset, roundKeys, output, offset)
            offset += 16
        }
        return output
    }

    fun ecbDecrypt(cipher: ByteArray, key: ByteArray): ByteArray {
        require(cipher.size % 16 == 0) { "密文长度必须是 16 的倍数" }
        val roundKeys = expandKey(key)
        val output = ByteArray(cipher.size)
        var offset = 0
        while (offset < cipher.size) {
            decryptBlock(cipher, offset, roundKeys, output, offset)
            offset += 16
        }
        return pkcs7Unpad(output)
    }
}
