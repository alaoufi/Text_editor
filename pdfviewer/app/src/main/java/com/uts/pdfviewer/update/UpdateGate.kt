package com.uts.pdfviewer.update

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * بوّابة تحديث إلزاميّة (أعلى التطبيق). تفحص عند الإقلاع وعند عودة التطبيق للواجهة
 * **إن وُجد إنترنت**. إن توفّرت نسخة أحدث تعرض شاشة حاجبة لا يمكن تجاوزها؛ وإن تعذّر
 * الفحص (لا إنترنت) لا تحجب التطبيق. لا تُمسّ أي بيانات — التحديث يُثبَّت فوق القديم.
 */
@Composable
fun UpdateGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var info by remember { mutableStateOf<UpdateInfo?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    // يعمل عند أوّل تكوين (الإقلاع) وعند كل عودة للواجهة.
    LaunchedEffect(resumeTick) {
        if (info == null && UpdateService.isOnline(context)) {
            info = withContext(Dispatchers.IO) {
                runCatching { UpdateService.check(context) }.getOrNull()
            }
        }
    }

    val up = info
    if (up != null) UpdateRequiredScreen(up) else content()
}

@Composable
private fun UpdateRequiredScreen(info: UpdateInfo) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf(-1) }   // -1 خامل، 0..100 تنزيل
    var message by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    val main = remember { Handler(Looper.getMainLooper()) }

    // لا يمكن تجاوز الشاشة بزرّ الرجوع.
    BackHandler(enabled = true) {}

    fun startUpdate() {
        if (!Installer.canInstall(context)) {
            Installer.openInstallSettings(context)
            isError = true
            message = "فعّل «تثبيت تطبيقات غير معروفة» لهذا التطبيق ثم اضغط «تحديث الآن»."
            return
        }
        scope.launch {
            progress = 0; isError = false; message = "جارٍ التنزيل…"
            val f = withContext(Dispatchers.IO) {
                UpdateService.downloadApk(context, info.url) { p -> main.post { progress = p } }
            }
            if (f != null) {
                message = "جارٍ فتح المثبّت…"
                Installer.install(context, f)
            } else {
                progress = -1; isError = true
                message = "تعذّر التنزيل. جرّب «تنزيل عبر المتصفّح»."
            }
        }
    }

    fun openBrowser() {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(info.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }
    }

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("🚀", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(6.dp))
            Text("تحديث مطلوب", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "يتوفّر إصدار أحدث (${info.version}). يجب التحديث للمتابعة — تُحفَظ بياناتك ويُثبَّت فوق النسخة الحالية.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            if (progress in 0..100) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text("$progress%", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(12.dp))
            }
            Button(
                onClick = { startUpdate() },
                enabled = progress !in 0..99,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("تحديث الآن") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { openBrowser() }, modifier = Modifier.fillMaxWidth()) {
                Text("تنزيل عبر المتصفّح")
            }
            if (message.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    message,
                    textAlign = TextAlign.Center,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
