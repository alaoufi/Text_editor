package com.uts.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uts.editor.R
import com.uts.editor.model.TextEncoding
import com.uts.editor.viewmodel.EncodingPrompt
import com.uts.editor.viewmodel.EncodingPromptReason

@Composable
fun EncodingDialog(
    prompt: EncodingPrompt,
    onPreview: (ByteArray, TextEncoding) -> String,
    onConfirm: (TextEncoding) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(prompt.suggested) }
    val preview = remember(selected) { onPreview(prompt.sample, selected) }
    val encodings = remember { TextEncoding.available() }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        title = { Text(stringResource(R.string.encoding_title)) },
        text = {
            Column {
                if (prompt.reason == EncodingPromptReason.DETECT_FAILED) {
                    Text(
                        stringResource(R.string.encoding_detect_failed),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    stringResource(R.string.encoding_detected, prompt.suggested.displayName, prompt.confidence),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                    items(encodings) { enc ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = enc }
                                .padding(vertical = 2.dp),
                        ) {
                            RadioButton(selected = selected == enc, onClick = { selected = enc })
                            Text(enc.displayName)
                        }
                    }
                }
                Text(
                    stringResource(R.string.encoding_preview),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Card(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(
                        text = preview.ifEmpty { " " },
                        style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .heightIn(max = 140.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                    )
                }
            }
        },
    )
}

@Composable
fun FileNameDialog(
    initial: String,
    folderName: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        title = { Text(stringResource(R.string.action_save)) },
        text = {
            Column {
                if (folderName != null) {
                    Text(
                        stringResource(R.string.save_into_folder, folderName),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.file_name_hint)) },
                )
            }
        },
    )
}

@Composable
fun GotoLineDialog(maxLine: Int, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { text.toIntOrNull()?.let { onGo(it.coerceIn(1, maxLine)) } }) {
                Text(stringResource(R.string.action_go))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        title = { Text(stringResource(R.string.action_goto_line)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text(stringResource(R.string.goto_line_hint, maxLine)) },
            )
        },
    )
}

@Composable
fun RecoveryDialog(count: Int, name: String, onRestore: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = { /* require a choice */ },
        confirmButton = { TextButton(onClick = onRestore) { Text(stringResource(R.string.recovery_restore)) } },
        dismissButton = { TextButton(onClick = onDiscard) { Text(stringResource(R.string.recovery_discard)) } },
        title = { Text(stringResource(R.string.recovery_title)) },
        text = { Text(stringResource(R.string.recovery_message, name)) },
    )
}

@Composable
fun ZipPickerDialog(
    entries: List<com.uts.editor.data.ZipSupport.Entry>,
    onPick: (com.uts.editor.data.ZipSupport.Entry) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        title = { Text(stringResource(R.string.action_open_zip)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(entries) { entry ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clickable { onPick(entry) },
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                com.uts.editor.viewmodel.EditorViewModel.humanSize(entry.size),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
fun BinaryFileDialog(name: String, onOpenAnyway: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = { TextButton(onClick = onOpenAnyway) { Text(stringResource(R.string.action_open)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
        title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = { Text(stringResource(R.string.msg_binary_warning)) },
    )
}

@Composable
fun DiscardDialog(name: String, onSave: () -> Unit, onDiscard: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = { TextButton(onClick = onSave) { Text(stringResource(R.string.confirm_discard_save)) } },
        dismissButton = { TextButton(onClick = onDiscard) { Text(stringResource(R.string.confirm_discard_dont_save)) } },
        title = { Text(stringResource(R.string.confirm_discard_title)) },
        text = { Text(stringResource(R.string.confirm_discard_message, name)) },
    )
}

@Composable
fun SaveEncodingDialog(
    current: TextEncoding,
    onConfirm: (TextEncoding) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(current) }
    val encodings = remember { TextEncoding.available() }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        title = { Text(stringResource(R.string.encoding_save_with)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                items(encodings) { enc ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { selected = enc }.padding(vertical = 2.dp),
                    ) {
                        RadioButton(selected = selected == enc, onClick = { selected = enc })
                        Text(enc.displayName)
                    }
                }
            }
        },
    )
}
