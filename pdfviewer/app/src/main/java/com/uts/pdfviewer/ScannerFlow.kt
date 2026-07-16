package com.uts.pdfviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A self-contained document scanner that does NOT depend on Google Play services,
 * so it works on every Android device: capture a photo, drag the four corners
 * onto the paper (perspective crop removes everything outside it), optionally
 * clean it up (grayscale + contrast to drop shadows/noise), collect pages, and
 * export a PDF.
 */
@Composable
fun ScannerScreen(onDone: (Uri) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val pages = remember { mutableStateListOf<Bitmap>() }
    var captured by remember { mutableStateOf<Bitmap?>(null) }
    var corners by remember { mutableStateOf(FloatArray(8)) }
    var cleaned by remember { mutableStateOf(true) }
    var captureFile by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val f = captureFile
        if (ok && f != null) {
            val bmp = ScanUtil.loadDownscaled(f, 2400)
            if (bmp != null) {
                captured = bmp
                corners = ScanUtil.defaultCorners(bmp.width, bmp.height)
            }
        } else if (pages.isEmpty()) {
            onCancel() // user backed out of the camera with nothing scanned
        }
    }

    fun launchCamera() {
        val dir = File(context.cacheDir, "scan").apply { mkdirs() }
        val f = File(dir, "cap_${pages.size}_${System.currentTimeMillis()}.jpg")
        captureFile = f
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        takePicture.launch(uri)
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { dest ->
        if (dest != null) onDone(dest) // MainActivity will copy from the temp PDF + open it
    }

    LaunchedEffect(Unit) { if (pages.isEmpty() && captured == null) launchCamera() }

    val shot = captured
    if (shot != null) {
        // ---- Crop / clean screen ----
        Column(Modifier.fillMaxSize().background(ComposeColor(0xFF202124))) {
            CornerCropCanvas(
                bitmap = shot,
                corners = corners,
                onCornersChange = { corners = it },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(selected = !cleaned, onClick = { cleaned = false }, label = { Text("كما هي") })
                FilterChip(selected = cleaned, onClick = { cleaned = true }, label = { Text("تصفية") })
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { captured = null; if (pages.isEmpty()) onCancel() }, modifier = Modifier.weight(1f)) { Text("إلغاء") }
                Button(
                    onClick = {
                        val processed = ScanUtil.process(shot, corners, cleaned)
                        pages.add(processed)
                        captured = null
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("إضافة الصفحة") }
            }
        }
    } else {
        // ---- Pages overview ----
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("عدد الصفحات: ${pages.size}", style = MaterialTheme.typography.titleMedium)
            Box(Modifier.padding(16.dp))
            Button(onClick = { launchCamera() }, modifier = Modifier.fillMaxWidth()) { Text("التقاط صفحة") }
            Box(Modifier.padding(6.dp))
            Button(
                onClick = {
                    busy = true
                    val dir = File(context.cacheDir, "scan").apply { mkdirs() }
                    val pdf = File(dir, "scan.pdf")
                    val ok = ScanUtil.writePdf(pages.toList(), pdf)
                    busy = false
                    if (ok) {
                        // Stage the PDF so MainActivity can copy it to the chosen file.
                        ScanUtil.lastPdf = pdf
                        saveLauncher.launch("scan.pdf")
                    }
                },
                enabled = pages.isNotEmpty() && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("حفظ PDF") }
            Box(Modifier.padding(6.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("خروج") }
        }
    }
}

@Composable
private fun CornerCropCanvas(
    bitmap: Bitmap,
    corners: FloatArray,
    onCornersChange: (FloatArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var dragging by remember { mutableStateOf(-1) }
    ComposeCanvas(
        modifier = modifier.pointerInput(bitmap) {
            detectDragGestures(
                onDragStart = { pos ->
                    val m = fitMapping(size.width.toFloat(), size.height.toFloat(), bitmap.width, bitmap.height)
                    dragging = nearestCorner(corners, pos, m)
                },
                onDragEnd = { dragging = -1 },
                onDrag = { change, _ ->
                    if (dragging >= 0) {
                        val m = fitMapping(size.width.toFloat(), size.height.toFloat(), bitmap.width, bitmap.height)
                        val bx = ((change.position.x - m.ox) / m.scale).coerceIn(0f, bitmap.width.toFloat())
                        val by = ((change.position.y - m.oy) / m.scale).coerceIn(0f, bitmap.height.toFloat())
                        val c = corners.copyOf()
                        c[dragging * 2] = bx; c[dragging * 2 + 1] = by
                        onCornersChange(c)
                    }
                    change.consume()
                },
            )
        },
    ) {
        val m = fitMapping(size.width, size.height, bitmap.width, bitmap.height)
        drawImage(
            image = image,
            dstOffset = IntOffset(m.ox.toInt(), m.oy.toInt()),
            dstSize = IntSize((bitmap.width * m.scale).toInt(), (bitmap.height * m.scale).toInt()),
        )
        // quad outline
        val pts = Array(4) { Offset(m.ox + corners[it * 2] * m.scale, m.oy + corners[it * 2 + 1] * m.scale) }
        for (i in 0 until 4) {
            drawLine(ComposeColor(0xFF00E5FF), pts[i], pts[(i + 1) % 4], strokeWidth = 3f)
        }
        for (p in pts) {
            drawCircle(ComposeColor(0xFF00E5FF), radius = 22f, center = p)
            drawCircle(ComposeColor.White, radius = 9f, center = p)
        }
    }
}

private class FitMap(val scale: Float, val ox: Float, val oy: Float)

private fun fitMapping(boxW: Float, boxH: Float, bmpW: Int, bmpH: Int): FitMap {
    val scale = min(boxW / bmpW, boxH / bmpH)
    val ox = (boxW - bmpW * scale) / 2f
    val oy = (boxH - bmpH * scale) / 2f
    return FitMap(scale, ox, oy)
}

private fun nearestCorner(corners: FloatArray, pos: Offset, m: FitMap): Int {
    var best = -1; var bestD = Float.MAX_VALUE
    for (i in 0 until 4) {
        val dx = m.ox + corners[i * 2] * m.scale
        val dy = m.oy + corners[i * 2 + 1] * m.scale
        val d = hypot(pos.x - dx, pos.y - dy)
        if (d < bestD) { bestD = d; best = i }
    }
    return best // grab whichever corner is nearest the touch
}

/** Image processing + PDF, all with plain Android APIs (no library). */
object ScanUtil {
    @Volatile var lastPdf: File? = null

    fun defaultCorners(w: Int, h: Int): FloatArray {
        val ix = w * 0.08f; val iy = h * 0.08f
        return floatArrayOf(ix, iy, w - ix, iy, w - ix, h - iy, ix, h - iy) // TL,TR,BR,BL
    }

    fun loadDownscaled(file: File, maxDim: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        applyExifRotation(file, bmp)
    }.getOrNull()

    private fun applyExifRotation(file: File, bmp: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            else -> return bmp
        }
        return runCatching { Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true) }.getOrDefault(bmp)
    }

    /** Perspective-crop to the quad, then optionally clean up. */
    fun process(src: Bitmap, corners: FloatArray, cleaned: Boolean): Bitmap {
        val wTop = dist(corners, 0, 1); val wBot = dist(corners, 3, 2)
        val hL = dist(corners, 0, 3); val hR = dist(corners, 1, 2)
        var outW = max(wTop, wBot).toInt().coerceIn(1, 2200)
        var outH = max(hL, hR).toInt().coerceIn(1, 2600)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val dst = floatArrayOf(0f, 0f, outW.toFloat(), 0f, outW.toFloat(), outH.toFloat(), 0f, outH.toFloat())
        val m = Matrix()
        m.setPolyToPoly(corners, 0, dst, 0, 4)
        canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return if (cleaned) enhance(out) else out
    }

    private fun enhance(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val cm = ColorMatrix().apply { setSaturation(0f) }
        val contrast = 1.9f
        val translate = (-0.5f * contrast + 0.5f) * 255f + 28f // brighten to whiten the page
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, translate,
                    0f, contrast, 0f, 0f, translate,
                    0f, 0f, contrast, 0f, translate,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
        canvas.drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        return out
    }

    fun writePdf(pages: List<Bitmap>, dest: File): Boolean = runCatching {
        val doc = PdfDocument()
        pages.forEachIndexed { i, bmp ->
            val info = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, i + 1).create()
            val page = doc.startPage(info)
            page.canvas.drawBitmap(bmp, 0f, 0f, null)
            doc.finishPage(page)
        }
        FileOutputStream(dest).use { doc.writeTo(it) }
        doc.close()
        true
    }.getOrDefault(false)

    private fun dist(c: FloatArray, a: Int, b: Int): Float =
        hypot(c[a * 2] - c[b * 2], c[a * 2 + 1] - c[b * 2 + 1])

    /** Copy the staged scanned PDF into the user-chosen destination. */
    suspend fun exportTo(context: Context, dest: Uri) {
        val src = lastPdf ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                java.io.FileInputStream(src).use { input ->
                    context.contentResolver.openOutputStream(dest)?.use { output -> input.copyTo(output) }
                }
            }
        }
    }
}
