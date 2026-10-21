package dev.netrunner.launcher.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.launcher.MainActivity
import dev.netrunner.launcher.system.Ice
import dev.netrunner.launcher.system.UiSound

/** Netwatch-style intrusion screen shown when an app's ICE limit for the day is used up. */
class IceActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PACKAGE = "dev.netrunner.launcher.ICE_PACKAGE"
        const val EXTRA_USED = "dev.netrunner.launcher.ICE_USED"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return finish()
        val usedMin = intent.getLongExtra(EXTRA_USED, 0L) / 60_000
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg)
        UiSound.play(this, UiSound.Cue.Glitch)
        // Back = jack out, never back into the app.
        onBackPressedDispatcher.addCallback(this) { jackOut() }
        setContent {
            NetrunnerTheme {
                IceScreen(
                    app = label,
                    usedMin = usedMin,
                    limitMin = Ice.limits[pkg] ?: 0,
                    onJackOut = ::jackOut,
                    onExtra = {
                        Ice.grantExtra(this, pkg)
                        finish()
                    },
                )
            }
        }
    }

    private fun jackOut() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}

@Composable
private fun IceScreen(app: String, usedMin: Long, limitMin: Int, onJackOut: () -> Unit, onExtra: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .crtGlitch()
            .scanlines()
            .systemBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlitchText(
            "ICE DÉCLENCHÉ",
            style = TextStyle(fontFamily = HudFont, fontSize = 34.sp, fontWeight = FontWeight.Bold),
            color = Cp.Red,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "> NETWATCH // ACCÈS BLOQUÉ",
            color = Cp.Red.copy(alpha = 0.8f),
            fontFamily = HudFont,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(28.dp))
        Text(app.uppercase(), color = Cp.Yellow, fontFamily = HudFont, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            "$usedMin min aujourd'hui · limite $limitMin min",
            color = Cp.Text,
            fontFamily = HudFont,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(40.dp))
        Text(
            "[ DÉCONNEXION ]",
            color = Cp.Background,
            fontFamily = HudFont,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .hudPanel(borderColor = Cp.Red, fill = Cp.Red)
                .clickable(onClick = onJackOut)
                .padding(vertical = 14.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "+${Ice.EXTRA_MINUTES} MIN",
            color = Cp.Cyan,
            fontFamily = HudFont,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.6f))
                .clickable(onClick = onExtra)
                .padding(vertical = 12.dp),
        )
    }
}
