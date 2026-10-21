package dev.netrunner.launcher.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.netrunner.launcher.widgets.DeckWidgets

/** Second home page: Android widgets, each in a HUD frame with its own small toolbar. */
@Composable
fun WidgetsPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    remember { DeckWidgets.load(context) }
    var picking by remember { mutableStateOf(false) }
    // Widget being added, kept while the bind and configure screens are shown.
    var pending by remember { mutableStateOf<Pair<Int, AppWidgetProviderInfo>?>(null) }

    val configure = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pending?.let { (id, _) ->
            if (result.resultCode == Activity.RESULT_OK) DeckWidgets.add(context, id) else DeckWidgets.discard(context, id)
        }
        pending = null
    }

    fun configureOrAdd(id: Int, info: AppWidgetProviderInfo) {
        if (info.configure == null) {
            DeckWidgets.add(context, id)
            pending = null
            return
        }
        pending = id to info
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
            .setComponent(info.configure)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        runCatching { configure.launch(intent) }.onFailure {
            // Configure screen not exported: let the host start it, and keep the widget.
            (context as? Activity)?.let { activity ->
                runCatching { DeckWidgets.host(context).startAppWidgetConfigureActivityForResult(activity, id, 0, 7001, null) }
            }
            DeckWidgets.add(context, id)
            pending = null
        }
    }

    val bind = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val p = pending
        if (p != null && result.resultCode == Activity.RESULT_OK) configureOrAdd(p.first, p.second)
        else {
            p?.let { DeckWidgets.discard(context, it.first) }
            pending = null
        }
    }

    fun pick(info: AppWidgetProviderInfo) {
        picking = false
        val id = DeckWidgets.allocate(context)
        if (DeckWidgets.bindIfAllowed(context, id, info)) {
            configureOrAdd(id, info)
        } else {
            pending = id to info
            bind.launch(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            )
        }
    }

    if (picking) {
        WidgetPicker(onPick = ::pick, onClose = { picking = false }, modifier = modifier)
        return
    }

    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("// WIDGETS", color = Cp.Yellow, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    "[ + AJOUTER ]",
                    color = Cp.Cyan,
                    fontFamily = HudFont,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { picking = true }.padding(6.dp),
                )
            }
        }
        if (DeckWidgets.ids.isEmpty()) {
            item {
                Text(
                    "Aucun widget. Touche « + AJOUTER » pour poser l'agenda, Maps, Keep ou n'importe quel widget Android ici.",
                    color = Cp.Dim,
                    fontFamily = HudFont,
                    fontSize = 12.sp,
                )
            }
        }
        items(DeckWidgets.ids.toList(), key = { it }) { id -> HostedWidget(id) }
    }
}

@Composable
private fun HostedWidget(id: Int) {
    val context = LocalContext.current
    val info = remember(id) { DeckWidgets.manager(context).getAppWidgetInfo(id) } ?: return
    val density = LocalDensity.current
    val base = with(density) { info.minHeight.toDp() }.coerceIn(90.dp, 260.dp)
    val height = base * (1f + 0.5f * (DeckWidgets.heights[id] ?: 0))
    val label = remember(id) { info.loadLabel(context.packageManager) }

    Column(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.55f), cut = 10.dp)
            .padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "// ${label.uppercase()}",
                color = Cp.Cyan,
                fontFamily = HudFont,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            WidgetTool("▲") { DeckWidgets.move(context, id, -1) }
            WidgetTool("▼") { DeckWidgets.move(context, id, 1) }
            WidgetTool("↕") { DeckWidgets.cycleHeight(context, id) }
            WidgetTool("✕", Cp.Red) { DeckWidgets.remove(context, id) }
        }
        AndroidView(
            factory = { ctx ->
                DeckWidgets.host(ctx).createView(ctx, id, info).apply { setAppWidget(id, info) }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(height),
        )
    }
}

@Composable
private fun WidgetTool(label: String, color: androidx.compose.ui.graphics.Color = Cp.Yellow, onClick: () -> Unit) {
    Text(
        label,
        color = color,
        fontFamily = HudFont,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun WidgetPicker(onPick: (AppWidgetProviderInfo) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val providers = remember { DeckWidgets.providers(context) }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("// CHOISIR UN WIDGET", color = Cp.Yellow, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("[ X ]", color = Cp.Red, fontFamily = HudFont, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onClose).padding(6.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(providers, key = { it.second.provider.flattenToString() }) { (label, info) ->
                Text(
                    label.uppercase(),
                    color = Cp.Text,
                    fontFamily = HudFont,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.4f), cut = 6.dp)
                        .clickable { onPick(info) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}
