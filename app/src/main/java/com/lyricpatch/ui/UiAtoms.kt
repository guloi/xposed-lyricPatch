package com.lyricpatch.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** 设置页与外观页共用的几个小件 */

@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(content = content)
    }
}

@Composable
fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/**
 * 大标题。onToggle 不为 null 时整行可点，右侧带箭头表示收起状态。
 */
@Composable
fun SectionHeader(
    title: String,
    subtitle: String?,
    expanded: Boolean = true,
    onToggle: (() -> Unit)? = null,
) {
    val arrow by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = bouncy(),
        label = "section-arrow",
    )
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onToggle != null) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(arrow),
                )
            }
        }
    }
}

/** 分组内容：展开 / 收起都走弹簧，收起后不占高度 */
@Composable
fun SectionBody(expanded: Boolean, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(animationSpec = bouncy()) +
            fadeIn(animationSpec = bouncy()),
        exit = shrinkVertically(animationSpec = bouncy()) +
            fadeOut(animationSpec = bouncy()),
    ) {
        Column(content = content)
    }
}

@Composable
fun CardNote(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 26.dp, end = 26.dp, top = 10.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            // 拇指里同时画勾和叉：靠 alpha / 旋转 / 缩放插值，实现「勾转成叉」的形变
            thumbContent = {
                val progress by animateFloatAsState(
                    targetValue = if (checked) 1f else 0f,
                    animationSpec = bouncy(),
                    label = "switch-thumb",
                )
                Box(Modifier.size(SwitchDefaults.IconSize)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            alpha = 1f - progress
                            rotationZ = -90f * progress
                            val scale = 0.6f + 0.4f * (1f - progress)
                            scaleX = scale
                            scaleY = scale
                        },
                    )
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            alpha = progress
                            rotationZ = 90f * (1f - progress)
                            val scale = 0.6f + 0.4f * progress
                            scaleX = scale
                            scaleY = scale
                        },
                    )
                }
            },
        )
    }
}

@Composable
fun InfoRow(title: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 13.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
