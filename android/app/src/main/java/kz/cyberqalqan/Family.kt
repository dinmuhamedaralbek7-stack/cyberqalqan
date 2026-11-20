package kz.cyberqalqan

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Отбасылық қалқан — нативті бөлігі (дабыл экраны үшін).
 * Деректер Vault ішінде ("family" кілті, AES-256-GCM). Пішімі веб-нұсқамен (report.html) бірдей.
 * Туысқа хабарлама ТЕК пайдаланушы растағаннан кейін SMS/WhatsApp қолданбасы арқылы жіберіледі.
 * Хабарламада сілтеме, SMS-код, карта нөмірі, SMS мәтіні болмайды.
 */
object Family {
    data class Contact(val id: String, val name: String, val rel: String, val phone: String, val method: String, val lang: String)

    private val PHONE = Regex("^\\+[1-9]\\d{7,14}$")

    fun load(ctx: Context): JSONObject = try { JSONObject(Vault.get(ctx, "family") ?: "{}") } catch (_: Exception) { JSONObject() }

    fun save(ctx: Context, raw: String): Boolean {
        if (raw.length > 32_000) return false
        return try { JSONObject(raw); Vault.put(ctx, "family", raw); true } catch (_: Exception) { false }
    }

    fun enabled(j: JSONObject) = j.optBoolean("enabled", true)

    fun active(ctx: Context): List<Contact> {
        val j = load(ctx); if (!enabled(j)) return emptyList()
        val arr = j.optJSONArray("contacts") ?: return emptyList()
        val out = mutableListOf<Contact>()
        for (i in 0 until minOf(arr.length(), 3)) {
            val c = arr.optJSONObject(i) ?: continue
            val ph = c.optString("phone")
            if (c.optString("status") != "active" || !PHONE.matches(ph)) continue
            out += Contact(c.optString("id"), c.optString("name").take(40), c.optString("rel").take(30), ph,
                if (c.optString("method") == "whatsapp") "whatsapp" else "sms", if (c.optString("lang") == "ru") "ru" else "kk")
        }
        return out
    }

    fun pendingCount(ctx: Context): Int {
        val arr = load(ctx).optJSONArray("contacts") ?: return 0
        var n = 0; for (i in 0 until arr.length()) if (arr.optJSONObject(i)?.optString("status") == "pending") n++
        return n
    }

    /** Веб-нұсқадағы famHex("u|"+url, 8) — бірдей хэш (сілтеменің өзі сақталмайды) */
    fun urlHash(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(("u|$url").toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    fun message(ctx: Context, c: Contact, score: Int, brand: String): String {
        val j = load(ctx)
        val me = j.optString("myName").take(40)
        val incl = j.optBoolean("includeBrand", true) && brand.isNotBlank()
        return if (c.lang == "ru") {
            val who = if (me.isNotBlank()) "Вашему близкому ($me)" else "Вашему близкому"
            val what = if (incl) " от имени «$brand»" else ""
            "🛡 Предупреждение CyberQalqan!\n$who пришло подозрительное сообщение$what. CyberQalqan обнаружил признаки мошенничества (оценка риска: $score%).\n" +
                "Свяжитесь с близким и помогите проверить его безопасность.\nСсылки и коды в это сообщение намеренно не включены."
        } else {
            val who = if (me.isNotBlank()) "Сіздің жақыныңызға ($me)" else "Сіздің жақыныңызға"
            val what = if (incl) " «$brand» атынан" else ""
            "🛡 CyberQalqan ескертуі!\n$who$what күмәнді хабарлама келді. CyberQalqan алаяқтық белгілерін анықтады (қауіп бағасы: $score%).\n" +
                "Жақыныңызға хабарласып, қауіпсіздігін тексеруге көмектесіңіз.\nСілтеме мен кодтар бұл хабарламаға әдейі қосылмаған."
        }
    }

    /** null — жіберуге болады; әйтпесе себебі (қазақша) */
    fun rateLimit(ctx: Context, c: Contact, h: String): String? {
        val arr = load(ctx).optJSONArray("alerts") ?: return null
        val now = System.currentTimeMillis(); var day = 0
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val s = a.optString("s"); val t = a.optLong("t")
            if (s == "test" || s == "failed") continue
            if (now - t < 86_400_000) day++
            if (a.optString("cid") != c.id) continue
            if (now - t < 120_000) return "Жақында ғана ескерту жібердіңіз. 2 минуттан кейін қайталаңыз."
            if (h.isNotEmpty() && a.optString("h") == h && now - t < 1_800_000) return "Бұл қауіп туралы туысыңызға 30 минут ішінде хабарладыңыз."
        }
        return if (day >= 10) "Бүгінгі ескертулер шегі (10) толды." else null
    }

    fun record(ctx: Context, c: Contact, score: Int, brand: String, h: String, ok: Boolean) {
        val j = load(ctx)
        val old = j.optJSONArray("alerts") ?: JSONArray()
        val id = ByteArray(6).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val arr = JSONArray().put(JSONObject().put("id", id).put("t", System.currentTimeMillis()).put("cid", c.id)
            .put("p", score).put("b", brand.take(60)).put("h", h).put("s", if (ok) "opened" else "failed"))
        for (i in 0 until minOf(old.length(), 29)) arr.put(old.get(i))
        j.put("alerts", arr)
        Vault.put(ctx, "family", j.toString())
    }

    /** SMS немесе WhatsApp қолданбасын дайын мәтінмен ашады. Жіберуді пайдаланушы өзі басады. */
    fun compose(ctx: Context, method: String, phone: String, body: String): Boolean {
        if (!PHONE.matches(phone) || body.length > 1500) return false
        val nt = Intent.FLAG_ACTIVITY_NEW_TASK
        return try {
            if (method == "whatsapp") {
                val view = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + phone.filter { it.isDigit() } + "?text=" + Uri.encode(body)))
                for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
                    try { ctx.startActivity(Intent(view).setPackage(pkg).addFlags(nt)); return true } catch (_: ActivityNotFoundException) {}
                }
                // WhatsApp жоқ — браузерлер (CyberQalqan-ның өзінсіз)
                ctx.startActivity(Intent.createChooser(view, "WhatsApp")
                    .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(ctx, CheckActivity::class.java))).addFlags(nt))
                true
            } else {
                ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")).putExtra("sms_body", body).addFlags(nt))
                true
            }
        } catch (_: Exception) { false }
    }

    fun siteBase(ctx: Context): String = try {
        val h = ctx.assets.open("api_base.txt").bufferedReader().use { it.readText() }.trim().removePrefix("https://").removeSuffix("/")
        if (Regex("^[a-z0-9.-]+$").matches(h) && h.isNotEmpty()) "https://$h" else ""
    } catch (_: Exception) { "" }
}
