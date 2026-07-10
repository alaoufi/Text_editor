package com.uts.editor.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.uts.editor.R
import com.uts.editor.util.PdfPages
import com.uts.editor.util.PdfRenderHelper
import com.uts.editor.viewmodel.PdfViewRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full-screen, read-only viewer that renders a scanned PDF's pages as images.
 * The Edit button runs OCR to turn the images into editable text.
 */
@Composable
fun PdfImageViewer(
    request: PdfViewRequest,
    ocrRunning: Boolean,
    ocrProgress: Pair<Int, Int>,
    onOcr: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    Dialog(
        onDismissRequest = { if (!ocrRunning) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onClose, enabled = !ocrRunning) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                        }
                        Text(
                            request.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                        )
                        Button(onClick = onOcr, enabled = !ocrRunning) {
                            if (ocrRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(Modifier.width(8.dp))
                                val (done, total) = ocrProgress
                                Text(if (total > 0) "$done/$total" else "…")
                            } else {
                                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.action_edit))
                            }
                        }
                    }
                }

                val pages by produceState<PdfPages?>(initialValue = null, request.uri) {
                    value = withContext(Dispatchers.IO) { PdfRenderHelper.open(context, request.uri) }
                }
                DisposableEffect(pages) { onDispose { pages?.close() } }

                val current = pages
                if (current == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val widthPx = with(density) { maxWidth.toPx() }.toInt().coerceIn(1, 1600)
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(current.pageCount) { index ->
                                PdfPageView(current, index, widthPx)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPageView(pages: PdfPages, index: Int, widthPx: Int) {
    val bitmap by produceState<Bitmap?>(initialValue = null, index, widthPx) {
        value = withContext(Dispatchers.IO) { runCatching { pages.render(index, widthPx) }.getOrNull() }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 4.dp),
        )
    } else {
        Box(
            Modifier.fillMaxWidth().height(360.dp),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }
    }
}
