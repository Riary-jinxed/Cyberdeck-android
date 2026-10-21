package dev.netrunner.scanner.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.MonoFont
import java.util.concurrent.Executors

/** Camera preview with a reticle; the first code seen is handed to [onFound] while [active]. */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
fun ScanTab(active: Boolean, onFound: (raw: String, format: String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scanner = remember { BarcodeScanning.getClient() }
    val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    val isActive by rememberUpdatedState(active)
    val found by rememberUpdatedState(onFound)

    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(context)
        val executor = Executors.newSingleThreadExecutor()
        future.addListener({
            val provider = future.get()
            val usePreview = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { proxy ->
                val image = proxy.image
                if (image == null || !isActive) {
                    proxy.close()
                    return@setAnalyzer
                }
                scanner.process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener { codes ->
                        val code = codes.firstOrNull { !it.rawValue.isNullOrEmpty() }
                        if (code != null && isActive) found(code.rawValue!!, formatName(code.format))
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            runCatching {
                provider.unbindAll()
                camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, usePreview, analysis)
            }.onFailure { Toast.makeText(context, "Caméra indisponible", Toast.LENGTH_SHORT).show() }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            if (future.isDone) runCatching { future.get().unbindAll() }
            executor.shutdown()
            camera = null
        }
    }
    DisposableEffect(Unit) { onDispose { scanner.close() } }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val input = runCatching { InputImage.fromFilePath(context, uri) }.getOrNull()
        if (input == null) {
            Toast.makeText(context, "Image illisible", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        scanner.process(input).addOnSuccessListener { codes ->
            val code = codes.firstOrNull { !it.rawValue.isNullOrEmpty() }
            if (code != null) found(code.rawValue!!, formatName(code.format))
            else Toast.makeText(context, "Aucun code trouvé dans l'image", Toast.LENGTH_SHORT).show()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ preview }, Modifier.fillMaxSize())
        Reticle(Modifier.fillMaxSize(), active)
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (active) "// ACQUISITION…" else "// CIBLE VERROUILLÉE", color = if (active) Cp.Cyan else Cp.Yellow,
                fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f),
            )
            Chip(if (torch) "LAMPE ✓" else "LAMPE", if (torch) Cp.Yellow else Cp.Cyan) {
                torch = !torch
                camera?.cameraControl?.enableTorch(torch)
            }
            Chip("IMAGE", Cp.Cyan) { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        }
        Text(
            "QR, Data Matrix, Aztec, PDF417, EAN, UPC, Code 128…", color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
        )
    }
}

/** Corner brackets around the target zone and a sweeping line while searching. */
@Composable
private fun Reticle(modifier: Modifier, active: Boolean) {
    val sweep by rememberInfiniteTransition(label = "sweep").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse), label = "line",
    )
    val yellow = Cp.Yellow
    val red = Cp.Red
    Canvas(modifier) {
        val side = minOf(size.width, size.height) * 0.68f
        val left = (size.width - side) / 2
        val top = (size.height - side) / 2
        val arm = side * 0.14f
        val w = 4.dp.toPx()
        val c = if (active) yellow else Color(0xFF39FF14)
        drawRect(Color.Black.copy(alpha = 0.35f), Offset.Zero, androidx.compose.ui.geometry.Size(size.width, top))
        drawRect(Color.Black.copy(alpha = 0.35f), Offset(0f, top + side), androidx.compose.ui.geometry.Size(size.width, size.height - top - side))
        listOf(
            Offset(left, top) to Offset(1f, 1f), Offset(left + side, top) to Offset(-1f, 1f),
            Offset(left, top + side) to Offset(1f, -1f), Offset(left + side, top + side) to Offset(-1f, -1f),
        ).forEach { (p, d) ->
            drawLine(c, p, Offset(p.x + arm * d.x, p.y), w, StrokeCap.Square)
            drawLine(c, p, Offset(p.x, p.y + arm * d.y), w, StrokeCap.Square)
        }
        if (active) {
            val y = top + side * sweep
            drawLine(red.copy(alpha = 0.8f), Offset(left + w, y), Offset(left + side - w, y), 2.dp.toPx())
        }
    }
}

fun formatName(format: Int): String = when (format) {
    Barcode.FORMAT_QR_CODE -> "QR"
    Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
    Barcode.FORMAT_AZTEC -> "Aztec"
    Barcode.FORMAT_PDF417 -> "PDF417"
    Barcode.FORMAT_EAN_13 -> "EAN-13"
    Barcode.FORMAT_EAN_8 -> "EAN-8"
    Barcode.FORMAT_UPC_A -> "UPC-A"
    Barcode.FORMAT_UPC_E -> "UPC-E"
    Barcode.FORMAT_CODE_128 -> "Code 128"
    Barcode.FORMAT_CODE_39 -> "Code 39"
    Barcode.FORMAT_CODE_93 -> "Code 93"
    Barcode.FORMAT_CODABAR -> "Codabar"
    Barcode.FORMAT_ITF -> "ITF"
    else -> "Code"
}
