package dev.netrunner.files.net

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Saved NAS shares. Passwords are encrypted with a key kept in the Android Keystore. */
object SmbStore {
    private const val KEY = "netrunner-smb"
    private fun prefs(context: Context) = context.getSharedPreferences("smb", Context.MODE_PRIVATE)

    fun servers(context: Context): List<Smb.Server> = runCatching {
        val array = JSONArray(prefs(context).getString("servers", "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Smb.Server(
                id = o.getString("id"), name = o.optString("name"), host = o.optString("host"), share = o.optString("share"),
                user = o.optString("user"), domain = o.optString("domain"),
                password = o.optString("pw").takeIf { it.isNotEmpty() }?.let { decrypt(it) }.orEmpty(),
            )
        }
    }.getOrDefault(emptyList())

    fun save(context: Context, server: Smb.Server) = write(context, servers(context).filter { it.id != server.id } + server)

    fun delete(context: Context, id: String) = write(context, servers(context).filter { it.id != id })

    private fun write(context: Context, list: List<Smb.Server>) {
        val array = JSONArray()
        list.forEach { s ->
            array.put(
                JSONObject().put("id", s.id).put("name", s.name).put("host", s.host).put("share", s.share)
                    .put("user", s.user).put("domain", s.domain).put("pw", if (s.password.isEmpty()) "" else encrypt(s.password)),
            )
        }
        prefs(context).edit().putString("servers", array.toString()).apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.iv + cipher.doFinal(text.toByteArray())
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    /** Empty when the key was lost (app data cleared): the password must be typed again. */
    private fun decrypt(text: String): String? = runCatching {
        val data = Base64.decode(text, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12)) }
        String(cipher.doFinal(data, 12, data.size - 12))
    }.getOrNull()
}
