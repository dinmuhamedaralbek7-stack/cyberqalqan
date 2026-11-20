package kz.cyberqalqan

import android.app.Activity
import android.app.NotificationManager
import android.os.Bundle
import android.widget.Toast

/**
 * Көрінбейтін экран: хабарландырудағы «🛡️ Тексеру» батырмасы басылғанда іске қосылады.
 * Адамды ешбір бетке апармайды — сілтемені өзі тексеріп, нәтижесін бірден айтады.
 */
class ScanActivity : Activity() {

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(CheckActivity.EXTRA_URL).orEmpty()
        val sms = intent.getStringExtra(CheckActivity.EXTRA_SMS).orEmpty()
        val sender = intent.getStringExtra(CheckActivity.EXTRA_SENDER).orEmpty()
        val nid = intent.getIntExtra("nid", 0)
        if (nid != 0) try { getSystemService(NotificationManager::class.java).cancel(nid) } catch (_: Exception) {}

        Toast.makeText(this, "🔎 CyberQalqan тексеруде…", Toast.LENGTH_SHORT).show()
        Thread {
            // Интернет болса — домен жасы, рейтинг, фишинг базалары, ChatGPT; болмаса — офлайн баға
            val o = Checker.run(applicationContext, url, sms, sender)
            runOnUiThread { finishWith(o.r, url, sms, sender, o.trust) }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun finishWith(r: RiskEngine.Result, url: String, sms: String, sender: String, t: org.json.JSONObject?) {
        val ctx = applicationContext  // экран жабылып қалса да хабарлама жетеді
        BubbleService.setBadge(r.score)
        val age = Checker.ageText(t)
        val ageTxt = if (age.isEmpty()) "" else " · домен жасы: $age"
        when {
            r.score >= Prefs.threshold(ctx) -> ctx.startActivity(AlarmActivity.intent(ctx, url, r.score, sms, sender, r.brand))
            r.score >= 40 -> {
                Notify.result(ctx, r, sms, sender)
                Toast.makeText(ctx, "⚠️ Күдікті сілтеме (${r.score}%)$ageTxt", Toast.LENGTH_LONG).show()
            }
            else -> {
                Notify.result(ctx, r, sms, sender)
                Toast.makeText(ctx, "✅ Қауіпті емес (${r.score}%)$ageTxt", Toast.LENGTH_LONG).show()
            }
        }
        if (!isFinishing) { finish(); overridePendingTransition(0, 0) }
    }
}
