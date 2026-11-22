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
import com.lyricpatch.ui.UiPrefs

/**
 * 分组内容：展开 / 收起。
 *
 * 用 Compose 自带的 AnimatedVisibility，但通过 MutableTransitionState(false) 起步，
 * 保证「首帧即使 expanded 已是 true 也会播入场动画」——直接写 `AnimatedVisibility(visible = …)`
 * 在首次组合时不播动画，快速折叠再展开就会僵硬出现。
 *
 * 另外：
 *  - 展开用弹簧（有回弹），收起用 tween（弹簧收尾会顿一下再小弹，显得卡）；
 *  - 折叠到底后内容会从组合中移除，不会出现“空白区误触隐藏开关”。
 */
@Composable
fun SectionBody(expanded: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = expanded

    AnimatedVisibility(
        visibleState = state,
        enter = if (UiPrefs.sectionLegacyAnim) {
            expandVertically(animationSpec = bouncy()) + fadeIn(animationSpec = bouncy())
        } else {
            expandVertically(animationSpec = tween(220)) + fadeIn(animationSpec = tween(220))
        },
        exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(160)),
    ) {
        Column(content = content)
    }
}
