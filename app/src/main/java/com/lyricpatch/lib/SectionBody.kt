package com.lyricpatch.lib

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.lyricpatch.ui.UiPrefs
import kotlin.math.roundToInt

/**
 * 分组内容：展开 / 收起。
 *
 * 默认用 [ProgressBody]：高度 + 轻微缩放，平稳。
 * 外观页可切到 [SpringBody]：展开时内容先被弹簧多带下去一点再回到正确位置。
 */
@Composable
fun SectionBody(expanded: Boolean, content: @Composable ColumnScope.() -> Unit) {
    if (UiPrefs.sectionLegacyAnim) {
        SpringBody(expanded, content)
    } else {
        ProgressBody(expanded, content)
    }
}

/** 展开用弹簧、收回用 tween，避免弹簧收尾的顿挫 */
private fun bodySpec(expanded: Boolean): AnimationSpec<Float> =
    if (expanded) bouncy() else tween(200, easing = FastOutSlowInEasing)

/** 默认：单一进度驱动高度与缩放，内容始终组合，输入框状态不会丢 */
@Composable
private fun ProgressBody(expanded: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val progress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        // 展开用弹簧（有回弹），收回用 tween：弹簧收尾会很慢地蹭到 0 再小过冲一下，
        // 看着就是“顿一下又弹一下”的卡顿感
        animationSpec = bodySpec(expanded),
        label = "section-body",
    )

    Layout(
        content = { Column(content = content) },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
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

/**
 * 弹簧版：起点在上方 -travel，随进度落回 0；弹簧过冲时 y 会变成正值，
 * 也就是“内容先多往下走一点”，然后回正。高度仍夹在自然高度内，超出部分裁掉。
 */
@Composable
private fun SpringBody(expanded: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val progress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        // 同 ProgressBody：收回不用弹簧，避免尾部顿挫
        animationSpec = if (expanded) {
            spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow)
        } else {
            tween(200, easing = FastOutSlowInEasing)
        },
        label = "section-body-spring",
    )
    val travelPx = with(LocalDensity.current) { 32.dp.toPx() }

    Layout(
        content = { Column(content = content) },
        modifier = Modifier.fillMaxWidth().clipToBounds(),
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints.copy(minHeight = 0))
        val visibleHeight = (placeable.height * progress).roundToInt().coerceIn(0, placeable.height)
        layout(placeable.width, visibleHeight) {
            val y = ((progress - 1f) * travelPx).roundToInt()
            placeable.placeWithLayer(0, y) {
                alpha = progress.coerceIn(0f, 1f)
            }
        }
    }
}
