package com.lyricpatch.lib

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** 整行可点的开关；拇指里带勾/叉，切换时旋转形变 + 弹一下 */
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
            thumbContent = {
                val progress by animateFloatAsState(
                    targetValue = if (checked) 1f else 0f,
                    animationSpec = tween(160),
                    label = "thumb-progress",
                )
                val pop = rememberPopScale(checked)
                Box(
                    Modifier.size(SwitchDefaults.IconSize)
                        .graphicsLayer {
                            scaleX = pop
                            scaleY = pop
                        },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        // 拇指底色是 onPrimary（浅色主题下就是白），不显式上色会跟背景同为白
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        tint = MaterialTheme.colorScheme.primary,
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
