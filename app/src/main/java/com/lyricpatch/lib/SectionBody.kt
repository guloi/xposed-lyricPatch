package com.lyricpatch.lib

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import kotlin.math.roundToInt

/**
 * 分组内容：展开 / 收起由同一个进度值驱动（高矮 + 缩放 + 透明度）。
 *
 * 不用 AnimatedVisibility：它会被 LazyColumn 回收重建，首次组合时若 visible 已经是 true
 * 就不会播入场动画，表现就是“快速折叠再展开时彮硬地出现”。
 * 这里内容始终组合（输入框状态不丢），只拿进度去算高度和图层参数，
 * 所以无论点多快、中间被打断多少次，每次切换都有弹动。
 */
@Composable
fun SectionBody(expanded: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val progress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = bouncy(),
        label = "section-body",
    )

    Layout(
        content = { Column(content = content) },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        // 内容始终按自然高度测量，可见高度 = 自然高度 × 进度（允许弹簧过冲）
        val placeable = measurables.first().measure(constraints.copy(minHeight = 0))
        val visibleHeight = (placeable.height * progress).roundToInt().coerceIn(0, placeable.height)
        layout(placeable.width, visibleHeight) {
            placeable.placeWithLayer(0, 0) {
                val scale = 0.94f + 0.06f * progress
                scaleX = scale
                scaleY = scale
                alpha = progress.coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(0.5f, 0f)
            }
        }
    }
}
