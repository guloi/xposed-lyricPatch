package com.lyricpatch.pages

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lyricpatch.lib.ActionRow
import com.lyricpatch.lib.RowDivider
import com.lyricpatch.lib.SettingsCard

/**
 * 设置入口页：只展示「歌词 / 显示 / 应用 / 模块」四个分类，
 * 点进各自分类后才是真正的设置项。
 */
@Composable
fun SettingsIndexPage(
    modifier: Modifier = Modifier,
    onOpenCategory: (String) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
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
    "歌词" -> Icons.Filled.List
    "显示" -> Icons.Filled.Settings
    "应用" -> Icons.Filled.Lock
    else -> Icons.Filled.Info
}
