package com.lyricpatch.pages

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lyricpatch.lib.ActionRow
import com.lyricpatch.lib.RowDivider
import com.lyricpatch.lib.SettingsCard

/**
 * 设置入口页：展示各个分类，点进分类才是具体设置项。
 */
@Composable
fun SettingsIndexPage(
    modifier: Modifier = Modifier,
    onOpenCategory: (String) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(top = 18.dp, bottom = 28.dp),
    ) {
        item {
            Text(
                "设置",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 26.dp, end = 26.dp, bottom = 4.dp),
            )
            Text(
                "选择要修改的部分",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 26.dp, end = 26.dp, bottom = 18.dp),
            )
        }

        item {
            SettingsCard {
                SETTINGS_CATEGORIES.forEachIndexed { index, category ->
                    if (index > 0) RowDivider()
                    ActionRow(
                        title = category.title,
                        subtitle = category.subtitle,
                        icon = iconOf(category.title),
                        onClick = { onOpenCategory(category.title) },
                    )
                }
            }
        }
    }
}

private fun iconOf(category: String): ImageVector = when (category) {
    "全部" -> TuneIcon
    "歌词" -> Icons.Filled.List
    "显示" -> Icons.Filled.Star
    "应用" -> Icons.Filled.Lock
    "外观" -> Icons.Filled.Create
    else -> Icons.Filled.Info
}

/**
 * Tune（滑杆）图标，Material Icons 24dp filled。
 * 只服务本文件，所以就地画，不引 material-icons-extended：那个库几千个图标，
 * 为这一个白背十几 MB 不划算。
 */
private val TuneIcon: ImageVector = ImageVector.Builder(
    name = "Tune",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(3f, 17f)
        horizontalLineToRelative(6f)
        verticalLineToRelative(2f)
        horizontalLineTo(3f)
        close()
        moveTo(3f, 5f)
        verticalLineToRelative(2f)
        horizontalLineToRelative(10f)
        verticalLineTo(5f)
        horizontalLineTo(3f)
        close()
        moveTo(13f, 21f)
        verticalLineToRelative(-2f)
        horizontalLineToRelative(8f)
        verticalLineToRelative(-2f)
        horizontalLineToRelative(-8f)
        verticalLineToRelative(-2f)
        horizontalLineToRelative(-2f)
        verticalLineToRelative(6f)
        horizontalLineToRelative(2f)
        close()
        moveTo(7f, 9f)
        verticalLineToRelative(2f)
        horizontalLineTo(3f)
        verticalLineToRelative(2f)
        horizontalLineToRelative(4f)
        verticalLineToRelative(2f)
        horizontalLineToRelative(2f)
        verticalLineTo(9f)
        horizontalLineTo(7f)
        close()
        moveTo(21f, 13f)
        verticalLineToRelative(-2f)
        horizontalLineTo(11f)
        verticalLineToRelative(2f)
        horizontalLineToRelative(10f)
        close()
        moveTo(15f, 9f)
        horizontalLineToRelative(2f)
        verticalLineTo(7f)
        horizontalLineToRelative(4f)
        verticalLineTo(5f)
        horizontalLineToRelative(-4f)
        verticalLineTo(3f)
        horizontalLineToRelative(-2f)
        verticalLineToRelative(6f)
        close()
    }
}.build()
