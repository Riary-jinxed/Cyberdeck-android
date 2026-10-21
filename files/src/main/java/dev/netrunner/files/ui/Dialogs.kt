package dev.netrunner.files.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudButton
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.MonoFont
import dev.netrunner.deck.hudFieldColors
import dev.netrunner.deck.hudShape
import dev.netrunner.files.data.FileOps
import dev.netrunner.files.system.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Runs a disk operation off the main thread, then reports and refreshes. */
fun CoroutineScope.fileOp(context: Context, explorer: Explorer, done: String, block: () -> Unit) {
    launch {
        val error = withContext(Dispatchers.IO) { runCatching(block).exceptionOrNull() }
        Toast.makeText(context, error?.message ?: done, Toast.LENGTH_SHORT).show()
        explorer.refresh()
    }
}

@Composable
private fun HudDialog(title: String, onDismiss: () -> Unit, confirm: @Composable () -> Unit, body: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Cp.Panel,
        shape = hudShape(14.dp),
        title = { Text(title, color = Cp.Yellow, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 1.sp) },
        text = body,
        confirmButton = confirm,
        dismissButton = { HudButton("ANNULER", color = Cp.Dim, onClick = onDismiss) },
    )
}

/** New folder or rename: the name without its extension is preselected, as in most file managers. */
@Composable
fun NameDialog(title: String, initial: String, action: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val dot = initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, dot))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    HudDialog(title, onDismiss, confirm = { HudButton(action, filled = true) { onConfirm(value.text) } }) {
        TextField(
            value, { value = it }, singleLine = true, colors = hudFieldColors(),
            textStyle = TextStyle(fontFamily = MonoFont, fontSize = 17.sp),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
}

@Composable
fun DeleteDialog(files: List<File>, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    HudDialog("SUPPRIMER ?", onDismiss, confirm = { HudButton("SUPPRIMER", color = Cp.Red, filled = true, onClick = onConfirm) }) {
        Column {
            Text(
                if (files.size == 1) "« ${files[0].name} »" + if (files[0].isDirectory) " et tout son contenu" else ""
                else "${files.size} éléments, dossiers compris avec leur contenu",
                color = Cp.Text, fontFamily = HudFont, fontSize = 16.sp,
            )
            Text("Définitif : pas de corbeille.", color = Cp.Red, fontFamily = HudFont, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
fun InfoDialog(file: File, onDismiss: () -> Unit) {
    // Folder sizes are counted in the background, they can take a moment on big trees.
    val total by produceState<Pair<Long, Int>?>(null, file) {
        value = withContext(Dispatchers.IO) { if (file.isDirectory) FileOps.treeSize(file) else file.length() to 1 }
    }
    HudDialog(file.name, onDismiss, confirm = { HudButton("OK", filled = true, onClick = onDismiss) }) {
        Column {
            Info("Chemin", Storage.pretty(file))
            Info("Type", if (file.isDirectory) "dossier" else Storage.mime(file))
            Info(
                "Taille",
                total?.let { (bytes, count) -> FileOps.size(bytes) + if (file.isDirectory) " · $count fichiers" else "" } ?: "calcul…",
            )
            Info("Modifié", FileOps.date(file))
            Info("Droits", FileOps.flags(file))
        }
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(label, color = Cp.Dim, fontFamily = HudFont, fontSize = 14.sp, modifier = Modifier.weight(0.35f))
        Text(value, color = Cp.Text, fontFamily = MonoFont, fontSize = 14.sp, modifier = Modifier.weight(0.65f))
    }
}
