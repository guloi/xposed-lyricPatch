package com.lyricpatch.lib

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.lyricpatch.ui.UiPrefs
import kotlin.math.PI
import kotlin.math.sin

/**
 * 全局统一的“Q 弹”弹簧：用于高度、位移这类有实际距离的动画。
 */
fun <T> bouncy(): SpringSpec<T> = spring(
    dampingRatio = 0.68f,
    stiffness = Spring.StiffnessMediumLow,
)

/**
 * 每次 key 变化都完整播一遍的“弹一下”，返回缩放系数：1.0 → 1 + amplitude → 1.0。
 *
 * 弹簧的过冲量和「剩余距离」成正比，连点时值已贴近目标，过冲就小到看不见；
 * 这里把“弹”从值动画里拆出来，每次点击都从 0 重播一遍正弦钟形，幅度固定。
 */
@Composable
fun rememberPopScale(key: Any?): Float {
    val amplitude = UiPrefs.bounceScale
    // 每次 key 变化都换一个新的 Animatable（初值 0），而不是对同一个实例 snapTo：
    // snapTo 落在协程被取消的窗口里会抛 CancellationException，把后面的 animateTo 一起带走，
    // 结果 anim 停在 0（sin(π·0)=0）——表现就是“这一次没有回弹”。
    val anim = remember(key) { Animatable(0f) }
    LaunchedEffect(anim) {
        runCatching {
            anim.animateTo(1f, animationSpec = tween(durationMillis = 260))
        }
    }
    if (amplitude <= 0f) return 1f
    return 1f + amplitude * sin(PI * anim.value).toFloat()
}
