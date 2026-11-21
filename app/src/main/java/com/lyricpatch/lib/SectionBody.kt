package com.lyricpatch.lib

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable

/** 分组内容：展开 / 收起都走弹簧，收起后不占高度 */
@Composable
fun SectionBody(expanded: Boolean, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(animationSpec = bouncy()) +
            fadeIn(animationSpec = bouncy()),
        exit = shrinkVertically(animationSpec = bouncy()) +
            fadeOut(animationSpec = bouncy()),
    ) {
        Column(content = content)
    }
}
