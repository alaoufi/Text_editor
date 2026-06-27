package com.uts.editor.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uts.editor.R
import com.uts.editor.data.AppLanguage
import com.uts.editor.data.AppSettings
import com.uts.editor.model.LoadMode
import com.uts.editor.ui.theme.syntaxColorsFor
import com.uts.editor.util.LocaleManager
import com.uts.editor.util.PdfExporter
import com.uts.editor.util.PrintHelper
import com.uts.editor.util.ShareHelper
import com.uts.editor.viewmodel.EditorTab
import com.uts.editor.viewmodel.EditorViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    viewModel: EditorViewModel,
    settings: AppSettings,
    onLanguageApplied: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var menuOpen by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showGoto by remember { mutableStateOf(false) }
    var pendingClose by remember { mutableStateOf<Int?>(null) }
    var showSaveName by remember { mutableStateOf(false) }

    val pickSaveFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { viewModel.setSaveFolder(it) } }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.open(it) } }

    val saveAsLauncher = rememberLauncherForActivityResult(
        remember { CreateTextDocument() }
    ) { uri -> uri?.let { viewModel.saveAs(it) } }

    val exportPdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val tab = viewModel.active ?: return@rememberLauncherForActivityResult
        uri?.let {
            scope.launch {
                runCatching {
                    val doc = PdfExporter.render(tab.field.text)
                    context.contentResolver.openOutputStream(it)?.use { os -> PdfExporter.writeTo(doc, os) }
                }
            }
        }
    }

    val exportHtmlLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/html")
    ) { uri ->
        val tab = viewModel.active ?: return@rememberLauncherForActivityResult
        uri?.let {
            scope.launch {
                runCatching {
                    val html = com.uts.editor.util.HtmlExporter.toHtml(
                        title = tab.doc.displayName,
                        text = tab.field.text,
                        spans = tab.spans.toList(),
                        lineAligns = tab.lineAligns.toMap(),
                        lineSpacings = tab.lineSpacings.toMap(),
                        defaultSizeSp = settings.fontSizeSp,
                        defaultSpacing = EditorViewModel.DEFAULT_LINE_SPACING,
                    )
                    context.contentResolver.openOutputStream(it)?.use { os ->
                        os.write(html.toByteArray(Charsets.UTF_8))
                    }
                }
            }
        }
    }

    val startNewSave: () -> Unit = {
        if (settings.saveFolderUri != null) showSaveName = true
        else saveAsLauncher.launch(viewModel.active?.doc?.displayName ?: "untitled.txt")
    }
    val onCloseActive: () -> Unit = {
        val idx = viewModel.activeIndex
        if (!viewModel.requestCloseTab(idx)) pendingClose = idx
    }

    // Voice input (speech-to-text): the system recognizer returns the dictated
    // text, which we insert at the caret.
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) viewModel.insertAtCursor(spoken)
        }
    }
    val onVoice: () -> Unit = {
        val lang = when (LocaleManager.storedLanguage(context)) {
            AppLanguage.ARABIC -> "ar"
            AppLanguage.ENGLISH -> "en"
            AppLanguage.SYSTEM -> java.util.Locale.getDefault().toLanguageTag()
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        }
        val launched = runCatching { voiceLauncher.launch(intent); true }.getOrDefault(false)
        if (!launched) scope.launch { snackbar.showSnackbar(context.getString(R.string.voice_unavailable)) }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it.text) }
    }

    val active = viewModel.active
    val dark = isDark(settings)
    val syntaxColors = remember(dark) { syntaxColorsFor(dark) }

    val layoutDir = when (LocaleManager.storedLanguage(context)) {
        AppLanguage.ARABIC -> LayoutDirection.Rtl
        AppLanguage.ENGLISH -> LayoutDirection.Ltr
        AppLanguage.SYSTEM -> LocalLayoutDirection.current
    }

    CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = { active?.let { StatusBar(it) } },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                val readOnly = active?.doc?.loadMode == LoadMode.READONLY_LARGE

                // One compact row: overflow menu (carries file actions + file name) + edit/format tools.
                CompactToolbar(
                    enabled = active != null && !readOnly,
                    fileName = active?.doc?.displayName ?: stringResource(R.string.app_name),
                    modified = active?.isModified() == true,
                    menuOpen = menuOpen,
                    onMenuOpen = { menuOpen = true },
                    onMenuDismiss = { menuOpen = false },
                    onNew = { menuOpen = false; viewModel.newDocument() },
                    onOpen = { menuOpen = false; openLauncher.launch(arrayOf("*/*")) },
                    onSave = { menuOpen = false; viewModel.save(onNeedSaveAs = startNewSave) },
                    onSaveAs = { menuOpen = false; saveAsLauncher.launch(active?.doc?.displayName ?: "untitled.txt") },
                    onCloseDoc = { menuOpen = false; onCloseActive() },
                    onGoto = { menuOpen = false; showGoto = true },
                    onShare = { menuOpen = false; active?.let { ShareHelper.shareText(context, it.doc.displayName, it.field.text) } },
                    onExportPdf = { menuOpen = false; exportPdfLauncher.launch((active?.doc?.displayName ?: "document") + ".pdf") },
                    onExportHtml = { menuOpen = false; exportHtmlLauncher.launch((active?.doc?.displayName?.substringBeforeLast('.') ?: "document") + ".html") },
                    onPrint = { menuOpen = false; active?.let { PrintHelper.print(context, it.doc.displayName, it.field.text) } },
                    onSettings = { menuOpen = false; showSettings = true },
                    lineSpacing = viewModel.caretLineSpacing(),
                    currentAlign = viewModel.caretLineAlignment(),
                    onUndo = { viewModel.undo() },
                    onRedo = { viewModel.redo() },
                    onFind = { viewModel.showFind(true) },
                    onBold = { viewModel.applyBold() },
                    onItalic = { viewModel.applyItalic() },
                    onClearFormat = { viewModel.clearFormatting() },
                    onApplySize = { sz -> viewModel.applyFontSize(sz) },
                    onSpacingDecrease = { viewModel.adjustLineSpacing(-0.1f) },
                    onSpacingIncrease = { viewModel.adjustLineSpacing(+0.1f) },
                    onSetAlign = { a -> viewModel.setLineAlignment(a) },
                    onPickTextColor = { c -> viewModel.applyTextColor(c) },
                    onPickBgColor = { c -> viewModel.applyHighlight(c) },
                    onVoice = onVoice,
                )

                if (viewModel.isBusy) LinearProgressIndicator(Modifier.fillMaxWidth())

                TabStrip(
                    tabs = viewModel.tabs,
                    activeIndex = viewModel.activeIndex,
                    onSelect = { viewModel.switchTo(it) },
                    onClose = { idx -> if (!viewModel.requestCloseTab(idx)) pendingClose = idx },
                )

                if (viewModel.findState.visible) {
                    FindReplaceBar(
                        state = viewModel.findState,
                        readOnly = readOnly,
                        onQueryChange = { viewModel.updateFind(query = it) },
                        onReplacementChange = { viewModel.updateFind(replacement = it) },
                        onToggleRegex = { viewModel.updateFind(regex = it) },
                        onToggleCase = { viewModel.updateFind(matchCase = it) },
                        onToggleWord = { viewModel.updateFind(wholeWord = it) },
                        onNext = { viewModel.findNext() },
                        onPrevious = { viewModel.findPrevious() },
                        onReplace = { viewModel.replaceCurrent() },
                        onReplaceAll = { viewModel.replaceAll() },
                        onClose = { viewModel.showFind(false) },
                    )
                }

                if (active != null) {
                    Box(Modifier.fillMaxSize()) {
                        EditorArea(
                            value = active.field,
                            onValueChange = { viewModel.onTextChange(it) },
                            language = active.doc.language,
                            syntaxEnabled = settings.syntaxEnabled,
                            syntaxColors = syntaxColors,
                            fontSizeSp = settings.fontSizeSp,
                            showLineNumbers = settings.lineNumbers,
                            wordWrap = settings.wordWrap,
                            readOnly = readOnly,
                            matches = viewModel.findState.matches,
                            currentMatch = viewModel.findState.current,
                            lineAligns = active.lineAligns.toMap(),
                            lineSpacings = active.lineSpacings.toMap(),
                            spans = active.spans.toList(),
                            defaultSpacing = EditorViewModel.DEFAULT_LINE_SPACING,
                            textColorOverride = settings.textColor,
                            bgColorOverride = settings.bgColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                        if (readOnly) {
                            LargeFileControls(
                                onPrev = { viewModel.loadPreviousLargePage() },
                                onNext = { viewModel.loadNextLargePage() },
                                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                            )
                        }
                    }
                }
            }
        }

        // ---- Dialogs ----

        viewModel.encodingPrompt?.let { prompt ->
            EncodingDialog(
                prompt = prompt,
                onPreview = { sample, enc -> viewModel.previewDecode(sample, enc) },
                onConfirm = { viewModel.confirmEncoding(it) },
                onDismiss = { viewModel.cancelEncodingPrompt() },
            )
        }

        viewModel.zipPrompt?.let { zip ->
            ZipPickerDialog(
                entries = zip.entries,
                onPick = { viewModel.openZipEntry(it) },
                onDismiss = { viewModel.dismissZipPrompt() },
            )
        }

        viewModel.binaryPrompt?.let { bin ->
            BinaryFileDialog(
                name = bin.displayName,
                onOpenAnyway = { viewModel.openBinaryAnyway() },
                onCancel = { viewModel.cancelBinaryPrompt() },
            )
        }

        if (showGoto && active != null) {
            GotoLineDialog(
                maxLine = active.doc.stats.lines,
                onGo = { viewModel.gotoLine(it); showGoto = false },
                onDismiss = { showGoto = false },
            )
        }

        if (showSaveName && active != null) {
            FileNameDialog(
                initial = active.doc.displayName,
                folderName = settings.saveFolderName,
                onConfirm = { name ->
                    showSaveName = false
                    viewModel.saveNewToDefaultFolder(name, onFallback = { saveAsLauncher.launch(name) })
                },
                onDismiss = { showSaveName = false },
            )
        }

        pendingClose?.let { idx ->
            val tab = viewModel.tabs.getOrNull(idx)
            if (tab != null) {
                DiscardDialog(
                    name = tab.doc.displayName,
                    onSave = {
                        pendingClose = null
                        viewModel.switchTo(idx)
                        viewModel.save(onNeedSaveAs = { saveAsLauncher.launch(tab.doc.displayName) })
                    },
                    onDiscard = { pendingClose = null; viewModel.closeTab(idx) },
                    onCancel = { pendingClose = null },
                )
            } else pendingClose = null
        }

        if (viewModel.recoverableDrafts.isNotEmpty()) {
            RecoveryDialog(
                count = viewModel.recoverableDrafts.size,
                name = viewModel.recoverableDrafts.first().displayName,
                onRestore = { viewModel.restoreDrafts() },
                onDiscard = { viewModel.discardDrafts() },
            )
        }

        if (showSettings) {
            SettingsSheet(
                settings = settings,
                onTheme = { scope.launch { viewModel.settingsStore.setTheme(it) } },
                onFontSize = { scope.launch { viewModel.settingsStore.setFontSize(it) } },
                onAutosave = { scope.launch { viewModel.settingsStore.setAutosave(it) } },
                onSyntax = { scope.launch { viewModel.settingsStore.setSyntax(it) } },
                onLineNumbers = { scope.launch { viewModel.settingsStore.setLineNumbers(it) } },
                onWordWrap = { scope.launch { viewModel.settingsStore.setWordWrap(it) } },
                onLanguageApplied = onLanguageApplied,
                onPickSaveFolder = { pickSaveFolderLauncher.launch(null) },
                onClearSaveFolder = { viewModel.clearSaveFolder() },
                onDismiss = { showSettings = false },
            )
        }
    }
}

/** Single low-profile row: the ⋮ menu (file actions + current file name) followed
 *  by the edit/format tools. Designed to use as little vertical space as possible. */
@Composable
private fun CompactToolbar(
    enabled: Boolean,
    fileName: String,
    modified: Boolean,
    menuOpen: Boolean,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onNew: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onCloseDoc: () -> Unit,
    onGoto: () -> Unit,
    onShare: () -> Unit,
    onExportPdf: () -> Unit,
    onExportHtml: () -> Unit,
    onPrint: () -> Unit,
    onSettings: () -> Unit,
    lineSpacing: Float,
    currentAlign: Int,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFind: () -> Unit,
    onBold: () -> Unit,
    onItalic: () -> Unit,
    onClearFormat: () -> Unit,
    onApplySize: (Float) -> Unit,
    onSpacingDecrease: () -> Unit,
    onSpacingIncrease: () -> Unit,
    onSetAlign: (Int) -> Unit,
    onPickTextColor: (Int?) -> Unit,
    onPickBgColor: (Int?) -> Unit,
    onVoice: () -> Unit,
) {
    Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Pinned overflow menu (does not scroll with the tools).
            Box {
                ToolButton(Icons.Filled.MoreVert, R.string.action_menu, onClick = onMenuOpen)
                DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuDismiss) {
                    DropdownMenuItem(
                        enabled = false,
                        text = {
                            Column {
                                Text(
                                    text = if (modified) "• $fileName" else fileName,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "v${com.uts.editor.BuildConfig.VERSION_NAME}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        },
                        onClick = {},
                    )
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_new)) }, onClick = onNew)
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_open)) }, onClick = onOpen)
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_save)) }, onClick = onSave)
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_save_as)) }, onClick = onSaveAs)
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_close_document)) }, onClick = onCloseDoc)
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_goto_line)) }, onClick = onGoto)
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_share)) }, onClick = onShare)
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_export_html)) }, onClick = onExportHtml)
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_export_pdf)) }, onClick = onExportPdf)
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_print)) }, onClick = onPrint)
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_settings)) }, onClick = onSettings)
                }
            }
            ToolDivider()
            // Scrollable tools.
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolButton(Icons.Filled.Undo, R.string.action_undo, enabled = enabled, onClick = onUndo)
                ToolButton(Icons.Filled.Redo, R.string.action_redo, enabled = enabled, onClick = onRedo)
                ToolButton(Icons.Filled.Search, R.string.action_find, onClick = onFind)
                ToolDivider()
                ToolButton(Icons.Filled.FormatBold, R.string.format_bold, enabled = enabled, onClick = onBold)
                ToolButton(Icons.Filled.FormatItalic, R.string.format_italic, enabled = enabled, onClick = onItalic)
                // Each group is one button that expands into its options (space-saving).
                AlignMenu(currentAlign = currentAlign, onSetAlign = onSetAlign)
                FontMenu(
                    lineSpacing = lineSpacing,
                    onApplySize = onApplySize,
                    onSpacingDecrease = onSpacingDecrease,
                    onSpacingIncrease = onSpacingIncrease,
                    onPickTextColor = onPickTextColor,
                    onPickBgColor = onPickBgColor,
                )
                ToolButton(Icons.Filled.FormatClear, R.string.format_clear, enabled = enabled, onClick = onClearFormat)
                ToolButton(Icons.Filled.Mic, R.string.tool_voice, enabled = enabled, onClick = onVoice)
            }
        }
    }
}

private val TEXT_COLOR_PRESETS = listOf(
    0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFD32F2F.toInt(), 0xFF1565C0.toInt(),
    0xFF2E7D32.toInt(), 0xFFEF6C00.toInt(), 0xFF6A1B9A.toInt(),
)
private val BG_COLOR_PRESETS = listOf(
    0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFFF8E1.toInt(), 0xFFEEEEEE.toInt(),
    0xFF263238.toInt(), 0xFFF5ECD9.toInt(), 0xFF0D1B2A.toInt(),
)

/** One alignment button that expands into start / center / end / justify. */
@Composable
private fun AlignMenu(currentAlign: Int, onSetAlign: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val icon = when (currentAlign) {
        1 -> Icons.Filled.FormatAlignCenter
        2 -> Icons.Filled.FormatAlignLeft
        3 -> Icons.Filled.FormatAlignJustify
        else -> Icons.Filled.FormatAlignRight
    }
    Box {
        ToolButton(icon, R.string.tool_align, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AlignItem(Icons.Filled.FormatAlignRight, R.string.align_start, currentAlign == 0) { open = false; onSetAlign(0) }
            AlignItem(Icons.Filled.FormatAlignCenter, R.string.align_center, currentAlign == 1) { open = false; onSetAlign(1) }
            AlignItem(Icons.Filled.FormatAlignLeft, R.string.align_end, currentAlign == 2) { open = false; onSetAlign(2) }
            AlignItem(Icons.Filled.FormatAlignJustify, R.string.align_justify, currentAlign == 3) { open = false; onSetAlign(3) }
        }
    }
}

@Composable
private fun AlignItem(icon: ImageVector, labelRes: Int, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        leadingIcon = { Icon(icon, contentDescription = null) },
        text = {
            Text(
                stringResource(labelRes),
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
        },
        onClick = onClick,
    )
}

/** One font button that expands into size (− / +), text colour and background colour. */
private val FONT_SIZE_PRESETS = listOf(12f, 14f, 16f, 20f, 26f, 32f)

@Composable
private fun FontMenu(
    lineSpacing: Float,
    onApplySize: (Float) -> Unit,
    onSpacingDecrease: () -> Unit,
    onSpacingIncrease: () -> Unit,
    onPickTextColor: (Int?) -> Unit,
    onPickBgColor: (Int?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolButton(Icons.Filled.FormatSize, R.string.tool_font, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // Font size (applies to the selection / current paragraph).
            Text(
                stringResource(R.string.settings_font_size),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Row(Modifier.padding(horizontal = 8.dp)) {
                FONT_SIZE_PRESETS.forEach { sz ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.padding(3.dp).size(34.dp).clickable { open = false; onApplySize(sz) },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("${sz.toInt()}", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            StepperRow(
                label = stringResource(R.string.tool_line_spacing),
                value = String.format("%.1f", lineSpacing),
                onDecrease = onSpacingDecrease, onIncrease = onSpacingIncrease,
            )
            HorizontalDivider()
            SwatchSection(R.string.tool_text_color, TEXT_COLOR_PRESETS, onPickTextColor)
            HorizontalDivider()
            SwatchSection(R.string.tool_bg_color, BG_COLOR_PRESETS, onPickBgColor)
        }
    }
}

@Composable
private fun StepperRow(label: String, value: String, onDecrease: () -> Unit, onIncrease: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp))
        IconButton(onClick = onDecrease, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Remove, stringResource(R.string.tool_font_decrease), Modifier.size(18.dp))
        }
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(36.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = onIncrease, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Add, stringResource(R.string.tool_font_increase), Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SwatchSection(labelRes: Int, presets: List<Int>, onPick: (Int?) -> Unit) {
    Text(
        stringResource(labelRes),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
    Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
        // Clear-this-attribute chip.
        Surface(
            shape = RoundedCornerShape(6.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.padding(3.dp).size(30.dp).clickable { onPick(null) },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.FormatClear, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
        presets.take(6).forEach { c ->
            Surface(
                color = Color(c),
                shape = RoundedCornerShape(6.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.padding(4.dp).size(30.dp).clickable { onPick(c) },
            ) {}
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, descRes: Int, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = stringResource(descRes), modifier = Modifier.size(21.dp))
    }
}

@Composable
private fun ToolDivider() {
    Spacer(Modifier.width(3.dp))
    Surface(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.width(1.dp).height(22.dp),
    ) {}
    Spacer(Modifier.width(3.dp))
}

@Composable
private fun TabStrip(
    tabs: List<EditorTab>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
) {
    if (tabs.size <= 1) return
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tabs.forEachIndexed { index, tab ->
            val selected = index == activeIndex
            Surface(
                color = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.clickable { onSelect(index) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = 2.dp)) {
                    Text(
                        text = (if (tab.isModified()) "• " else "") + tab.doc.displayName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    IconButton(onClick = { onClose(index) }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Filled.Close, stringResource(R.string.tab_close), modifier = Modifier.size(15.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBar(tab: EditorTab) {
    val (line, col) = caretPosition(tab.field.text, tab.field.selection.start)
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 2.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val s = tab.doc.stats
            StatusItem(stringResource(R.string.status_position, line, col))
            StatusItem(stringResource(R.string.status_lines, s.lines))
            StatusItem(stringResource(R.string.status_words, s.words))
            StatusItem(stringResource(R.string.status_chars, s.chars))
            StatusItem(stringResource(R.string.status_size, EditorViewModel.humanSize(s.sizeBytes)))
        }
    }
}

@Composable
private fun StatusItem(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, fontSize = 11.sp, maxLines = 1)
}

@Composable
private fun LargeFileControls(onPrev: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrev) {
                Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.action_previous))
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.action_next))
            }
        }
    }
}

private fun isDark(settings: AppSettings): Boolean = when (settings.theme) {
    com.uts.editor.data.ThemeMode.DARK -> true
    com.uts.editor.data.ThemeMode.LIGHT -> false
    com.uts.editor.data.ThemeMode.SYSTEM -> false
}

/**
 * Like [ActivityResultContracts.CreateDocument] but derives the MIME type from
 * the requested file name, so the storage provider preserves the exact
 * extension (e.g. .json, .kt, .sql) instead of appending ".txt".
 */
private class CreateTextDocument : ActivityResultContract<String, Uri?>() {
    override fun createIntent(context: Context, input: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(com.uts.editor.data.FileIo.mimeForName(input))
            .putExtra(Intent.EXTRA_TITLE, input)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

private fun caretPosition(text: String, offset: Int): Pair<Int, Int> {
    val safe = offset.coerceIn(0, text.length)
    var line = 1
    var lineStart = 0
    var i = 0
    while (i < safe) {
        if (text[i] == '\n') { line++; lineStart = i + 1 }
        i++
    }
    return line to (safe - lineStart + 1)
}
