package dev.netrunner.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

fun encodeQr(text: String): BitMatrix? = runCatching {
    QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
    )
}.getOrNull()

/** Black on white so every scanner reads it, framed in the HUD style. */
@Composable
fun QrImage(matrix: BitMatrix, modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color.White)) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

@Composable
fun QrOverlay(text: String, onClose: () -> Unit) {
    val matrix = remember(text) { encodeQr(text) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Cp.Background.copy(alpha = 0.94f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("// QR", color = Cp.Yellow, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (matrix != null) {
                Box(Modifier.hudPanel(borderColor = Cp.Yellow, cut = 14.dp).padding(10.dp)) {
                    QrImage(matrix, Modifier.size(260.dp))
                }
            } else {
                Text("texte trop long pour un QR", color = Cp.Red, fontFamily = HudFont, fontSize = 12.sp)
            }
            Text(
                text,
                color = Cp.Text,
                fontFamily = HudFont,
                fontSize = 12.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            Text("touche pour fermer", color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp)
        }
    }
}

/**
 * A printable label: the QR on white with a title and up to two lines under it, as a bitmap
 * ready to share to a label printer app or a photo print.
 */
fun qrLabelBitmap(text: String, title: String, lines: List<String> = emptyList()): android.graphics.Bitmap? {
    val matrix = encodeQr(text) ?: return null
    val cell = 12
    val qr = matrix.width * cell
    val pad = 24
    val titleSize = 40f
    val lineSize = 28f
    val height = pad + qr + 16 + titleSize.toInt() + lines.size * (lineSize.toInt() + 8) + pad
    val width = qr + pad * 2
    val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)
    val black = android.graphics.Paint().apply { color = android.graphics.Color.BLACK }
    for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
        if (matrix[x, y]) {
            val left = (pad + x * cell).toFloat()
            val top = (pad + y * cell).toFloat()
            canvas.drawRect(left, top, left + cell, top + cell, black)
        }
    }
    fun fit(paint: android.graphics.Paint, s: String): String {
        if (paint.measureText(s) <= width - pad * 2) return s
        var cut = s
        while (cut.isNotEmpty() && paint.measureText("$cut…") > width - pad * 2) cut = cut.dropLast(1)
        return "$cut…"
    }
    val titlePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        textSize = titleSize
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = android.graphics.Paint.Align.CENTER
    }
    val linePaint = android.graphics.Paint(titlePaint).apply {
        textSize = lineSize
        typeface = android.graphics.Typeface.MONOSPACE
    }
    var y = pad + qr + 16 + titleSize
    canvas.drawText(fit(titlePaint, title), width / 2f, y, titlePaint)
    lines.forEach { line ->
        y += lineSize + 8
        canvas.drawText(fit(linePaint, line), width / 2f, y, linePaint)
    }
    return bitmap
}

/** Shares [bitmap] as a PNG (label printer apps, messages, print service). */
fun shareBitmap(context: android.content.Context, bitmap: android.graphics.Bitmap, name: String) {
    runCatching {
        val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifBlank { "etiquette" }
        val file = java.io.File(dir, "$safe.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(android.content.Intent.EXTRA_STREAM, uri)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            android.content.Intent.createChooser(send, "Partager l'étiquette").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        android.widget.Toast.makeText(context, "Partage impossible", android.widget.Toast.LENGTH_SHORT).show()
    }
}
