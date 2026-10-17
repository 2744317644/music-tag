package app.tagger.core

/**
 * 平台信息：三端各自实现，用于界面显示与调试。
 * 这是 KMP 的标准 expect/actual 用法，也验证了 iOS 端代码路径可编译。
 */
expect object Platform {
    val name: String
    val pathSeparator: String
    /** 应用配置/缓存目录（用于放设置文件和日志）。 */
    fun appDataDirectory(appName: String = "NeteaseTagger"): String
}
