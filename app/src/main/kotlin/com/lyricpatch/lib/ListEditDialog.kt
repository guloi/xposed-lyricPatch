package com.lyricpatch.lib

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 一行一个的列表编辑器。
 * 用于歌词源 / 翻译源 / 白名单 / 黑名单这类“多条同构文本”，
 * 比一个多行文本框好改、也不会因为逗号拼错而整项失效。
 */
@Composable
fun ListEditDialog(
    title: String,
    initial: String,
    placeholder: String,
    supporting: String,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val items = remember {
        mutableStateListOf<String>().apply {
            addAll(splitList(initial))
            if (isEmpty()) add("")
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Column(
                    modifier = Modifier
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    items.forEachIndexed { index, value ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = value,
                                onValueChange = { items[index] = it },
                                modifier = Modifier.weight(1f).padding(vertical = 3.dp),
                                singleLine = true,
                                shape = MaterialTheme.shapes.medium,
                                placeholder = {
                                    Text(
                                        placeholder,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                            IconButton(
                                onClick = { items.removeAt(index) },
                                enabled = items.size > 1,
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "删除这一行",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { items.add("") }) { Text("添加一行") }
                    Text(
                        "${items.count { it.isNotBlank() }} 项",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(items.map { it.trim() }.filter { it.isNotEmpty() })
                    onDismiss()
                },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
