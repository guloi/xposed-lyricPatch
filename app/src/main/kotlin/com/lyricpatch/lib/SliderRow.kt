package com.lyricpatch.lib

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 带右侧数值气泡的滑杆行；点数值气泡可以直接输入精确值 */
@Composable
fun SliderRow(
    title: String,
    subtitle: String?,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: String,
    onChange: (Int) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    val min = range.start.roundToInt()
    val max = range.endInclusive.roundToInt()

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Surface(
                onClick = { editing = true },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    display,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        Slider(
            value = value.toFloat().coerceIn(range.start, range.endInclusive),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range,
            steps = steps,
        )
    }

    if (editing) {
        NumberInputDialog(
            title = title,
            subtitle = subtitle,
            initial = value.coerceIn(min, max),
            min = min,
            max = max,
            onDismiss = { editing = false },
            onConfirm = onChange,
        )
    }
}

/** 滑杆的精确值输入框：限制在滑杆范围里，避免滑块显示被夹住 */
@Composable
private fun NumberInputDialog(
    title: String,
    subtitle: String?,
    initial: Int,
    min: Int,
    max: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember {
        mutableStateOf(
            TextFieldValue(
                text = initial.toString(),
                selection = TextRange(0, initial.toString().length),
            )
        )
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val parsed = text.text.trim().toIntOrNull()
    val valid = parsed != null && parsed in min..max

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    singleLine = true,
                    isError = text.text.isNotBlank() && !valid,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (min < 0) KeyboardType.Text else KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            parsed?.takeIf { it in min..max }?.let {
                                onConfirm(it)
                                onDismiss()
                            }
                        },
                    ),
                    supportingText = {
                        Text(
                            if (valid) "范围 $min–$max" else "请输入 $min–$max 之间的整数",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    parsed?.let {
                        onConfirm(it)
                        onDismiss()
                    }
                },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
