package dev.netrunner.launcher.system

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import dev.netrunner.launcher.notify.NotificationHub

/** One line of the diagnostic. [ok] is null when Android gives no way to check (HyperOS extras). */
data class Check(
    val title: String,
    val detail: String,
    val ok: Boolean?,
    val fix: (Context) -> Unit,
    val optional: Boolean = false,
)

/** Everything Netrunner needs from Android and HyperOS, with a one-tap fix for each. */
object Diagnostics {

    fun run(context: Context): List<Check> {
        val pkg = context.packageName
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        val roles = context.getSystemService(RoleManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val imm = context.getSystemService(InputMethodManager::class.java)

        return listOf(
            Check("Écran d'accueil", "Netrunner remplace le launcher Xiaomi", HomeRole.isDefault(context), { HomeRole.openSettings(it) }),
            Check(
                "Accès aux notifications",
                "Flux, musique, verrou cyber. Si grisé : Infos appli › ⋮ › Autoriser les paramètres restreints",
                NotificationHub.isAccessGranted(context),
                { open(it, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            ),
            Check(
                "Appli Téléphone",
                "Holo-appels entrants et sortants",
                roles?.isRoleHeld(RoleManager.ROLE_DIALER) == true,
                { open(it, Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) },
                optional = true,
            ),
            Check(
                "Afficher par-dessus les autres applis",
                "Nécessaire au verrou cyber",
                Settings.canDrawOverlays(context),
                { open(it, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$pkg"))) },
                optional = true,
            ),
            Check(
                "Notifications de Netrunner",
                "Carte d'appel entrant",
                Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
                { appDetails(it) },
            ),
            Check(
                "Contacts et journal d'appels",
                "Noms et photos pendant les appels, recherche",
                granted(Manifest.permission.READ_CONTACTS) && granted(Manifest.permission.READ_CALL_LOG),
                { appDetails(it) },
                optional = true,
            ),
            Check("Localisation", "Scanner Wi-Fi et météo", granted(Manifest.permission.ACCESS_FINE_LOCATION), { appDetails(it) }, optional = true),
            Check("Agenda", "Contrats sur l'accueil", granted(Manifest.permission.READ_CALENDAR), { appDetails(it) }, optional = true),
            Check(
                "Ne pas déranger",
                "Mode furtif silencieux et tuile NPD",
                notifications?.isNotificationPolicyAccessGranted == true,
                { open(it, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
                optional = true,
            ),
            Check(
                "Modifier les paramètres système",
                "Tuile rotation",
                Settings.System.canWrite(context),
                { open(it, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$pkg"))) },
                optional = true,
            ),
            Check(
                "Clavier Netrunner",
                "Activé dans la liste des claviers",
                imm?.enabledInputMethodList?.any { it.packageName == pkg } == true,
                { open(it, Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                optional = true,
            ),
            Check(
                "Batterie sans restriction",
                "Évite que HyperOS coupe le flux de notifications et le verrou",
                power?.isIgnoringBatteryOptimizations(pkg) == true,
                { open(it, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
            ),
            Check(
                "HyperOS : autres autorisations",
                "Active « Afficher sur l'écran de verrouillage » et « Fenêtres pop-up en arrière-plan »",
                null,
                { miuiPermissions(it) },
            ),
            Check(
                "HyperOS : démarrage automatique",
                "Relance le service de notifications après un redémarrage",
                null,
                { miuiAutostart(it) },
            ),
        )
    }

    private fun open(context: Context, intent: Intent) {
        val ok = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (!ok) appDetails(context)
    }

    private fun appDetails(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Xiaomi's own permission page (lock screen display, background pop-ups). */
    private fun miuiPermissions(context: Context) {
        val intent = Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            .putExtra("extra_pkgname", context.packageName)
        open(context, intent)
    }

    private fun miuiAutostart(context: Context) {
        val intent = Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        open(context, intent)
    }
}
