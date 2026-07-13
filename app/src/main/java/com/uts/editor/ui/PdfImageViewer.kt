package com.uts.editor.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.uts.editor.R
import com.uts.editor.util.PdfPages
import com.uts.editor.util.PdfRenderHelper
import com.uts.editor.viewmodel.PdfViewRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Full-screen, read-only viewer that renders a scanned PDF's pages as images.
 * The Edit button runs OCR on the current page to turn it into editable text.
 */
@Composable
fun PdfImageViewer(
    request: PdfViewRequest,
    ocrRunning: Boolean,
    ocrProgress: Pair<Int, Int>,
    onOcr: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Pinch-to-zoom + pan state for the page images.
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offset = if (scale > 1f) offset + panChange else Offset.Zero
    }

    Dialog(
        onDismissRequest = { if (!ocrRunning) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            // Hold the opened document in a stable holder and close it only when the
            // viewer truly leaves (keying on the changing result closed it too early).
            val holder = remember(request.uri) { object { var pages: PdfPages? = null } }
            val result by produceState<Result<PdfPages>?>(initialValue = null, request.uri) {
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        withTimeout(30_000) {
                            PdfRenderHelper.open(context, request.uri) ?: error("open failed")
                        }
                    }
                }
                holder.pages = r.getOrNull()
                value = r
            }
            DisposableEffect(request.uri) { onDispose { holder.pages?.close() } }

            val current = result?.getOrNull()
            val pageCount = current?.pageCount ?: 0
            val currentIndex = listState.firstVisibleItemIndex

            Column(Modifier.fillMaxSize()) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onClose, enabled = !ocrRunning, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close), modifier = Modifier.size(20.dp))
                        }
                        // Page count + navigation (replaces the file name).
                        Row(
                            Modifier.weight(1f),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (pageCount > 0) {
                                IconButton(
                                    onClick = { scope.launch { listState.animateScrollToItem((currentIndex - 1).coerceAtLeast(0)) } },
                                    modifier = Modifier.size(30.dp),
                                ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.action_previous), modifier = Modifier.size(18.dp)) }
                                Text(
                                    "${(currentIndex + 1).coerceAtMost(pageCount)} / $pageCount",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                IconButton(
                                    onClick = { scope.launch { listState.animateScrollToItem((currentIndex + 1).coerceAtMost(pageCount - 1)) } },
                                    modifier = Modifier.size(30.dp),
                                ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.action_next), modifier = Modifier.size(18.dp)) }
                            }
                        }
                        Button(
                            onClick = { onOcr(currentIndex) },
                            enabled = !ocrRunning && pageCount > 0,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        ) {
                            if (ocrRunning) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            } else {
                                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.action_edit), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                when {
                    result == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.pdf_cannot_display), modifier = Modifier.padding(24.dp))
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
                                // While zoomed in, disable list scroll so two-finger
                                // drag pans the zoomed page instead of scrolling.
                                userScrollEnabled = scale <= 1.05f,
                            ) {
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
}

@Composable
private fun PdfPageView(pages: PdfPages, index: Int, widthPx: Int) {
    // first = render finished; second = the bitmap (null if it failed).
    val state by produceState(initialValue = false to (null as Bitmap?), index, widthPx) {
        val bmp = withContext(Dispatchers.IO) { runCatching { pages.render(index, widthPx) }.getOrNull() }
        value = true to bmp
    }
    val (done, bmp) = state
    // Recycle this page's bitmap when the row leaves the list (scrolled far away)
    // so a long PDF doesn't accumulate large bitmaps and exhaust memory.
    DisposableEffect(bmp) {
        onDispose { bmp?.let { if (!it.isRecycled) it.recycle() } }
    }
    when {
        bmp != null -> Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 4.dp),
        )
        done -> Box(
            Modifier.fillMaxWidth().height(140.dp).padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.pdf_page_failed, index + 1) +
                    (pages.lastError?.let { "\n$it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        else -> Box(
            Modifier.fillMaxWidth().height(360.dp),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }
    }
}
