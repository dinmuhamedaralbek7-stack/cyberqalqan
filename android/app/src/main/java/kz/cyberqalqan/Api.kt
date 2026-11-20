package kz.cyberqalqan

import android.content.Context
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/**
 * CyberQalqan серверінен доменнің сенім деңгейін сұрау.
 * Құпиялылық: серверге ТЕК домен атауы жіберіледі (SMS мәтіні, нөмір, сілтеменің толық жолы жіберілмейді).
 * Тек HTTPS; жауап өлшемі шектеулі; уақыт шектеулі.
 */
object Api {
    private val DOMAIN_RE = Regex("^(?=.{3,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")

    private fun base(ctx: Context): String = try {
        ctx.assets.open("api_base.txt").bufferedReader().use { it.readText() }.trim()
            .removePrefix("https://").removeSuffix("/")
    } catch (_: Exception) { "" }

    fun trust(ctx: Context, rawDomain: String): JSONObject? {
        var d = rawDomain.trim().lowercase().removeSuffix(".")
        if (d.startsWith("www.")) d = d.substring(4)
        if (!DOMAIN_RE.matches(d)) return null
        val b = base(ctx)
        if (b.isEmpty() || !Regex("^[a-z0-9.-]+$").matches(b)) return null
        var c: HttpsURLConnection? = null
        return try {
            c = URL("https://$b/api/trust?d=" + URLEncoder.encode(d, "UTF-8")).openConnection() as HttpsURLConnection
            c.connectTimeout = 6000; c.readTimeout = 12000
            c.instanceFollowRedirects = false
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode != 200) return null
            val txt = c.inputStream.bufferedReader().use { r ->
                val buf = CharArray(64 * 1024); val n = r.read(buf); if (n <= 0) "" else String(buf, 0, n)
            }
            val j = JSONObject(txt)
            if (j.optBoolean("ok")) j else null
        } catch (_: Exception) { null } finally { c?.disconnect() }
    }

    /** Серверде ChatGPT кілті орнатылған ба? Нәтиже Prefs.AI_SRV ішінде кэштеледі. */
    fun refreshAiEnabled(ctx: Context) {
        val b = base(ctx); if (b.isEmpty() || !Regex("^[a-z0-9.-]+$").matches(b)) return
        Thread {
            var c: HttpsURLConnection? = null
            try {
                c = URL("https://$b/api/ai").openConnection() as HttpsURLConnection
                c.connectTimeout = 6000; c.readTimeout = 8000; c.instanceFollowRedirects = false
                if (c.responseCode == 200) {
                    val j = JSONObject(c.inputStream.bufferedReader().use { it.readText().take(2000) })
                    Prefs.set(ctx, Prefs.AI_SRV, j.optBoolean("enabled"))
                }
            } catch (_: Exception) {} finally { c?.disconnect() }
        }.start()
    }

    /**
     * ChatGPT талдауы (сервер арқылы; API кілті қосымшада ЖОҚ).
     * Әдепкі бойынша тек сілтеме, домен және белгілер жіберіледі; SMS мәтіні — тек баптауда рұқсат етілсе.
     */
    fun ai(ctx: Context, body: JSONObject): JSONObject? {
        if (!Prefs.aiActive(ctx)) return null
        if (!Prefs.bool(ctx, Prefs.AI_SMS, false)) body.put("sms", "")
        val b = base(ctx)
        if (b.isEmpty() || !Regex("^[a-z0-9.-]+$").matches(b)) return null
        var c: HttpsURLConnection? = null
        return try {
            c = URL("https://$b/api/ai").openConnection() as HttpsURLConnection
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 6000; c.readTimeout = 25000; c.instanceFollowRedirects = false
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().take(8000).toByteArray(Charsets.UTF_8)) }
            if (c.responseCode != 200) return null
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText().take(16000) })
            if (j.optBoolean("ok")) j else null
        } catch (_: Exception) { null } finally { c?.disconnect() }
    }

    fun hostOf(url: String): String =
        url.substringAfter("://", url).substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':').lowercase()

    /** Эвристикалық бағаны сенім деңгейімен біріктіру (сайттағы ережемен бірдей). */
    fun combine(score: Int, t: JSONObject?): Int {
        if (t == null) return score
        val trust = t.optInt("score", 50)
        val listed = t.optJSONObject("facts")?.optBoolean("listed") == true
        return when {
            listed -> maxOf(score, 97)
            trust < 50 -> minOf(99, Math.round(score + (50 - trust) * 0.6f))
            trust >= 70 && score < 40 -> maxOf(2, score - 10)
            else -> score
        }
    }
}
