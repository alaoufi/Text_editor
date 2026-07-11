package com.uts.editor.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.uts.editor.R
import com.uts.editor.viewmodel.HtmlViewRequest

/**
 * Full-screen, read-only viewer that shows a Word/Excel document with its
 * formatting preserved by rendering the converted HTML in a [WebView]. Pinch to
 * zoom is enabled; the Edit button extracts the document's text for editing
 * (native .docx/.xlsx layout can't be edited in place).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlDocViewer(
    request: HtmlViewRequest,
    onEdit: () -> Unit,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.action_close),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Text(
                            request.name,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Button(
                            onClick = onEdit,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.action_edit), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                // No scripts run: the HTML is our own static markup.
                                settings.javaScriptEnabled = false
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                                settings.setSupportZoom(true)
                                isVerticalScrollBarEnabled = true
                                isHorizontalScrollBarEnabled = true
                                loadDataWithBaseURL(null, request.html, "text/html", "UTF-8", null)
                            }
                        },
                    )
                }
            }
        }
    }
}
