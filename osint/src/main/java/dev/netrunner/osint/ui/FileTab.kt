package dev.netrunner.osint.ui

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudButton
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.MonoFont
import dev.netrunner.deck.SectionTitle
import dev.netrunner.deck.hudFieldColors
import dev.netrunner.osint.data.FileIntel
import dev.netrunner.osint.data.Report
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Photos (EXIF, cleaning, reverse search) and any file's hashes. Nothing leaves the phone unless a link is opened. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FileTab(state: OsintState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var photo by remember { mutableStateOf<FileIntel.Picked?>(null) }
    var exif by remember { mutableStateOf<Report?>(null) }
    var hash by remember { mutableStateOf<Report?>(null) }
    var reading by remember { mutableStateOf(false) }
    var hashing by remember { mutableStateOf(false) }
    var cleaned by remember { mutableStateOf<ByteArray?>(null) }
    var imageUrl by rememberSaveable { mutableStateOf("") }

    fun saveTo(uri: Uri?) {
        val bytes = cleaned ?: return
        cleaned = null
        if (uri == null) return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }.isSuccess }
            Toast.makeText(context, if (ok) "Copie sans métadonnées enregistrée." else "Enregistrement impossible.", Toast.LENGTH_SHORT).show()
        }
    }
    val saveJpeg = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { saveTo(it) }
    val savePng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { saveTo(it) }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val p = FileIntel.describe(context, uri)
        photo = p
        exif = null
        scope.launch {
            reading = true
            exif = runCatching { FileIntel.exif(context, p) }.getOrElse { Report.failed("EXIF", p.name, "Lecture impossible.") }
            reading = false
        }
    }
    // Asked once before the first photo: without it Android blanks the GPS tags.
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { pickPhoto.launch(arrayOf("image/*")) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val f = FileIntel.describe(context, uri)
        hash = null
        scope.launch {
            hashing = true
            hash = runCatching { FileIntel.hashes(context, f) }.getOrElse { Report.failed("HASH", f.name, "Lecture impossible.") }
            hashing = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        SectionTitle("PHOTO", Cp.Yellow)
        Text("Métadonnées EXIF : appareil, date, position GPS, auteur.", color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
        HudButton("CHOISIR UNE PHOTO", Modifier.fillMaxWidth().padding(top = 4.dp)) {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED) pickPhoto.launch(arrayOf("image/*"))
            else askLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
        photo?.let { p ->
            Thumbnail(p.uri)
            exif?.let { r ->
                ReportCard(r, saved = state.isSaved(r), onSave = { state.add(r) }) {
                    FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("NETTOYER ET ENREGISTRER", Cp.Yellow) {
                            scope.launch {
                                val result = runCatching { FileIntel.clean(context, p) }
                                result.onSuccess { (bytes, mime) ->
                                    cleaned = bytes
                                    val name = FileIntel.cleanName(p.name, mime)
                                    if (mime == "image/png") savePng.launch(name) else saveJpeg.launch(name)
                                }.onFailure { Toast.makeText(context, it.message ?: "Nettoyage impossible.", Toast.LENGTH_SHORT).show() }
                            }
                        }
                        Chip("RECHERCHE INVERSÉE", Cp.Cyan) { sharePhoto(context, p.uri, p.mime) }
                    }
                }
            }
            if (reading) Message("Lecture des métadonnées…")
        }

        SectionTitle("IMAGE EN LIGNE", Cp.Yellow, Modifier.padding(top = 20.dp))
        Text("Adresse d'une image publique, pour la chercher ailleurs sur le web.", color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
        TextField(
            imageUrl, { imageUrl = it }, singleLine = true, colors = hudFieldColors(),
            placeholder = { Text("https://…/image.jpg", fontFamily = MonoFont, color = Cp.Dim) },
            textStyle = TextStyle(fontFamily = MonoFont, fontSize = 15.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        if (imageUrl.trim().startsWith("http")) {
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FileIntel.reverseLinks(imageUrl).forEach { l -> Chip("↗ ${l.label}", Cp.Cyan) { openUrl(context, l.url) } }
            }
        }

        SectionTitle("EMPREINTE", Cp.Yellow, Modifier.padding(top = 20.dp))
        Text("MD5, SHA-1 et SHA-256 de n'importe quel fichier, à comparer sur VirusTotal.", color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
        HudButton("CHOISIR UN FICHIER", Modifier.fillMaxWidth().padding(top = 4.dp)) { pickFile.launch(arrayOf("*/*")) }
        if (hashing) Message("Calcul des empreintes…")
        hash?.let { r -> Column(Modifier.padding(top = 10.dp)) { ReportCard(r, saved = state.isSaved(r), onSave = { state.add(r) }) } }
        Text(" ", modifier = Modifier.padding(bottom = 12.dp))
    }
}

/** A small preview of the picked photo, decoded at a reduced size. */
@Composable
private fun Thumbnail(uri: Uri) {
    val context = LocalContext.current
    var bmp by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 600 || bounds.outHeight / (sample * 2) >= 600) sample *= 2
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }?.asImageBitmap()
            }.getOrNull()
        }
    }
    bmp?.let { Image(it, null, Modifier.fillMaxWidth().height(180.dp).padding(vertical = 10.dp), contentScale = ContentScale.Fit) }
}

/** Hands the photo to a reverse-search app (Google Lens, Bing…) through the share sheet. */
private fun sharePhoto(context: android.content.Context, uri: Uri, mime: String) {
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri("photo", uri)
    context.startActivity(Intent.createChooser(send, "Recherche inversée avec…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
