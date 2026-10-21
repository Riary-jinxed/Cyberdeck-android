package dev.netrunner.launcher.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.telecom.TelecomManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.netrunner.launcher.call.Contacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Tabs = listOf("CLAVIER", "RÉCENTS", "CONTACTS")

private val CallPermissions = buildList {
    add(Manifest.permission.CALL_PHONE)
    add(Manifest.permission.READ_CONTACTS)
    add(Manifest.permission.READ_CALL_LOG)
    add(Manifest.permission.READ_PHONE_STATE)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

@Composable
fun DialerScreen(prefill: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var number by remember(prefill) { mutableStateOf(prefill) }
    var tab by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var isDialer by remember { mutableStateOf(isDefaultDialer(context)) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDialer = isDefaultDialer(context)
        refresh++
    }
    LaunchedEffect(Unit) {
        val missing = CallPermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }

    Box(
        Modifier
            .fillMaxSize()
            .cyberGrid()
            .scanlines()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .displayCutoutPadding()
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            PanelHeader(title = "HOLO-LIAISON", accent = Cp.Red, onClose = onClose)
            if (!isDialer) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "[ DÉFINIR NETRUNNER COMME APPLI TÉLÉPHONE ] ►",
                    color = Cp.Yellow,
                    fontFamily = HudFont,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .hudPanel(borderColor = Cp.Yellow, cut = 8.dp)
                        .clickable { role.launch(dialerRoleIntent(context)) }
                        .padding(12.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tabs.forEachIndexed { i, label ->
                    val selected = i == tab
                    Text(
                        label,
                        color = if (selected) Cp.Background else Cp.Cyan,
                        fontFamily = HudFont,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .weight(1f)
                            .hudPanel(borderColor = Cp.Cyan, fill = if (selected) Cp.Cyan else Cp.Panel, cut = 6.dp)
                            .clickable { tab = i }
                            .padding(vertical = 8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> KeypadTab(number, onNumber = { number = it }, onCall = { placeCall(context, number) })
                    1 -> RecentsTab(refresh) { placeCall(context, it) }
                    else -> ContactsTab(refresh) { placeCall(context, it) }
                }
            }
        }
    }
}

@Composable
private fun KeypadTab(number: String, onNumber: (String) -> Unit, onCall: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                number.ifEmpty { "_" },
                color = Cp.Yellow,
                fontFamily = HudFont,
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.weight(1f),
            )
            if (number.isNotEmpty()) {
                Text(
                    "⌫",
                    color = Cp.Red,
                    fontSize = 26.sp,
                    modifier = Modifier
                        .clickable { onNumber(number.dropLast(1)) }
                        .padding(8.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Keypad(onDigit = { onNumber(number + it) })
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .hudPanel(borderColor = Cp.Cyan, fill = Cp.Cyan.copy(alpha = 0.18f), cut = 14.dp)
                .clickable(enabled = number.isNotBlank(), onClick = onCall)
                .padding(vertical = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("☎ OUVRIR LA LIAISON", color = Cp.Cyan, fontFamily = HudFont, fontSize = 16.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun RecentsTab(refresh: Int, onCall: (String) -> Unit) {
    val context = LocalContext.current
    val recents by produceState(emptyList<dev.netrunner.launcher.call.RecentCall>(), refresh) {
        value = withContext(Dispatchers.IO) { Contacts.recents(context) }
    }
    val format = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    if (recents.isEmpty()) {
        EmptyHint("Aucun appel récent (ou accès au journal refusé).")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(recents) { call ->
            val (icon, color) = when (call.type) {
                CallLog.Calls.INCOMING_TYPE -> "↙" to Cp.Cyan
                CallLog.Calls.OUTGOING_TYPE -> "↗" to Cp.Yellow
                CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> "✕" to Cp.Red
                else -> "·" to Cp.Dim
            }
            ListRow(
                icon = icon,
                iconColor = color,
                title = call.name ?: call.number.ifBlank { "Numéro masqué" },
                subtitle = format.format(Date(call.date)) + if (call.durationSec > 0) " · ${call.durationSec / 60}:%02d".format(call.durationSec % 60) else "",
                onClick = { if (call.number.isNotBlank()) onCall(call.number) },
            )
        }
    }
}

@Composable
private fun ContactsTab(refresh: Int, onCall: (String) -> Unit) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf("") }
    val contacts by produceState(emptyList<dev.netrunner.launcher.call.PhoneContact>(), refresh) {
        value = withContext(Dispatchers.IO) { Contacts.all(context) }
    }
    Column {
        androidx.compose.foundation.text.BasicTextField(
            value = filter,
            onValueChange = { filter = it },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Cp.Yellow),
            modifier = Modifier
                .fillMaxWidth()
                .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.6f), cut = 6.dp)
                .padding(10.dp),
            decorationBox = { inner ->
                if (filter.isEmpty()) Text("> rechercher un contact", color = Cp.Dim, fontFamily = HudFont, fontSize = 14.sp)
                inner()
            },
        )
        Spacer(Modifier.height(8.dp))
        val shown = contacts.filter { filter.isBlank() || it.name.contains(filter, true) || it.number.contains(filter) }
        if (shown.isEmpty()) {
            EmptyHint("Aucun contact (ou accès aux contacts refusé).")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown) { c ->
                    ListRow(
                        icon = c.name.firstOrNull()?.uppercase() ?: "?",
                        iconColor = Cp.Yellow,
                        title = c.name,
                        subtitle = c.number,
                        onClick = { onCall(c.number) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ListRow(icon: String, iconColor: androidx.compose.ui.graphics.Color, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.35f), cut = 6.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, color = iconColor, fontFamily = HudFont, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp)
        }
        Text("☎", color = Cp.Cyan, fontSize = 18.sp)
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, color = Cp.Dim, fontFamily = HudFont, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
}

private fun isDefaultDialer(context: Context): Boolean {
    val roles = context.getSystemService(RoleManager::class.java)
    return roles?.isRoleHeld(RoleManager.ROLE_DIALER) == true
}

private fun dialerRoleIntent(context: Context): Intent {
    val roles = context.getSystemService(RoleManager::class.java)
    return if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_DIALER)) {
        roles.createRequestRoleIntent(RoleManager.ROLE_DIALER)
    } else {
        Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
            .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
    }
}

/** Places the call through Telecom; our InCallService then shows the holocall screen. */
private fun placeCall(context: Context, number: String) {
    if (number.isBlank()) return
    val uri = Uri.fromParts("tel", number, null)
    val canCall = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
    if (canCall) {
        val telecom = context.getSystemService(TelecomManager::class.java)
        try {
            telecom.placeCall(uri, Bundle())
            return
        } catch (_: SecurityException) {
        }
    }
    // Without the permission, hand the number to the system dialer.
    context.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
