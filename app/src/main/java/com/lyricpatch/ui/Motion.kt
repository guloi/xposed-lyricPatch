package com.lyricpatch.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * 全局统一的“Q 弹”弹簧：阻尼比 0.55 略低，会回弹一下但不打摆。
 * 各处动画都走它，避免有的地方 tween、有的地方弹簧。
 */
fun <T> bouncy(): SpringSpec<T> = spring(
    dampingRatio = 0.68f,   // 只留一点回弹，不再打摆
    stiffness = Spring.StiffnessMediumLow,
)
