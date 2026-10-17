package app.tagger.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 编码/哈希/加密链路自检。
 * RSA 与 MD5 的期望值由参考实现（Python 版）算出，保证服务端能解出我们的请求。
 */
class CryptoTest {

    @Test
    fun md5MatchesKnownVectors() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Crypto.md5Hex(""))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Crypto.md5Hex("abc"))
        assertEquals("c4ca4238a0b923820dcc509a6f75849b", Crypto.md5Hex("1"))
        assertEquals("089b4943ea034acfa445d050c7913e55", Crypto.md5Hex("中文测试"))
    }

    @Test
    fun md5HandlesMultiBlockInput() {
        // 标准向量："1234567890" × 8（80 字符，跨越多个 64 字节块）
        val text = "1234567890".repeat(8)
        assertEquals(80, text.length)
        assertEquals("57edf4a22be3c955ac49da2e2107b67a", Crypto.md5Hex(text))

        // 块边界：补齐规则最容易出错的地方
        assertEquals("c1bb4f81d892b2d57947682aeb252456", Crypto.md5Hex("x".repeat(64)))
        assertEquals("1bc932052302d074bdec39795fe00cf6", Crypto.md5Hex("x".repeat(65)))
        assertEquals("d5d675535f98fb8ce337c8fd049cbf69", Crypto.md5Hex("y".repeat(128)))
        assertEquals("5eca9bd3eb07c006cd43ae48dfde7fd3", Crypto.md5Hex("a".repeat(32)))
        assertEquals("4697843037d962f62a5a429e611e0f5f", Crypto.md5Hex("a".repeat(40)))
    }

    @Test
    fun base64RoundTrips() {
        val samples = listOf("", "a", "ab", "abc", "abcd", "中文测试", "{\"s\":\"夜曲\"}")
        for (sample in samples) {
            val encoded = Crypto.base64Encode(sample.encodeToByteArray())
            assertEquals(sample, Crypto.base64Decode(encoded).decodeToString(), "往返: $sample")
        }
        assertEquals("aGVsbG8=", Crypto.base64Encode("hello".encodeToByteArray()))
        assertEquals("5Lit5paH", Crypto.base64Encode("中文".encodeToByteArray()))
    }

    @Test
    fun hexRoundTrips() {
        val data = ByteArray(32) { (it * 37 + 11).toByte() }
        val hex = Crypto.toHex(data)
        assertEquals(64, hex.length)
        assertTrue(data.contentEquals(Crypto.fromHex(hex)))
        assertEquals("00ff10", Crypto.toHex(byteArrayOf(0, 0xFF.toByte(), 0x10)))
    }

    @Test
    fun rsaMatchesReferenceImplementation() {
        // 参考值：Python 版 pow(m, 65537, n) 的结果
        assertEquals(
            "d15a1683c992095d0c234c19966605c5c5964911268bbeda8cb8d08d834913e5" +
                "9d53b32358903a121b5fca784c1f5ae44951fd02524df58ecc98e52cc7cf8689" +
                "b42c2e93ddf05b0592512d87f5960467e2f086c018849d76014d323500e30f13" +
                "ef4cafbb0cf5a66731a3f1776c75ca35d0062dac70a3e33245afabcf47938487",
            Crypto.rsaEncrypt("abcdefghijklmnop"),
            "RSA(abcdefghijklmnop)",
        )
        assertEquals(
            "71963f3ffd07896a65116ae62a26d0672cc685cc39416082c5637fd358baa392" +
                "ac1520d7218188aff739affa5393d8d36b69b863f72282de5d0e081ac6fbd1af" +
                "d8a067999e39f91f6074ffcff4e97f56e3118ddaefe151a7d538d6d39a27a613" +
                "269d65d901f65b6f9769dbc58e1297a00f312347f259bcc5c6d0445ae38e0d17",
            Crypto.rsaEncrypt("Aa1Bb2Cc3Dd4Ee5F"),
            "RSA(Aa1Bb2Cc3Dd4Ee5F)",
        )
        // 全零密钥也要能算（不允许 BigInteger 带符号问题）
        assertEquals(256, Crypto.rsaEncrypt("0000000000000000").length)
    }

    @Test
    fun rsaOutputIsStableAndDistinct() {
        val a = Crypto.rsaEncrypt("abcdefghijklmnop")
        val b = Crypto.rsaEncrypt("abcdefghijklmnop")
        val c = Crypto.rsaEncrypt("abcdefghijklmnoq")
        assertEquals(256, a.length)
        assertEquals(a, b, "同一输入结果稳定")
        assertTrue(a != c, "不同输入得到不同密文")
        assertTrue(a.all { it in "0123456789abcdef" }, "应为小写十六进制")
    }

    @Test
    fun weapiIsSelfConsistent() {
        val payload = "{\"s\":\"夜曲\",\"type\":1,\"limit\":10,\"offset\":0,\"total\":true}"
        val secretKey = "0123456789abcdef"
        val form = NeteaseCrypto.weapiWithKey(payload, secretKey)

        assertTrue(form.containsKey("params"))
        assertTrue(form.containsKey("encSecKey"))
        assertEquals(256, form.getValue("encSecKey").length)
        // 解密应还原原始 JSON（含中文）
        assertEquals(payload, NeteaseCrypto.weapiDecrypt(form.getValue("params"), secretKey))
    }

    @Test
    fun weapiUsesRandomKeyEachTime() {
        val payload = "{\"test\":1}"
        val first = NeteaseCrypto.weapi(payload)
        val second = NeteaseCrypto.weapi(payload)
        assertTrue(first["params"] != second["params"], "每次请求应使用不同随机密钥")
        assertTrue(first["encSecKey"] != second["encSecKey"])
    }

    @Test
    fun eapiFormatIsStable() {
        val form = NeteaseCrypto.eapi("/song/lyric", "{\"id\":1}")
        val params = form.getValue("params")
        assertTrue(params.length % 32 == 0, "eapi 输出应是 16 字节块的十六进制")
        assertTrue(params.all { it in "0123456789ABCDEF" }, "eapi 应为大写十六进制")
        assertEquals(params, NeteaseCrypto.eapi("/song/lyric", "{\"id\":1}").getValue("params"))
    }

    @Test
    fun randomKeyHasRequestedShape() {
        val key = Crypto.randomSecretKey()
        assertEquals(16, key.length)
        assertTrue(key.all { it.isLetterOrDigit() })
        assertTrue(key != Crypto.randomSecretKey() || key != Crypto.randomSecretKey())
    }
}
