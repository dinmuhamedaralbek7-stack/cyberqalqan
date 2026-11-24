package kz.cyberqalqan

import android.app.Notification
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Келген SMS / WhatsApp / Telegram хабарландыруларындағы сілтемелерді автоматты тексереді.
 * Сілтеме ашылмайды; хабарлама мәтіні құрылғыдан шықпайды.
 */
class SmsWatcherService : NotificationListenerService() {

    companion object { const val KEY_AUTO = "auto_alarm" }

    // Бір хабарламаның хабарландыруы бірнеше рет жаңарады — 30 секунд ішінде қайталамаймыз
    private val seen = LinkedHashMap<String, Long>()
    private var guard: InstallGuard.Handle? = null

    override fun onListenerConnected() { super.onListenerConnected(); if (guard == null) guard = InstallGuard.register(this) }
    override fun onListenerDisconnected() { InstallGuard.unregister(this, guard); guard = null; super.onListenerDisconnected() }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return
        if (!Prefs.consent(this)) return  // пайдаланушы ашық келісім бермесе — ештеңе оқымаймыз
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras ?: return

        val sender = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val parts = mutableListOf<String>()
        ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.let { parts += it.toString() }
        ex.getCharSequence(Notification.EXTRA_TEXT)?.let { parts += it.toString() }
        ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { parts += it.toString() }
        val text = parts.distinct().joinToString("\n")
        if (text.isBlank()) return

        // WhatsApp/Telegram арқылы APK ФАЙЛ келді (сілтемесіз) — бұл ең қауіпті схема, бірден дабыл
        val apkName = RiskEngine.apkInText(text)?.takeIf { !it.lowercase().contains("cyberqalqan") }
        if (apkName != null) {
            val key = sbn.packageName + "|apk|" + apkName
            val now = System.currentTimeMillis()
            if ((seen[key] ?: 0L) < now - 60_000) {
                seen[key] = now
                val u0 = RiskEngine.findUrls(text).firstOrNull()
                val r = RiskEngine.analyze(u0, text, sender)
                Notify.alert(this, r, text, sender, alarm = true)
                if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) {
                    try { startActivity(AlarmActivity.intent(this, u0 ?: "", r.score, text, sender, r.brand)) } catch (_: Exception) {}
                }
            }
            return
        }

        val urls = RiskEngine.findUrls(text)
        if (urls.isEmpty()) return

        var worst: RiskEngine.Result? = null
        for (u in urls.take(5)) {
            val key = sbn.packageName + "|" + u
            val now = System.currentTimeMillis()
            val prev = seen[key]
            if (prev != null && now - prev < 30_000) continue
            seen[key] = now
            val r = RiskEngine.analyze(u, text, sender)
            if (worst == null || r.score > worst.score) worst = r
        }
        while (seen.size > 300) seen.remove(seen.keys.first())

        val w = worst ?: return
        // Домалақ батырма үшін: соңғы сілтеме шифрланып сақталады → батырманы басқанда көшірусіз тексеріледі
        Vault.saveLast(this, w.url ?: "", text, sender)
        BubbleService.newLink(w.score)
        val auto = getSharedPreferences(AlarmActivity.PREFS, MODE_PRIVATE).getBoolean(KEY_AUTO, false)
        if (auto && w.score >= Prefs.threshold(this)) {
            // Автоматты режим: бірден дабыл
            Notify.alert(this, w, text, sender, alarm = true)
            if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) {
                try { startActivity(AlarmActivity.intent(this, w.url ?: "", w.score, text, sender, w.brand)) } catch (_: Exception) {}
            }
        } else {
            // Батырма режимі: хабарламаның жанында «🛡️ Тексеру» батырмасы шығады
            Notify.linkFound(this, w.url ?: "", text, sender)
        }
    }
}
