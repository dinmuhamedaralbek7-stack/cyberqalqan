package kz.cyberqalqan

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * Шифрланған қойма:
 *  - AES-256-GCM кілті Android Keystore ішінде жасалады (кілтті телефоннан шығарып алу мүмкін емес,
 *    мүмкін болса аппараттық TEE/StrongBox ішінде сақталады).
 *  - Соңғы сілтеме, тексеру тарихы осы кілтпен шифрланып сақталады.
 *  - Қосымша PIN-коды: тек PBKDF2-HMAC-SHA256 (120 000 итерация + тұз) хэші сақталады, PIN өзі сақталмайды.
 *  - 5 қате PIN → 5 минутқа бұғат (brute-force қорғанысы).
 */
object Vault {
    private const val ALIAS = "cq_vault_v1"
    private const val FILE = "cq_vault"
    private const val ITER = 120_000

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    private fun enc(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun dec(s: String): String? = try {
        val (iv, data) = s.split(":", limit = 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(c.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (_: Exception) { null }

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun put(ctx: Context, k: String, v: String?) {
        val e = sp(ctx).edit()
        if (v == null) e.remove(k) else try { e.putString(k, enc(v)) } catch (_: Exception) { e.remove(k) }
        e.apply()
    }
    fun get(ctx: Context, k: String): String? = sp(ctx).getString(k, null)?.let { dec(it) }
    /** Тексеру тарихы мен соңғы сілтемені өшіру (PIN сақталады) */
    fun wipe(ctx: Context) { sp(ctx).edit().remove("last").remove("hist").apply() }

    // ---------- Соңғы сілтеме (SMS хабарландыруынан) ----------
    fun saveLast(ctx: Context, url: String, sms: String, sender: String) {
        put(ctx, "last", org.json.JSONObject().put("u", url.take(2048)).put("s", sms.take(1500))
            .put("f", sender.take(80)).put("t", System.currentTimeMillis()).toString())
    }
    /** Соңғы maxAgeMs ішінде келген сілтеме: (url, sms, sender) */
    fun last(ctx: Context, maxAgeMs: Long = 30 * 60_000L): Triple<String, String, String>? = try {
        val j = org.json.JSONObject(get(ctx, "last") ?: return null)
        if (System.currentTimeMillis() - j.getLong("t") > maxAgeMs) null
        else Triple(j.getString("u"), j.optString("s"), j.optString("f"))
    } catch (_: Exception) { null }

    // ---------- PIN (екінші фактор) ----------
    private fun pbkdf2(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, ITER, 256)).encoded

    fun hasPin(ctx: Context) = sp(ctx).contains("pin")

    fun setPin(ctx: Context, pin: String?) {
        if (pin == null) { sp(ctx).edit().remove("pin").remove("fails").remove("lockUntil").apply(); return }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val h = pbkdf2(pin, salt)
        sp(ctx).edit().putString("pin", Base64.encodeToString(salt, Base64.NO_WRAP) + ":" + Base64.encodeToString(h, Base64.NO_WRAP))
            .putInt("fails", 0).apply()
    }

    /** Қалған бұғат уақыты (мс), 0 болса — енгізуге болады */
    fun lockedFor(ctx: Context): Long = maxOf(0L, sp(ctx).getLong("lockUntil", 0) - System.currentTimeMillis())

    fun checkPin(ctx: Context, pin: String): Boolean {
        if (lockedFor(ctx) > 0) return false
        val stored = sp(ctx).getString("pin", null) ?: return true
        val (s, h) = stored.split(":", limit = 2)
        val ok = MessageDigest.isEqual(pbkdf2(pin, Base64.decode(s, Base64.NO_WRAP)), Base64.decode(h, Base64.NO_WRAP))
        val e = sp(ctx).edit()
        if (ok) e.putInt("fails", 0)
        else {
            val f = sp(ctx).getInt("fails", 0) + 1
            e.putInt("fails", f)
            if (f >= 5) e.putInt("fails", 0).putLong("lockUntil", System.currentTimeMillis() + 5 * 60_000L)
        }
        e.apply()
        return ok
    }
}
