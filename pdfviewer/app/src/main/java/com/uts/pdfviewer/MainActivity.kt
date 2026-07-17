package com.uts.pdfviewer

import android.annotation.SuppressLint
import android.app.Activity
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
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * A minimal PDF viewer: shows a PDF with pdf.js (selectable text, sharp zoom),
 * can scan a paper document to a PDF (ML Kit — auto edge-crop + cleanup filters),
 * print, and close.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = incomingPdf(intent)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    val context = LocalContext.current
                    val scope = rememberCoroutineScope()

                    // --- Activation gate: cover the app until it's activated ---
                    var licensed by remember {
                        mutableStateOf(License.state(context).let {
                            it == License.State.ACTIVE || it == License.State.DISABLED
                        })
                    }
                    if (!licensed) {
                        ActivationScreen(onActivated = { licensed = true })
                        return@Surface
                    }

                    var uri by remember { mutableStateOf(incoming) }
                    var showScanner by remember { mutableStateOf(false) }

                    // --- Document scanner → PDF, then offer to save it ---
                    var scannedPdf by remember { mutableStateOf<Uri?>(null) }
                    val saveScan = rememberLauncherForActivityResult(
                        ActivityResultContracts.CreateDocument("application/pdf")
                    ) { dest ->
                        val src = scannedPdf
                        if (dest != null && src != null) copyInBackground(context, src, dest)
                        scannedPdf = null
                    }
                    val scanLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartIntentSenderForResult()
                    ) { result ->
                        if (result.resultCode == Activity.RESULT_OK) {
                            val pdf = GmsDocumentScanningResult
                                .fromActivityResultIntent(result.data)?.pdf?.uri
                            if (pdf != null) {
                                scannedPdf = pdf
                                uri = pdf                 // show the scan straight away
                                saveScan.launch("scan.pdf") // and offer to save it
                            }
                        }
                    }
                    val onScan: () -> Unit = {
                        val options = GmsDocumentScannerOptions.Builder()
                            .setGalleryImportAllowed(true)
                            .setPageLimit(30)
                            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                            .build()
                        GmsDocumentScanning.getClient(options)
                            .getStartScanIntent(context as Activity)
                            .addOnSuccessListener { sender ->
                                scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
                            }
                            .addOnFailureListener {
                                // No Google scanner on this device → use the built-in one.
                                showScanner = true
                            }
                    }

                    val current = uri
                    when {
                        showScanner -> ScannerScreen(
                            onDone = { dest ->
                                scope.launch {
                                    ScanUtil.exportTo(context, dest)
                                    showScanner = false
                                    uri = dest
                                }
                            },
                            onCancel = { showScanner = false },
                        )
                        current == null -> HomeScreen(onScan = onScan, onOpened = { uri = it })
                        else -> PdfScreen(uri = current, onScan = onScan, onClose = { finish() })
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

private fun copyInBackground(context: Context, src: Uri, dest: Uri) {
    Thread {
        runCatching {
            context.contentResolver.openInputStream(src)?.use { input ->
                context.contentResolver.openOutputStream(dest)?.use { output -> input.copyTo(output) }
            }
        }
    }.start()
}

@Composable
private fun ActivationScreen(onActivated: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val device = remember { License.deviceIdPretty(context) }
    var code by remember { mutableStateOf("") }
    var seed by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var showOwner by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        ) {
            Text("🔐", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(4.dp))
            Text("تفعيل التطبيق", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "أرسل «رقم الجهاز» للمطوّر ليصلك رمز التفعيل (يعمل بدون إنترنت).",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            Text("رقم الجهاز", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(device, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(License.deviceId(context)))
                isError = false; message = "نُسخ رقم الجهاز ✓"
            }) { Text("📋 نسخ رقم الجهاز") }
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                value = code, onValueChange = { code = it },
                label = { Text("أدخل رمز التفعيل") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    if (License.tryActivate(context, code)) onActivated()
                    else { isError = true; message = "رمز غير صالح لهذا الجهاز." }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("تفعيل") }
            if (message.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    message,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = { showOwner = !showOwner }) {
                Text("استرجاع المالك (بذرة سرّية)", style = MaterialTheme.typography.bodySmall)
            }
            if (showOwner) {
                OutlinedTextField(
                    value = seed, onValueChange = { seed = it },
                    label = { Text("البذرة السرّية (64 hex)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (License.recoverWithSeed(context, seed)) onActivated()
                        else { isError = true; message = "بذرة غير مطابقة." }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("تفعيل بالبذرة") }
            }
        }
    }
}

@Composable
private fun HomeScreen(onScan: () -> Unit, onOpened: (Uri) -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: ""
    }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { picked -> if (picked != null) onOpened(picked) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Button(onClick = { picker.launch(arrayOf("application/pdf")) }) { Text("افتح ملف PDF") }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onScan) { Text("مسح ضوئي") }
        }
        // Version shown at the bottom so you can confirm the installed build.
        Text(
            "الإصدار $version",
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PdfScreen(uri: Uri, onScan: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onScan) { Text("مسح ضوئي") }
                    TextButton(onClick = {
                        val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                        pm.print("PDF", PdfPrintAdapter(context, uri, "PDF"), null)
                    }) { Text("طباعة") }
                }
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
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PdfWebView(pdfFile: File, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        onRelease = { web -> web.stopLoading(); web.loadUrl("about:blank"); web.destroy() },
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
    )
}
