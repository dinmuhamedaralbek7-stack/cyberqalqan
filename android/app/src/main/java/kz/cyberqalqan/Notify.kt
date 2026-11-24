package kz.cyberqalqan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build

object Notify {
    const val CH_SERVICE = "cq_service"
    const val CH_ALERT = "cq_alert"
    const val CH_ALARM = "cq_alarm_v1"
    const val CH_SCAN = "cq_scan"
    val ALARM_VIBRATION = longArrayOf(0, 700, 250, 700, 250, 700)

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Қалқымалы батырма", NotificationManager.IMPORTANCE_MIN)
        )
        val alert = NotificationChannel(CH_ALERT, "Қауіпті сілтеме ескертулері", NotificationManager.IMPORTANCE_HIGH)
        alert.enableVibration(true)
        alert.lightColor = Color.RED
        nm.createNotificationChannel(alert)
        val alarm = NotificationChannel(CH_ALARM, "🚨 Дабыл: өте қауіпті сілтеме", NotificationManager.IMPORTANCE_HIGH)
        alarm.enableVibration(true)
        alarm.vibrationPattern = ALARM_VIBRATION
        alarm.setBypassDnd(true)
        alarm.setSound(
            android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM),
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        )
        nm.createNotificationChannel(alarm)
        nm.createNotificationChannel(
            NotificationChannel(CH_SCAN, "Хабарламадағы сілтемелер (Тексеру батырмасы)", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun scanIntent(ctx: Context, url: String, sms: String, sender: String, nid: Int) =
        Intent(ctx, ScanActivity::class.java).apply {
            putExtra(CheckActivity.EXTRA_URL, url)
            putExtra(CheckActivity.EXTRA_SMS, sms.take(2000))
            putExtra(CheckActivity.EXTRA_SENDER, sender.take(60))
            putExtra("nid", nid)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }

    /** Хабарламада сілтеме келгенде — оның жанында «🛡️ Тексеру» батырмасы бар хабарландыру. */
    @Suppress("DEPRECATION")
    fun linkFound(ctx: Context, url: String, sms: String, sender: String) {
        ensureChannels(ctx)
        val nid = ("scan|" + url).hashCode()
        val pi = PendingIntent.getActivity(ctx, nid, scanIntent(ctx, url, sms, sender, nid), piFlags())
        val who = if (sender.isNotBlank()) sender.take(40) else "Хабарлама"
        val n = builder(ctx, CH_SCAN)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("🛡️ $who: сілтеме бар")
            .setContentText(url.take(80) + " — басудан бұрын тексеріңіз")
            .setStyle(Notification.BigTextStyle().bigText(sms.take(300) + "\n\n👇 «Тексеру» батырмасын басыңыз — сілтеме ашылмайды"))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .setColor(Color.parseColor("#3CF2C3"))
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_shield), "🛡️ ТЕКСЕРУ", pi).build())
            .build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(nid, n) } catch (_: SecurityException) {}
    }

    /** Тексеру нәтижесі: қауіпсіз немесе күдікті. */
    @Suppress("DEPRECATION")
    fun result(ctx: Context, r: RiskEngine.Result, sms: String, sender: String) {
        ensureChannels(ctx)
        val safe = r.score < 40
        val nid = ("res|" + (r.url ?: "")).hashCode()
        val open = Intent(ctx, CheckActivity::class.java).apply {
            putExtra(CheckActivity.EXTRA_URL, r.url ?: "")
            putExtra(CheckActivity.EXTRA_SMS, sms.take(2000))
            putExtra(CheckActivity.EXTRA_SENDER, sender.take(60))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(ctx, nid, open, piFlags())
        val title = if (safe) "✅ Қауіпті емес · ${r.score}%" else "⚠️ Күдікті сілтеме · ${r.score}% — баспаңыз"
        val body = if (safe) "${r.url}\nАйқын қауіп белгілері табылмады. Бәрібір код, пароль, CVV енгізбеңіз."
                   else "${r.url}\n" + r.reasons.take(3).joinToString("\n") { "• $it" }
        val n = builder(ctx, CH_SCAN)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(r.url ?: "")
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .setColor(Color.parseColor(if (safe) "#2EE59D" else "#FFB547"))
            .build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(nid, n) } catch (_: SecurityException) {}
    }

    @Suppress("DEPRECATION")
    fun builder(ctx: Context, channel: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, channel) else Notification.Builder(ctx)

    private fun piFlags() = PendingIntent.FLAG_UPDATE_CURRENT or
        (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)

    @Suppress("DEPRECATION")
    fun appAlert(ctx: Context, f: AppScanner.Finding, why: String) {
        ensureChannels(ctx)
        val id = ("app|" + f.pkg).hashCode()
        val alarmPi = PendingIntent.getActivity(ctx, id, AlarmActivity.appIntent(ctx, f, why), piFlags())
        val delPi = PendingIntent.getActivity(ctx, id + 1,
            Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:${f.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), piFlags())
        val title = if (why == "a11y") "🚨 «${f.label}» телефонды басқару рұқсатын алды!" else "🚨 Қауіпті қосымша орнатылды: «${f.label}»"
        val n = builder(ctx, CH_ALARM)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(f.reasons.firstOrNull() ?: "APK-алаяқтық белгілері")
            .setStyle(Notification.BigTextStyle().bigText(f.reasons.take(3).joinToString("\n• ", "• ") + "\nДереу өшіріңіз. Банк деректерін енгізсеңіз — банкке қоңырау шалыңыз."))
            .setContentIntent(alarmPi)
            .setColor(Color.parseColor("#FF4D6D"))
            .setPriority(Notification.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVibrate(ALARM_VIBRATION)
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_shield), "🗑 Өшіру", delPi).build())
            .setFullScreenIntent(alarmPi, true)
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        try { ctx.getSystemService(NotificationManager::class.java).notify(id, n) } catch (_: SecurityException) {}
    }

    @Suppress("DEPRECATION")
    fun alert(ctx: Context, r: RiskEngine.Result, sms: String, sender: String, alarm: Boolean = false) {
        ensureChannels(ctx)
        val open = Intent(ctx, CheckActivity::class.java).apply {
            putExtra(CheckActivity.EXTRA_URL, r.url ?: "")
            putExtra(CheckActivity.EXTRA_SMS, sms.take(2000))
            putExtra(CheckActivity.EXTRA_SENDER, sender.take(60))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val id = (r.url?.takeIf { it.isNotEmpty() } ?: sms.take(2000)).hashCode()
        val pi = PendingIntent.getActivity(ctx, id, open, piFlags())
        val title = "${r.score}% · ${RiskEngine.verdict(r.score)}"
        val body = buildString {
            append(r.url ?: "")
            if (r.brand != null) append("\n«${r.brand}» атын жамылған болуы мүмкін")
            r.reasons.take(2).forEach { append("\n• ").append(it) }
            append("\nСілтемені баспаңыз. Толығырақ үшін түртіңіз.")
        }
        val dialPi = PendingIntent.getActivity(ctx, 102,
            Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:${AlarmActivity.POLICE}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), piFlags())
        val alarmPi = PendingIntent.getActivity(ctx, id + 1,
            AlarmActivity.intent(ctx, r.url ?: "", r.score, sms, sender, r.brand), piFlags())
        val n = builder(ctx, if (alarm) CH_ALARM else CH_ALERT)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(if (alarm) "🚨 БАСПАҢЫЗ! Сілтеме ${r.score}% қауіпті" else "CyberQalqan: $title")
            .setContentText(r.url ?: "Күдікті хабарлама")
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setColor(if (r.score >= 70) Color.parseColor("#FF4D6D") else Color.parseColor("#FFB547"))
            .setPriority(if (alarm) Notification.PRIORITY_MAX else Notification.PRIORITY_HIGH)
            .setCategory(if (alarm) Notification.CATEGORY_ALARM else Notification.CATEGORY_MESSAGE)
            .setVibrate(ALARM_VIBRATION)
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_shield),
                "📞 ${AlarmActivity.POLICE} — Полиция", dialPi).build())
            .apply { if (alarm) setFullScreenIntent(alarmPi, true) }
            .build()
        // Дабыл дыбысы адам хабарландыруды ашқанша/өшіргенше қайталана береді
        if (alarm) n.flags = n.flags or Notification.FLAG_INSISTENT
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (_: SecurityException) { /* хабарландыруға рұқсат жоқ */ }
    }
}
