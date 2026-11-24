package kz.cyberqalqan

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Толық тексеру: эвристика → домен сенімі → ChatGPT. Фондық ағында шақырылады. */
object Checker {
    data class Out(val r: RiskEngine.Result, val trust: JSONObject?, val ai: JSONObject?)

    fun run(ctx: Context, url: String, sms: String, sender: String): Out {
        val r0 = RiskEngine.analyze(url, sms, sender)
        val host = Api.hostOf(url)
        val t = if (host.isNotEmpty()) Api.trust(ctx, host) else null
        var score = Api.combine(r0.score, t)
        val ai = if (url.isNotBlank()) Api.ai(ctx, JSONObject()
            .put("url", url.take(500)).put("domain", host).put("sms", sms.take(1000)).put("sender", sender.take(60))
            .put("score", score).put("signals", JSONArray(r0.reasons.take(12).map { it.take(160) }))
            .put("trust", t?.let { "сенім ${it.optInt("score")}/100" } ?: "")) else null
        if (ai != null) {
            val a = ai.optInt("risk", score)
            // ИИ бағаны тек көтере алады (қауіпті сілтемені «қауіпсіз» деп жасыра алмайды)
            score = maxOf(score, (score + a) / 2)
        }
        // Анонимді статистика + қауіпті доменді қауымдастық базасына қосу (тек домен)
        Api.intelLog(ctx, "android", score >= 60)
        if (score >= 60 && host.isNotEmpty())
            Api.intelReport(ctx, "domain", host, if (r0.brand != null) "fake_bank" else "phishing", score)
        return Out(r0.copy(score = score), t, ai)
    }

    fun ageText(t: JSONObject?): String {
        val f = t?.optJSONObject("facts") ?: return ""
        if (!f.has("ageDays")) return ""
        val d = f.optInt("ageDays")
        return if (d < 365) "$d күн" else "${d / 365} жыл"
    }
}
