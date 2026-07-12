package com.uts.pdfviewer

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import android.content.Context
import android.print.PrintManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A deliberately minimal PDF viewer: it opens a PDF, shows its pages, and offers
 * a single Close button. No editing, no tools, no menus.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = incomingPdf(intent)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    var uri by remember { mutableStateOf(incoming) }
                    val picker = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument()
                    ) { picked -> if (picked != null) uri = picked }

                    val current = uri
                    if (current == null) {
                        PickScreen(onPick = { picker.launch(arrayOf("application/pdf")) })
                    } else {
                        PdfScreen(uri = current, onClose = { finish() })
                    }
                }
            }
        }
    }

    private fun incomingPdf(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
        else -> null
    }
}

@Composable
private fun PickScreen(onPick: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Button(onClick = onPick) { Text("افتح ملف PDF") }
    }
}

@Composable
private fun PdfScreen(uri: Uri, onClose: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Save a copy of the open PDF to a location the user picks.
    val saveCopy = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { dest ->
        if (dest != null) scope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    context.contentResolver.openOutputStream(dest)?.use { out -> input.copyTo(out) }
                }
            }
        }
    }
    fun suggestedName(): String {
        var name = "copy.pdf"
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst() && !c.isNull(i)) name = c.getString(i)
            }
        }
        return name
    }

    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offset = if (scale > 1f) offset + panChange else Offset.Zero
    }

    Column(Modifier.fillMaxSize()) {
        // Top bar: Print + Close.
        Surface(tonalElevation = 3.dp) {
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { saveCopy.launch(suggestedName()) }) {
                        Text("نسخ")
                    }
                    TextButton(onClick = {
                        val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                        pm.print("PDF", PdfPrintAdapter(context, uri, "PDF"), null)
                    }) {
                        Text("طباعة")
                    }
                }
                IconButton(onClick = onClose, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "إغلاق", modifier = Modifier.size(24.dp))
                }
            }
        }

        val holder = remember(uri) { object { var pages: PdfPages? = null } }
        val result by produceState<Result<PdfPages>?>(initialValue = null, uri) {
            val r = withContext(Dispatchers.IO) {
                runCatching { PdfRenderHelper.open(context, uri) ?: error("open failed") }
            }
            holder.pages = r.getOrNull()
            value = r
        }
        DisposableEffect(uri) { onDispose { holder.pages?.close() } }

        val pages = result?.getOrNull()
        when {
            result == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            pages == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("تعذّر فتح الملف", modifier = Modifier.padding(24.dp))
            }
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val widthPx = with(density) { maxWidth.toPx() }.toInt().coerceIn(1, 2048)
                Box(
                    Modifier
                        .fillMaxSize()
                        .transformable(transformState)
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = {
                                if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                            })
                        }
                        .graphicsLayer {
                            scaleX = scale; scaleY = scale
                            translationX = offset.x; translationY = offset.y
                        }
                ) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        state = listState,
                        userScrollEnabled = scale <= 1.05f,
                    ) {
                        items(pages.pageCount) { index -> PageView(pages, index, widthPx) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageView(pages: PdfPages, index: Int, widthPx: Int) {
    val state by produceState(initialValue = false to (null as Bitmap?), index, widthPx) {
        val bmp = withContext(Dispatchers.IO) { runCatching { pages.render(index, widthPx) }.getOrNull() }
        value = true to bmp
    }
    val (done, bmp) = state
    when {
        bmp != null -> Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 4.dp),
        )
        done -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            Text("تعذّر عرض الصفحة ${index + 1}")
        }
        else -> Box(Modifier.fillMaxWidth().height(360.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}
