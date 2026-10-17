package app.tagger.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 真实接口联调：只有 JVM 端跑（jvmTest），验证 weapi 加密能被网易云服务端解开。
 * 没有网络时会跳过而不失败。
 */
class NetworkIntegrationTest {

    private fun client(): Pair<HttpClient, NeteaseClient> {
        val http = HttpClient(OkHttp) {
            engine {
                config {
                    followRedirects(true)
                }
            }
        }
        return http to NeteaseClient(http, rate = 2.0)
    }

    @Test
    fun searchAndDetailWork() = runBlocking {
        val (http, client) = client()
        try {
            val songs = runCatching { client.searchSongs("富士山下 陈奕迅", 5) }
                .getOrElse { ex ->
                    println("跳过：网络不可用（${ex.message}）")
                    return@runBlocking
                }
            assertTrue(songs.isNotEmpty(), "搜索应返回结果；若为空说明加密或端点有问题")
            val first = songs.first()
            println("搜索首条：${first.name} — ${first.artistsText} — ${first.album} (${first.durationSeconds.toInt()}s)")
            assertTrue(first.name.isNotBlank())
            assertTrue(first.artists.isNotEmpty())
            assertTrue(first.durationMs > 0)

            val detail = client.songDetail(first.id)
            println("详情：专辑=${detail.album} 年=${detail.year} 音轨=${detail.trackNo} 封面=${detail.picUrl.take(40)}")
            assertTrue(detail.album.isNotBlank() || detail.picUrl.isNotBlank(), "详情应有专辑或封面")

            val (lyric, translated, hasTranslation) = client.lyric(first.id)
            println("歌词：${lyric.length} 字符，翻译=${if (hasTranslation) "有" else "无"}")
            assertTrue(lyric.isNotEmpty() || translated.isNotEmpty(), "应有歌词")

            val albums = client.searchAlbums("叶惠美", 3)
            assertTrue(albums.isNotEmpty(), "专辑搜索应有结果")
            if (albums.isNotEmpty()) {
                val album = client.albumDetail(albums.first().id)
                println("专辑：${album.name} / ${album.artist} / ${album.year} / ${album.tracks.size} 首")
                assertTrue(album.tracks.isNotEmpty(), "专辑详情应含曲目")
                assertTrue(album.tracks.any { it.trackNo > 0 }, "曲目应有音轨号")
            }

            val artists = client.searchArtists("周杰伦", 3)
            assertTrue(artists.isNotEmpty(), "歌手搜索应有结果")
            if (artists.isNotEmpty()) {
                val works = client.artistSongs(artists.first().id, 10, artists.first().name)
                println("歌手作品：${works.size} 首，例如 ${works.take(3).joinToString("、") { it.name }}")
                assertTrue(works.isNotEmpty(), "歌手应有作品")
            }

            val (size, hits, _) = client.cache.stats()
            println("缓存：$size 条，命中 $hits 次")
            assertTrue(size > 0)

            // 重复搜索必须命中缓存（不再打网络）
            val start = kotlin.time.TimeSource.Monotonic.markNow()
            client.searchSongs("富士山下 陈奕迅", 5)
            val elapsed = start.elapsedNow()
            println("缓存命中耗时：${elapsed.inWholeMilliseconds} ms")
            assertTrue(elapsed.inWholeMilliseconds < 500, "重复搜索应走缓存")
        } finally {
            http.close()
        }
    }

    @Test
    fun coverDownloadWorks() = runBlocking {
        val (http, client) = client()
        try {
            val songs = runCatching { client.searchSongs("富士山下 陈奕迅", 3) }.getOrElse { return@runBlocking }
            if (songs.isEmpty()) return@runBlocking
            val detail = client.songDetail(songs.first().id)
            val url = NeteaseClient.coverUrl(detail.picUrl, 300)
            if (url.isBlank()) return@runBlocking
            val bytes = client.fetchBytes(url)
            println("封面下载：${bytes.size} 字节，开头 ${Crypto.toHex(bytes.take(4).toByteArray())}")
            assertTrue(bytes.size > 1000, "封面应有内容")
        } finally {
            http.close()
        }
    }
}
