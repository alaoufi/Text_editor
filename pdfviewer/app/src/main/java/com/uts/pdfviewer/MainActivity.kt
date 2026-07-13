package com.uts.pdfviewer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.print.PrintManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * A minimal PDF viewer that shows the PDF with pdf.js in a WebView, so the real
 * text layer is selectable and copyable (no OCR). Only Print and Close actions.
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
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Button(onClick = { picker.launch(arrayOf("application/pdf")) }) {
                                Text("افتح ملف PDF")
                            }
                        }
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

    override fun onDestroy() {
        super.onDestroy()
        // Deep cleanup: remove the temporary copy of the PDF so nothing lingers.
        runCatching { File(cacheDir, "pdfview").deleteRecursively() }
    }
}

@Composable
private fun PdfScreen(uri: Uri, onClose: () -> Unit) {
    val context = LocalContext.current

    // Copy the PDF to a private cache file once, off the main thread.
    val cacheFile by produceState<File?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "pdfview").apply { mkdirs() }
                val f = File(dir, "doc.pdf")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(f).use { input.copyTo(it) }
                }
                f
            }.getOrNull()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Surface(tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = {
                    val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                    pm.print("PDF", PdfPrintAdapter(context, uri, "PDF"), null)
                }) { Text("طباعة") }
                IconButton(onClick = onClose, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "إغلاق", modifier = Modifier.size(24.dp))
                }
            }
        }

        val file = cacheFile
        if (file == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            PdfWebView(file, Modifier.fillMaxSize())
        }
    }

    // When the viewer leaves the screen, delete the temporary PDF copy.
    DisposableEffect(uri) {
        onDispose { runCatching { File(context.cacheDir, "pdfview").deleteRecursively() } }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PdfWebView(pdfFile: File, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                .addPathHandler("/pdf/") { _ ->
                    runCatching {
                        WebResourceResponse("application/pdf", null, FileInputStream(pdfFile))
                    }.getOrNull()
                }
                .build()

            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.setSupportZoom(true)
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                isVerticalScrollBarEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
                }
                loadUrl("https://appassets.androidplatform.net/assets/pdfjs/viewer.html")
            }
        },
        onRelease = { webView ->
            // Tear the WebView down so pdf.js and its page bitmaps are freed.
            runCatching {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.clearHistory()
                webView.removeAllViews()
                webView.destroy()
            }
        },
    )
}
