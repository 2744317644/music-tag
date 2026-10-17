package app.tagger.core.tags

/** 调试开关：只在测试里打开，生产环境保持关闭。 */
internal object DebugFlags {
    var tags: Boolean = false
}
