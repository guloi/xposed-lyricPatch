package com.lyricpatch.lib

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * 内容出现 / 消失时的展开动画。
 *
 * 用 MutableTransitionState(false) 起步，保证首帧即使 expanded 已是 true 也会播入场动画；
 * 折叠功能已经去掉，这里只用于条件内容的过渡（例如关闭莫奈取色后出现调色板）。
 */
@Composable
fun SectionBody(expanded: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = expanded

    AnimatedVisibility(
        visibleState = state,
        enter = expandVertically(animationSpec = bouncyExpand()) + fadeIn(animationSpec = tween(120)),
        exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(140)),
    ) {
        Column(content = content)
    }
}
