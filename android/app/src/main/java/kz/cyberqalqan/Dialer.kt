package kz.cyberqalqan

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/** Жақын адамға бір басумен қоңырау: CALL_PHONE рұқсаты болса — нөмір өзі терілеп, бірден қоңырау шалынады. */
object Dialer {
    const val REQ = 7701
    private var pending: String? = null

    private fun clean(p: String) = p.filter { it.isDigit() || it == '+' }.take(16)

    fun call(a: Activity, phone: String) {
        val p = clean(phone); if (p.length < 5) return
        if (Build.VERSION.SDK_INT < 23 || a.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            try { a.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$p"))); return } catch (_: Exception) {}
            dial(a, p)
        } else { pending = p; a.requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), REQ) }
    }

    fun dial(a: Activity, p: String) { try { a.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${clean(p)}"))) } catch (_: Exception) {} }

    /** Activity.onRequestPermissionsResult ішінен шақырыңыз */
    fun onResult(a: Activity, code: Int, grants: IntArray) {
        if (code != REQ) return
        val p = pending ?: return; pending = null
        if (grants.firstOrNull() == PackageManager.PERMISSION_GRANTED) call(a, p) else dial(a, p)
    }
}
