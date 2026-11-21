package com.lyricpatch.lib

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 卡片下方的说明文字 */
@Composable
fun CardNote(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 26.dp, end = 26.dp, top = 10.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
