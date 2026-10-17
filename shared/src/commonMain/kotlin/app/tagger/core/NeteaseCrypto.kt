package app.tagger.core

/**
 * 网易云 weapi / eapi 的请求体加密。
 *
 * weapi: 两层 CBC（固定非对称密钥 + 随机密钥）+ RSA 加密随机密钥
 * eapi : 单层 ECB + MD5 摘要拼串
 */
object NeteaseCrypto {

    private const val NONCE = "0CoJUm6Qyw8W8jud"
    private const val IV = "0102030405060708"
    private const val EAPI_KEY = "e82ckenh8dichen8"
    private const val EAPI_SEPARATOR = "-36cd479b6b5-"

    /**
     * @param payload 已经序列化好的 JSON 文本
     */
    fun weapi(payload: String): Map<String, String> {
        val first = Crypto.base64Encode(
            Aes.cbcEncrypt(payload.encodeToByteArray(), NONCE.encodeToByteArray(), IV.encodeToByteArray())
        )
        val secretKey = Crypto.randomSecretKey()
        val params = Crypto.base64Encode(
            Aes.cbcEncrypt(first.encodeToByteArray(), secretKey.encodeToByteArray(), IV.encodeToByteArray())
        )
        return mapOf(
            "params" to params,
            "encSecKey" to Crypto.rsaEncrypt(secretKey),
        )
    }

    fun eapi(path: String, payload: String): Map<String, String> {
        val apiPath = path.replace("/eapi", "/api")
        val digest = Crypto.md5Hex("nobody${apiPath}use${payload}md5forencrypt")
        val plain = "$apiPath$EAPI_SEPARATOR$payload$EAPI_SEPARATOR$digest"
        val encrypted = Aes.ecbEncrypt(plain.encodeToByteArray(), EAPI_KEY.encodeToByteArray())
        return mapOf("params" to Crypto.toHex(encrypted).uppercase())
    }

    // ---------------------------------------------------------------- 测试用入口

    /** 用指定密钥加密，便于用已知向量做回归测试。 */
    internal fun weapiWithKey(payload: String, secretKey: String): Map<String, String> {
        val first = Crypto.base64Encode(
            Aes.cbcEncrypt(payload.encodeToByteArray(), NONCE.encodeToByteArray(), IV.encodeToByteArray())
        )
        val params = Crypto.base64Encode(
            Aes.cbcEncrypt(first.encodeToByteArray(), secretKey.encodeToByteArray(), IV.encodeToByteArray())
        )
        return mapOf("params" to params, "encSecKey" to Crypto.rsaEncrypt(secretKey))
    }

    /** 解出 weapi 的 params（用于验证自洽）。 */
    internal fun weapiDecrypt(params: String, secretKey: String): String {
        val inner = Aes.cbcDecrypt(
            Crypto.base64Decode(params),
            secretKey.encodeToByteArray(),
            IV.encodeToByteArray(),
        )
        val outer = Aes.cbcDecrypt(
            Crypto.base64Decode(inner.decodeToString()),
            NONCE.encodeToByteArray(),
            IV.encodeToByteArray(),
        )
        return outer.decodeToString()
    }
}
