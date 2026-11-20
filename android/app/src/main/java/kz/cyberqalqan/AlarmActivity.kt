package kz.cyberqalqan

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 🚨 Дабыл экраны: қауіпті сілтеме табылғанда қызыл экран, діріл, дыбыс және 102 батырмасы. */
class AlarmActivity : Activity() {

    companion object {
        const val POLICE = "102"
        const val PREFS = "cq_prefs"
        const val KEY_SOUND = "alarm_sound"

        fun dialPolice(ctx: Context) {
            // Android жедел нөмірлерге (102, 112) бөгде қосымшаның өзі қоңырау шалуына тыйым салады,
            // сондықтан нөмір теріліп тұрған экран ашылады — тек жасыл батырманы басу қалады.
            val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$POLICE")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { ctx.startActivity(i) } catch (_: Exception) {}
        }

        fun soundOn(ctx: Context) = ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SOUND, true)

        fun intent(ctx: Context, url: String, score: Int, sms: String, sender: String, brand: String?) =
            Intent(ctx, AlarmActivity::class.java).apply {
                putExtra(CheckActivity.EXTRA_URL, url)
                putExtra(CheckActivity.EXTRA_SMS, sms.take(2000))
                putExtra(CheckActivity.EXTRA_SENDER, sender.take(60))
                putExtra("score", score)
                putExtra("brand", brand ?: "")
                putExtra("nid", (if (url.isNotEmpty()) url else sms.take(2000)).hashCode())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
    }

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private val main = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Security.secureWindow(this)
        // Құлыпталған экранның үстінен көрсету және экранды жағу
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
            (getSystemService(KEYGUARD_SERVICE) as? KeyguardManager)?.requestDismissKeyguard(this, null)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.parseColor("#B0102A")
        window.navigationBarColor = Color.parseColor("#3A0410")
        build()
        cancelNotification()
        startAlarm()
    }

    private fun cancelNotification() {
        val nid = intent.getIntExtra("nid", 0)
        if (nid != 0) try { getSystemService(android.app.NotificationManager::class.java).cancel(nid) } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); build(); cancelNotification(); startAlarm()
    }

    private fun build() {
        val url = intent.getStringExtra(CheckActivity.EXTRA_URL).orEmpty()
        val score = intent.getIntExtra("score", 90)
        val sender = intent.getStringExtra(CheckActivity.EXTRA_SENDER).orEmpty()
        val brand = intent.getStringExtra("brand").orEmpty()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(46), dp(22), dp(30))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#B0102A"), Color.parseColor("#5A0718"), Color.parseColor("#1A0208")))
        }
        fun t(s: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
            text = s; textSize = size; setTextColor(color); gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD; setLineSpacing(0f, 1.12f)
        }
        fun gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
        fun btn(label: String, bg: Int, fg: Int, onClick: () -> Unit) = TextView(this).apply {
            text = label; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(fg); setPadding(dp(16), dp(18), dp(16), dp(18))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(bg) }
            isClickable = true; setOnClickListener { stopAlarm(); onClick() }
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        val siren = t("🚨", 74f)
        siren.startAnimation(AlphaAnimation(1f, 0.25f).apply {
            duration = 420; repeatMode = Animation.REVERSE; repeatCount = Animation.INFINITE
        })
        root.addView(siren)
        root.addView(t("БАСПАҢЫЗ!", 42f, bold = true))
        root.addView(gap(6))
        root.addView(t("Бұл хабарламадағы сілтеме $score% қауіпті", 19f, Color.parseColor("#FFD6DD"), true))
        root.addView(gap(14))
        val info = buildString {
            if (brand.isNotEmpty()) append("Алаяқ «$brand» атын жамылған.\n")
            append("Сілтемені ашсаңыз, ақшаңыз бен деректеріңіз ұрлануы мүмкін.")
            if (sender.isNotEmpty()) append("\nЖіберуші: $sender")
        }
        root.addView(t(info, 15.5f, Color.parseColor("#FFE9ED")))
        if (url.isNotEmpty()) {
            root.addView(gap(12))
            root.addView(t(url.take(120), 13f, Color.parseColor("#FFB3C0")).apply {
                typeface = Typeface.MONOSPACE
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.parseColor("#40000000")) }
            })
        }
        root.addView(gap(16))
        root.addView(t("Тыныштаныңыз — сіз әлі ештеңе жоғалтқан жоқсыз. Хабарламаны өшіріп, жіберушіні бұғаттаңыз.", 14f, Color.parseColor("#FFCCD5")))
        root.addView(gap(26))

        root.addView(btn("📞  $POLICE — Полицияға қоңырау", Color.WHITE, Color.parseColor("#B0102A")) { dialPolice(this) })
        root.addView(gap(12))
        // ---- Отбасылық қалқан: туысқа бір батырмамен хабарлау ----
        val fam = Family.active(this)
        if (fam.isNotEmpty()) {
            root.addView(btn("👨‍👩‍👧  ТУЫСЫМА ХАБАРЛАУ", Color.parseColor("#5B8CFF"), Color.WHITE) { familyAlert(fam, url, score, brand) })
            root.addView(gap(12))
        }
        root.addView(btn("📋  Толық есепті көру", Color.parseColor("#33FFFFFF"), Color.WHITE) {
            startActivity(Intent(this, CheckActivity::class.java).apply {
                putExtra(CheckActivity.EXTRA_URL, url)
                putExtra(CheckActivity.EXTRA_SMS, intent.getStringExtra(CheckActivity.EXTRA_SMS).orEmpty())
                putExtra(CheckActivity.EXTRA_SENDER, sender)
            })
            finish()
        })
        root.addView(gap(12))
        root.addView(btn("✅  Түсіндім, баспаймын", Color.parseColor("#1AFFFFFF"), Color.parseColor("#FFD6DD")) { finish() })
        root.addView(gap(14))
        root.addView(t("Ақша аударып қойсаңыз — дереу банкке (карта артындағы нөмір) және $POLICE-ге хабарласыңыз.", 12.5f, Color.parseColor("#FFB3C0")))

        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
    }

    @Suppress("DEPRECATION")
    private fun familyAlert(list: List<Family.Contact>, url: String, score: Int, brand: String) {
        val pick = { c: Family.Contact ->
            val h = if (url.isNotEmpty()) Family.urlHash(url) else ""
            val why = Family.rateLimit(this, c, h)
            if (why != null) android.widget.Toast.makeText(this, why, android.widget.Toast.LENGTH_LONG).show()
            else {
                val body = Family.message(this, c, score, brand)
                android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Кімге: ${c.name} (${c.rel})")
                    .setMessage(body + "\n\n" + (if (c.method == "whatsapp") "WhatsApp ашылады — онда «Жіберу» басыңыз." else "SMS қолданбасы ашылады — онда «Жіберу» басыңыз."))
                    .setPositiveButton("📨 Жіберу") { _, _ ->
                        val ok = Family.compose(this, c.method, c.phone, body)
                        Family.record(this, c, score, brand, h, ok)
                        if (!ok) android.widget.Toast.makeText(this, "Қолданба ашылмады. Туысыңызға қоңырау шалыңыз.", android.widget.Toast.LENGTH_LONG).show()
                    }
                    .setNegativeButton("Болдырмау", null).show()
            }
        }
        if (list.size == 1) pick(list[0])
        else android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Кімге хабарлаймыз?")
            .setItems(list.map { "${it.name} (${it.rel})" }.toTypedArray()) { _, i -> pick(list[i]) }
            .show()
    }

    private fun startAlarm() {
        stopAlarm()
        // Қатты діріл — адам өзі өшіргенше қайталана береді
        val pattern = longArrayOf(0, 900, 300, 900, 300, 900, 700)
        vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
        try {
            if (Build.VERSION.SDK_INT >= 26) vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            else vibrator?.vibrate(pattern, 0)
        } catch (_: Exception) {}

        if (soundOn(this)) {
            try {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                    if (Build.VERSION.SDK_INT >= 21) audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    if (Build.VERSION.SDK_INT >= 28) isLooping = true
                    play()
                }
                // Ескі Android-тарда дыбыс біткен сайын қайта қосамыз — адам өзі өшіргенше
                keepRinging()
            } catch (_: Exception) {}
        }
    }

    private fun keepRinging() {
        main.postDelayed({
            val r = ringtone ?: return@postDelayed
            try { if (!r.isPlaying) r.play() } catch (_: Exception) {}
            keepRinging()
        }, 1500)
    }

    private fun stopAlarm() {
        try { ringtone?.stop() } catch (_: Exception) {}
        ringtone = null
        try { vibrator?.cancel() } catch (_: Exception) {}
        main.removeCallbacksAndMessages(null)
    }

    // Артқа батырмасы дабылды өшірмейді — тек экрандағы батырмалар өшіреді
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* әдейі бос */ }
    override fun onDestroy() { stopAlarm(); super.onDestroy() }
}
