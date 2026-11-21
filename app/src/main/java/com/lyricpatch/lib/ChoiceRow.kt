package com.lyricpatch.lib

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 一行胶囊选项（互斥选择），选项平分宽度 */
@Composable
fun ChoiceRow(
    title: String,
    subtitle: String?,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 13.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
