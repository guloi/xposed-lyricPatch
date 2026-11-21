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
    val anim = remember { Animatable(0f) }
    LaunchedEffect(key) {
        anim.snapTo(0f)
        runCatching {
            anim.animateTo(1f, animationSpec = tween(durationMillis = 260))
        }
    }
    if (amplitude <= 0f) return 1f
    return 1f + amplitude * sin(PI * anim.value).toFloat()
}
