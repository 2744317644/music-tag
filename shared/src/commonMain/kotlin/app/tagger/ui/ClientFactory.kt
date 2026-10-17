package app.tagger.ui

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders

/**
 * 三端共用一个创建入口，具体引擎由平台提供（desktopApp / Android / iOS 各自注册）。
 */
private var engineFactory: (() -> HttpClientEngine)? = null

object HttpClientFactory {

    /** 平台启动时注入自己的引擎（OkHttp / Darwin / Android）。 */
    fun install(factory: () -> HttpClientEngine) {
        engineFactory = factory
    }

    fun create(): HttpClient {
        val factory: () -> HttpClientEngine = engineFactory
            ?: error("未安装 HTTP 引擎：请在平台入口调用 HttpClientFactory.install")
        val engine: HttpClientEngine = factory.invoke()
        return HttpClient(engine) {
            // 统一 UA，网易云对浏览器 UA 才返回数据
            defaultRequest {
                header(
                    HttpHeaders.UserAgent,
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                )
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 20_000
                socketTimeoutMillis = 30_000
            }
        }
    }
}
