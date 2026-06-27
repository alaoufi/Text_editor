package com.uts.editor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.uts.editor.R
import com.uts.editor.data.AppLanguage
import com.uts.editor.data.AppSettings
import com.uts.editor.data.ThemeMode
import com.uts.editor.util.LocaleManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: AppSettings,
    onTheme: (ThemeMode) -> Unit,
    onFontSize: (Float) -> Unit,
    onAutosave: (Boolean) -> Unit,
    onSyntax: (Boolean) -> Unit,
    onLineNumbers: (Boolean) -> Unit,
    onWordWrap: (Boolean) -> Unit,
    onLanguageApplied: () -> Unit,
    onPickSaveFolder: () -> Unit,
    onClearSaveFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val currentLanguage = LocaleManager.storedLanguage(context)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.settings_title),
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)

            SectionTitle(stringResource(R.string.settings_theme))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(settings.theme == ThemeMode.SYSTEM, { onTheme(ThemeMode.SYSTEM) },
                    { Text(stringResource(R.string.settings_theme_system)) })
                FilterChip(settings.theme == ThemeMode.LIGHT, { onTheme(ThemeMode.LIGHT) },
                    { Text(stringResource(R.string.settings_theme_light)) })
                FilterChip(settings.theme == ThemeMode.DARK, { onTheme(ThemeMode.DARK) },
                    { Text(stringResource(R.string.settings_theme_dark)) })
            }

            SectionTitle(stringResource(R.string.settings_language))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(currentLanguage == AppLanguage.SYSTEM, {
                    LocaleManager.setLanguage(context, AppLanguage.SYSTEM); onLanguageApplied()
                }, { Text(stringResource(R.string.settings_language_system)) })
                FilterChip(currentLanguage == AppLanguage.ARABIC, {
                    LocaleManager.setLanguage(context, AppLanguage.ARABIC); onLanguageApplied()
                }, { Text(stringResource(R.string.settings_language_ar)) })
                FilterChip(currentLanguage == AppLanguage.ENGLISH, {
                    LocaleManager.setLanguage(context, AppLanguage.ENGLISH); onLanguageApplied()
                }, { Text(stringResource(R.string.settings_language_en)) })
            }

            SectionTitle(stringResource(R.string.settings_font_size) + ": ${settings.fontSizeSp.toInt()}sp")
            Slider(
                value = settings.fontSizeSp,
                onValueChange = onFontSize,
                valueRange = 8f..32f,
                steps = 23,
            )

            ToggleRow(stringResource(R.string.settings_syntax), settings.syntaxEnabled, onSyntax)
            ToggleRow(stringResource(R.string.line_numbers), settings.lineNumbers, onLineNumbers)
            ToggleRow(stringResource(R.string.word_wrap), settings.wordWrap, onWordWrap)
            ToggleRow(stringResource(R.string.settings_autosave), settings.autosaveEnabled, onAutosave,
                subtitle = stringResource(R.string.settings_autosave_desc))

            SectionTitle(stringResource(R.string.settings_save_folder))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    settings.saveFolderName ?: stringResource(R.string.settings_save_folder_none),
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (settings.saveFolderUri != null) {
                    androidx.compose.material3.TextButton(onClick = onClearSaveFolder) {
                        Text(stringResource(R.string.settings_clear_folder))
                    }
                }
                androidx.compose.material3.Button(onClick = onPickSaveFolder) {
                    Text(stringResource(R.string.settings_choose_folder))
                }
            }

            Divider(Modifier.padding(vertical = 12.dp))
            SectionTitle(stringResource(R.string.settings_about))
            Text(stringResource(R.string.about_text),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
