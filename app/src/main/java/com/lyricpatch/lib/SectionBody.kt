package com.lyricpatch.lib

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import com.lyricpatch.ui.UiPrefs
import kotlin.math.roundToInt

/**
 * 分组内容：展开 / 收起。
 *
 * 默认实现用单一进度驱动高度 + 缩放 + 透明度（[ProgressBody]），
 * 可在外观页切回旧实现（[LegacyBody]）——旧实现已经修好「首次组合时不播动画」的毛病：
 * 用从 false 起步的 MutableTransitionState，即使首帧 expanded 已经是 true 也会走入场。
 */
@Composable
fun SectionBody(expanded: Boolean, content: @Composable ColumnScope.() -> Unit) {
    if (UiPrefs.sectionLegacyAnim) {
        LegacyBody(expanded, content)
    } else {
        ProgressBody(expanded, content)
    }
}

/** 当前默认：单一进度驱动，内容始终组合，输入框状态不会丢 */
@Composable
private fun ProgressBody(expanded: Boolean, content: @Composable ColumnScope.() -> Unit) {
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

/** 旧实现：AnimatedVisibility，已修好首帧不播入场的问题 */
@Composable
private fun LegacyBody(expanded: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = expanded
    AnimatedVisibility(
        visibleState = state,
        enter = expandVertically(animationSpec = bouncy()) +
            fadeIn(animationSpec = bouncy()),
        exit = shrinkVertically(animationSpec = bouncy()) +
            fadeOut(animationSpec = bouncy()),
    ) {
        Column(content = content)
    }
}
