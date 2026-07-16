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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

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
                // Auto-detect the paper's edges; fall back to a safe inset if unsure.
                corners = ScanUtil.detectDocument(bmp) ?: ScanUtil.defaultCorners(bmp.width, bmp.height)
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
    var touch by remember { mutableStateOf(Offset.Zero) }
    val accent = ComposeColor(0xFF00E5FF)
    ComposeCanvas(
        modifier = modifier.pointerInput(bitmap) {
            detectDragGestures(
                onDragStart = { pos ->
                    touch = pos
                    val m = fitMapping(size.width.toFloat(), size.height.toFloat(), bitmap.width, bitmap.height)
                    dragging = nearestCorner(corners, pos, m)
                },
                onDragEnd = { dragging = -1 },
                onDrag = { change, _ ->
                    if (dragging >= 0) {
                        touch = change.position
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
        // Selection quad: translucent fill + outline.
        val pts = Array(4) { Offset(m.ox + corners[it * 2] * m.scale, m.oy + corners[it * 2 + 1] * m.scale) }
        val quadPath = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until 4) lineTo(pts[i].x, pts[i].y)
            close()
        }
        drawPath(quadPath, accent.copy(alpha = 0.12f))
        for (i in 0 until 4) {
            drawLine(accent, pts[i], pts[(i + 1) % 4], strokeWidth = 5f)
        }
        for (p in pts) {
            drawCircle(ComposeColor.White, radius = 34f, center = p)
            drawCircle(accent, radius = 34f, center = p, style = Stroke(6f))
            drawCircle(accent, radius = 8f, center = p)
        }

        // Magnifier: while dragging, show a zoomed loupe of the area under the corner.
        if (dragging in 0 until 4) {
            val cxImg = corners[dragging * 2]
            val cyImg = corners[dragging * 2 + 1]
            val magR = 150f
            val zoom = 2.6f
            val srcHalf = magR / zoom
            val sx = (cxImg - srcHalf).coerceIn(0f, (bitmap.width - 2 * srcHalf).coerceAtLeast(0f))
            val sy = (cyImg - srcHalf).coerceIn(0f, (bitmap.height - 2 * srcHalf).coerceAtLeast(0f))
            // Keep the loupe on the side away from the finger.
            val onLeft = touch.x < size.width / 2f
            val mcx = if (onLeft) size.width - magR - 28f else magR + 28f
            val mcy = magR + 28f
            val ring = Path().apply { addOval(Rect(mcx - magR, mcy - magR, mcx + magR, mcy + magR)) }
            clipPath(ring) {
                drawImage(
                    image = image,
                    srcOffset = IntOffset(sx.toInt(), sy.toInt()),
                    srcSize = IntSize((2 * srcHalf).toInt().coerceAtLeast(1), (2 * srcHalf).toInt().coerceAtLeast(1)),
                    dstOffset = IntOffset((mcx - magR).toInt(), (mcy - magR).toInt()),
                    dstSize = IntSize((2 * magR).toInt(), (2 * magR).toInt()),
                )
            }
            // Crosshair at the exact corner position inside the loupe.
            val chx = mcx + (cxImg - (sx + srcHalf)) * zoom
            val chy = mcy + (cyImg - (sy + srcHalf)) * zoom
            drawLine(accent, Offset(chx - 26f, chy), Offset(chx + 26f, chy), strokeWidth = 3f)
            drawLine(accent, Offset(chx, chy - 26f), Offset(chx, chy + 26f), strokeWidth = 3f)
            drawCircle(ComposeColor.White, radius = magR, center = Offset(mcx, mcy), style = Stroke(6f))
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

    // ---- Automatic paper-edge detection (no library, works on every device) ----
    // Pipeline: downscale → grayscale → blur → Sobel edges → Hough line vote →
    // pick the outer left/right/top/bottom borders → intersect into 4 corners.

    private class Line(val theta: Double, val rho: Double, val votes: Int)

    /**
     * Detect the four corners of the paper in [src]. Returns TL,TR,BR,BL in *source*
     * pixels, or null when it isn't confident (caller then falls back to an inset).
     */
    fun detectDocument(src: Bitmap): FloatArray? = runCatching {
        val maxDim = 480
        val longSide = max(src.width, src.height)
        val scale = if (longSide > maxDim) longSide.toFloat() / maxDim else 1f
        val w = max(1, (src.width / scale).roundToInt())
        val h = max(1, (src.height / scale).roundToInt())
        val small = Bitmap.createScaledBitmap(src, w, h, true)

        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small != src) small.recycle()

        // Grayscale (luma).
        val gray = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            gray[i] = (77 * ((c shr 16) and 0xFF) + 150 * ((c shr 8) and 0xFF) + 29 * (c and 0xFF)) shr 8
        }
        val blur = boxBlur(gray, w, h)
        val mag = sobel(blur, w, h)

        // Keep only the strongest ~12% of edge pixels.
        val thr = percentile(mag, 0.88f)

        // Hough accumulator over the strong edge pixels.
        val nTheta = 180
        val cosT = DoubleArray(nTheta) { cos(Math.PI * it / nTheta) }
        val sinT = DoubleArray(nTheta) { sin(Math.PI * it / nTheta) }
        val diag = sqrt((w * w + h * h).toDouble()).toInt() + 1
        val nRho = 2 * diag + 1
        val acc = IntArray(nTheta * nRho)
        var strongCount = 0
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (mag[row + x] < thr) continue
                strongCount++
                for (t in 0 until nTheta) {
                    val r = (x * cosT[t] + y * sinT[t]).roundToInt() + diag
                    acc[t * nRho + r]++
                }
            }
        }
        if (strongCount < 200) return null

        // Gather candidate line cells, strongest first.
        val minVotes = max((0.20 * min(w, h)).toInt(), 24)
        val cand = ArrayList<IntArray>() // [votes, tIndex, rSigned]
        for (t in 0 until nTheta) {
            val base = t * nRho
            for (r in 1 until nRho - 1) {
                val v = acc[base + r]
                if (v < minVotes) continue
                if (v < acc[base + r - 1] || v < acc[base + r + 1]) continue
                if (t > 0 && v < acc[base - nRho + r]) continue
                if (t < nTheta - 1 && v < acc[base + nRho + r]) continue
                cand.add(intArrayOf(v, t, r - diag))
            }
        }
        if (cand.size < 4) return null
        cand.sortByDescending { it[0] }

        // Greedy non-maximum suppression so one paper edge = one line.
        val kept = ArrayList<IntArray>()
        for (c in cand) {
            var ok = true
            for (p in kept) {
                val dt = min(abs(c[1] - p[1]), nTheta - abs(c[1] - p[1]))
                if (dt <= 8 && abs(c[2] - p[2]) <= 15) { ok = false; break }
            }
            if (ok) kept.add(c)
        }
        val lines = kept.map { Line(Math.PI * it[1] / nTheta, it[2].toDouble(), it[0]) }

        // Split into near-vertical (theta≈0/180) and near-horizontal (theta≈90) borders,
        // each already ordered by vote strength.
        val deg = Math.PI / 180.0
        val verticals = lines.filter { it.theta < 35 * deg || it.theta > 145 * deg }
        val horizontals = lines.filter { it.theta in 55 * deg..125 * deg }
        if (verticals.size < 2 || horizontals.size < 2) return null

        val cx = w / 2.0; val cy = h / 2.0
        // x where a vertical line crosses the vertical centre.
        fun xAt(l: Line): Double = (l.rho - cy * sin(l.theta)) / (cos(l.theta).let { if (abs(it) < 1e-6) 1e-6 else it })
        // y where a horizontal line crosses the horizontal centre.
        fun yAt(l: Line): Double = (l.rho - cx * cos(l.theta)) / (sin(l.theta).let { if (abs(it) < 1e-6) 1e-6 else it })

        // The true paper borders are the strongest (longest) lines on each side.
        val left = verticals.firstOrNull { xAt(it) < cx } ?: return null
        val right = verticals.firstOrNull { xAt(it) > cx } ?: return null
        val top = horizontals.firstOrNull { yAt(it) < cy } ?: return null
        val bottom = horizontals.firstOrNull { yAt(it) > cy } ?: return null

        // Only trust detection when the paper fills most of the frame — the normal
        // scanning case. This avoids latching onto an inner fold/shadow/text line
        // and cropping to half the page; if unsure we return null (manual fallback).
        if (xAt(left) > 0.32 * w || xAt(right) < 0.68 * w) return null
        if (yAt(top) > 0.32 * h || yAt(bottom) < 0.68 * h) return null

        val tl = intersect(top, left) ?: return null
        val tr = intersect(top, right) ?: return null
        val br = intersect(bottom, right) ?: return null
        val bl = intersect(bottom, left) ?: return null
        val quad = floatArrayOf(
            tl[0], tl[1], tr[0], tr[1], br[0], br[1], bl[0], bl[1],
        )

        // Sanity: corners inside a small margin, quad covers most of the frame.
        val marginX = w * 0.06f; val marginY = h * 0.06f
        for (i in 0 until 4) {
            if (quad[i * 2] < -marginX || quad[i * 2] > w + marginX) return null
            if (quad[i * 2 + 1] < -marginY || quad[i * 2 + 1] > h + marginY) return null
        }
        if (quadArea(quad) < 0.45 * w * h) return null

        // Map back to source pixels and clamp to bounds.
        val out = FloatArray(8)
        for (i in 0 until 4) {
            out[i * 2] = (quad[i * 2] * scale).coerceIn(0f, src.width.toFloat())
            out[i * 2 + 1] = (quad[i * 2 + 1] * scale).coerceIn(0f, src.height.toFloat())
        }
        out
    }.getOrNull()

    private fun intersect(a: Line, b: Line): FloatArray? {
        val det = cos(a.theta) * sin(b.theta) - cos(b.theta) * sin(a.theta)
        if (abs(det) < 1e-6) return null // parallel
        val x = (a.rho * sin(b.theta) - b.rho * sin(a.theta)) / det
        val y = (cos(a.theta) * b.rho - cos(b.theta) * a.rho) / det
        return floatArrayOf(x.toFloat(), y.toFloat())
    }

    private fun quadArea(q: FloatArray): Double {
        var area = 0.0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            area += q[i * 2].toDouble() * q[j * 2 + 1] - q[j * 2].toDouble() * q[i * 2 + 1]
        }
        return abs(area) / 2.0
    }

    private fun boxBlur(g: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0; var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val ny = y + dy; val nx = x + dx
                if (nx in 0 until w && ny in 0 until h) { sum += g[ny * w + nx]; n++ }
            }
            out[y * w + x] = sum / n
        }
        return out
    }

    private fun sobel(g: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val tl = g[(y - 1) * w + x - 1]; val tc = g[(y - 1) * w + x]; val tr = g[(y - 1) * w + x + 1]
            val ml = g[y * w + x - 1]; val mr = g[y * w + x + 1]
            val bl = g[(y + 1) * w + x - 1]; val bc = g[(y + 1) * w + x]; val br = g[(y + 1) * w + x + 1]
            val gx = (tr + 2 * mr + br) - (tl + 2 * ml + bl)
            val gy = (bl + 2 * bc + br) - (tl + 2 * tc + tr)
            out[y * w + x] = abs(gx) + abs(gy)
        }
        return out
    }

    private fun percentile(v: IntArray, p: Float): Int {
        var mx = 1
        for (x in v) if (x > mx) mx = x
        val bins = 1024
        val hist = IntArray(bins)
        for (x in v) hist[(x.toLong() * (bins - 1) / mx).toInt()]++
        val target = (v.size * p).toInt()
        var cum = 0
        for (b in 0 until bins) {
            cum += hist[b]
            if (cum >= target) return (b.toLong() * mx / (bins - 1)).toInt()
        }
        return mx
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
