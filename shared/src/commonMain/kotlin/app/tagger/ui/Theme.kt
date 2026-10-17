@file:OptIn(com.moriafly.salt.ui.UnstableSaltUiApi::class)

package app.tagger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.moriafly.salt.ui.SaltDynamicColors
import com.moriafly.salt.ui.SaltTheme
import com.moriafly.salt.ui.Text
import com.moriafly.salt.ui.darkSaltColors
import com.moriafly.salt.ui.lightSaltColors
import com.moriafly.salt.ui.saltConfigs

/**
 * 应用主题：统一走 SaltUI 的 SaltTheme。
 * Salt Player 是深色为主，这里默认深色；[dark] 可切换。
 */
@Composable
fun TaggerTheme(
    dark: Boolean = true,
    content: @Composable () -> Unit,
) {
    SaltTheme(
        configs = saltConfigs(isDarkTheme = dark),
        dynamicColors = SaltDynamicColors(
            light = lightSaltColors(),
            dark = darkSaltColors(),
        ),
        content = content,
    )
}

/**
 * SaltUI 没有文本框组件（它的设计是列表式），这里按 Salt 的视觉语言补一个：
 * 圆角背景 + 细描边 + 强调色光标。
 */
@Composable
fun SaltTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(SaltTheme.shapes.medium)
            .background(SaltTheme.colors.subBackground)
            .border(width = Dp.Hairline, color = SaltTheme.colors.stroke, shape = SaltTheme.shapes.medium)
            .padding(horizontal = SaltTheme.dimens.padding, vertical = SaltTheme.dimens.subPadding),
    ) {
        if (value.isEmpty() && hint.isNotEmpty()) {
            Text(
                text = hint,
                color = SaltTheme.colors.subText,
                style = SaltTheme.textStyles.main,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            textStyle = SaltTheme.textStyles.main.copy(color = SaltTheme.colors.text),
            cursorBrush = SolidColor(SaltTheme.colors.highlight),
        )
    }
}

/** 小号辅助文字（Salt 的 subText 风格）。 */
@Composable
fun SaltSubText(
    text: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = SaltTheme.colors.subText,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        style = SaltTheme.textStyles.sub,
        maxLines = maxLines,
    )
}

/** 秒 -> m:ss。 */
fun formatDuration(seconds: Double): String {
    if (seconds <= 0) return "--:--"
    val total = seconds.toInt()
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}
