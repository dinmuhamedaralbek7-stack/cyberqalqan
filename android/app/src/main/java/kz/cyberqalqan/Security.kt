package kz.cyberqalqan

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.view.WindowManager
import java.io.File
import java.security.MessageDigest

/**
 * CyberQalqan қорғаныс қабаттары:
 *  1) Түпнұсқалық: қосымшаның қолтаңбасы біздің кілтпен сәйкес келе ме (жалған/өзгертілген APK-ны анықтайды)
 *  2) Екі факторлы құлып: (1) саусақ ізі / бет / телефон құлпы + (2) CyberQalqan-ның жеке PIN-коды (PBKDF2 хэші, Vault)
 *  3) Экран қорғанысы: скриншот пен экран жазуға тыйым (FLAG_SECURE)
 *  + root-талған телефон туралы ескерту
 */
object Security {
    /** cq-release.keystore сертификатының SHA-256 ізі */
    private const val CERT_SHA256 = "0E39FD06CB16C901252C1E1DE49F7C63940DE11F24BB3DAE8BBAD9255C761EA1"
    private const val KEY_LOCK = "app_lock"
    private const val UNLOCK_WINDOW_MS = 5 * 60 * 1000L
    const val REQ_CREDENTIAL = 77

    @Volatile private var unlockedUntil = 0L

    fun secureWindow(a: Activity) {
        a.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }

    // ---------- 1) Түпнұсқалық ----------
    @Suppress("DEPRECATION")
    fun isGenuine(ctx: Context): Boolean = try {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val si = info.signingInfo
            if (si == null) emptyArray() else if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else {
            ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        sigs?.any { sha256(it.toByteArray()) == CERT_SHA256 } == true
    } catch (_: Exception) { false }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02X".format(it) }

    // ---------- root ескертуі ----------
    fun isRooted(): Boolean {
        val paths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/app/Superuser.apk",
            "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su", "/data/adb/magisk")
        return paths.any { try { File(it).exists() } catch (_: Exception) { false } } ||
            (Build.TAGS?.contains("test-keys") == true)
    }

    // ---------- 2) Құлып ----------
    fun lockEnabled(ctx: Context) = ctx.getSharedPreferences(AlarmActivity.PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LOCK, true)
    fun setLock(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(AlarmActivity.PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LOCK, on).apply()

    fun deviceSecure(ctx: Context): Boolean =
        (ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isDeviceSecure == true

    fun needsUnlock(ctx: Context) = lockEnabled(ctx) && (deviceSecure(ctx) || Vault.hasPin(ctx)) && System.currentTimeMillis() > unlockedUntil
    fun markUnlocked() { unlockedUntil = System.currentTimeMillis() + UNLOCK_WINDOW_MS }
    fun relock() { unlockedUntil = 0L }

    /** Саусақ ізі / бет / PIN арқылы растау. Нәтиже onResult(true/false). */
    @Suppress("DEPRECATION")
    fun authenticate(a: Activity, onResult: (Boolean) -> Unit) {
        val title = "CyberQalqan құлпын ашу"
        val sub = "Қорғанысты тек сіз өзгерте аласыз"
        if (Build.VERSION.SDK_INT >= 29) {
            val b = BiometricPrompt.Builder(a).setTitle(title).setSubtitle(sub)
            if (Build.VERSION.SDK_INT >= 30) {
                b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            } else {
                b.setDeviceCredentialAllowed(true)
            }
            val cb = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { markUnlocked(); onResult(true) }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { onResult(false) }
            }
            try { b.build().authenticate(CancellationSignal(), a.mainExecutor, cb) } catch (_: Exception) { onResult(false) }
        } else {
            val km = a.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val i = km.createConfirmDeviceCredentialIntent(title, sub)
            if (i == null) { onResult(true); return }
            pending = onResult
            try { a.startActivityForResult(i, REQ_CREDENTIAL) } catch (_: Exception) { onResult(false) }
        }
    }

    private var pending: ((Boolean) -> Unit)? = null
    fun onCredentialResult(resultCode: Int) {
        val ok = resultCode == Activity.RESULT_OK
        if (ok) markUnlocked()
        pending?.invoke(ok); pending = null
    }
}
