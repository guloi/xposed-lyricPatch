package com.lyricpatch.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

/**
 * 全局统一的“Q 弹”弹簧：阻尼比 0.55 略低，会回弹一下但不打摆。
 * 各处动画都走它，避免有的地方 tween、有的地方弹簧。
 */
fun <T> bouncy(): SpringSpec<T> = spring(
    dampingRatio = 0.68f,   // 只留一点回弹，不再打摆
    stiffness = Spring.StiffnessMediumLow,
)

/**
 * 带回弹的浮点动画。
 *
 * 和 animateXxxAsState 的区别：这里每次目标变化都用 `initialVelocity = 0` 重新起弹，
 * 所以连续快速点击也能看到完整的一次过冲回弹，而不会因为“带着上一次的速度”被抵消掉。
 */
@Composable
fun rememberBouncyFloat(target: Float): Float {
    val anim = remember { Animatable(target) }
    LaunchedEffect(target) {
        // 被新目标打断 / 组合销毁都是正常情况，别把异常抛到主线程
        runCatching {
            anim.animateTo(targetValue = target, animationSpec = bouncy(), initialVelocity = 0f)
        }
    }
    return anim.value
}
