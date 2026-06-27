package com.uts.editor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.uts.editor.R
import com.uts.editor.viewmodel.FindState

@Composable
fun FindReplaceBar(
    state: FindState,
    readOnly: Boolean,
    onQueryChange: (String) -> Unit,
    onReplacementChange: (String) -> Unit,
    onToggleRegex: (Boolean) -> Unit,
    onToggleCase: (Boolean) -> Unit,
    onToggleWord: (Boolean) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    label = { Text(stringResource(R.string.find_hint)) },
                    modifier = Modifier.weight(1f),
                )
                val countText = if (state.query.isEmpty()) "" else if (state.matches.isEmpty())
                    stringResource(R.string.find_none)
                else stringResource(R.string.find_results, state.current + 1, state.matches.size)
                Text(
                    countText,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                IconButton(onClick = onPrevious) {
                    Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.action_previous))
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.action_next))
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, stringResource(R.string.action_close))
                }
            }
            if (!readOnly) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.replacement,
                        onValueChange = onReplacementChange,
                        singleLine = true,
                        label = { Text(stringResource(R.string.replace_hint)) },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onReplace) { Text(stringResource(R.string.action_replace)) }
                    TextButton(onClick = onReplaceAll) { Text(stringResource(R.string.action_replace_all)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                FilterChip(selected = state.regex, onClick = { onToggleRegex(!state.regex) },
                    label = { Text(stringResource(R.string.use_regex)) })
                FilterChip(selected = state.matchCase, onClick = { onToggleCase(!state.matchCase) },
                    label = { Text(stringResource(R.string.match_case)) })
                FilterChip(selected = state.wholeWord, onClick = { onToggleWord(!state.wholeWord) },
                    label = { Text(stringResource(R.string.whole_word)) })
            }
        }
    }
}
