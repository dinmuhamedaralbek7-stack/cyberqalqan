package kz.cyberqalqan

import java.util.Locale

/**
 * Офлайн эвристикалық талдау. Сілтеме ЕШҚАШАН ашылмайды — тек мәтіні талданады.
 * Толық есеп (assets/report.html) осы ережелердің кеңейтілген нұсқасын қолданады.
 */
object RiskEngine {

    data class Result(val score: Int, val reasons: List<String>, val url: String?, val brand: String?)

    private class Brand(val keys: List<String>, val name: String, val official: List<String>)

    private val BRANDS = listOf(
        Brand(listOf("kaspi"), "Kaspi.kz", listOf("kaspi.kz")),
        Brand(listOf("halyk", "homebank", "onlinebank"), "Halyk Bank", listOf("halykbank.kz", "homebank.kz", "halyk.kz")),
        Brand(listOf("egov", "e-gov", "gov-kz", "govkz"), "eGov", listOf("egov.kz", "gov.kz")),
        Brand(listOf("jusan"), "Jusan Bank", listOf("jusan.kz")),
        Brand(listOf("freedom", "ffin"), "Freedom Bank", listOf("ffin.kz", "freedombank.kz", "bankffin.kz", "freedom.kz")),
        Brand(listOf("bcc", "centercredit"), "Bank CenterCredit", listOf("bcc.kz")),
        Brand(listOf("forte"), "ForteBank", listOf("forte.kz", "fortebank.com")),
        Brand(listOf("otbasy", "hcsbk"), "Otbasy Bank", listOf("hcsbk.kz", "otbasybank.kz")),
        Brand(listOf("kazpost", "kazpochta", "qazpost"), "Қазпошта", listOf("post.kz")),
        Brand(listOf("enpf"), "БЖЗҚ", listOf("enpf.kz")),
        Brand(listOf("olx"), "OLX", listOf("olx.kz", "olx.uz", "olx.ua")),
        Brand(listOf("krisha"), "Krisha.kz", listOf("krisha.kz")),
        Brand(listOf("kolesa"), "Kolesa.kz", listOf("kolesa.kz")),
        Brand(listOf("wildberries"), "Wildberries", listOf("wildberries.ru", "wildberries.kz", "wb.ru")),
        Brand(listOf("ozon"), "Ozon", listOf("ozon.ru", "ozon.kz")),
        Brand(listOf("telegram", "tgram"), "Telegram", listOf("telegram.org", "t.me", "telegram.me")),
        Brand(listOf("whatsapp"), "WhatsApp", listOf("whatsapp.com", "wa.me", "whatsapp.net")),
        Brand(listOf("instagram"), "Instagram", listOf("instagram.com")),
        Brand(listOf("sber"), "Сбербанк", listOf("sberbank.ru", "sberbank.kz", "sber.ru")),
        Brand(listOf("tinkoff", "tbank"), "Т-Банк", listOf("tinkoff.ru", "tbank.ru")),
        Brand(listOf("gosuslugi"), "Госуслуги", listOf("gosuslugi.ru")),
        Brand(listOf("google", "gmail"), "Google", listOf("google.com", "google.kz", "gmail.com", "youtube.com", "youtu.be")),
        Brand(listOf("apple", "icloud"), "Apple", listOf("apple.com", "icloud.com")),
        Brand(listOf("paypal"), "PayPal", listOf("paypal.com")),
    )
    private val SAFE = listOf(
        "wikipedia.org", "github.com", "microsoft.com", "yandex.kz", "yandex.ru", "2gis.kz",
        "mail.ru", "vk.com", "facebook.com", "tiktok.com"
    )
    private val SHORTENERS = setOf(
        "bit.ly", "tinyurl.com", "clck.ru", "cutt.ly", "t.ly", "is.gd", "goo.su", "u.to", "rb.gy",
        "shorturl.at", "tiny.cc", "ow.ly", "vk.cc", "bitly.com", "s.id", "v.gd", "qps.ru", "surl.li",
        "tinyurl.ru", "rebrand.ly", "bl.ink"
    )
    private val BAD_TLD = setOf(
        "xyz", "top", "icu", "click", "live", "shop", "online", "site", "store", "buzz", "cfd", "sbs",
        "rest", "monster", "cyou", "tk", "ml", "ga", "cf", "gq", "work", "support", "vip", "win", "loan",
        "pw", "cam", "quest", "bond", "lol", "mom", "fun", "space", "website", "link", "club", "pro",
        "info", "biz", "asia", "today", "digital", "cloud", "host", "help", "center"
    )
    private val KEYWORDS = listOf(
        "login", "signin", "verify", "secure", "update", "account", "bonus", "prize", "gift", "promo",
        "confirm", "auth", "wallet", "card", "pay", "oplata", "vyplata", "kompens", "priz", "lottery",
        "akcia", "aktsiya", "bank", "dostavka", "delivery", "track", "otp", "cvv", "password", "parol"
    )

    private class SmsRule(val re: Regex, val weight: Int, val title: String)

    private val SMS_RULES = listOf(
        SmsRule(Regex("срочн|немедлен|в течени[ие]\\s*\\d|24\\s*(час|сағ)|шұғыл|тез арада|дереу|соңғы мүмкіндік|последн|истека|мерзімі өт"), 10, "шұғылдық қысымы"),
        SmsRule(Regex("блок|заблок|бұғат|арест|штраф|айыппұл|долг|қарыз|взлом|бұзыл|подозрит|күдікті операция|списан"), 10, "қорқыту"),
        SmsRule(Regex("выигр|приз|ұтыс|ұтып|бонус|подарок|сыйлық|компенсац|өтемақы|выплат|кэшбэк|акци|розыгр|бесплатн|тегін|миллион"), 10, "сыйлық/ақша уәдесі"),
        SmsRule(Regex("банк|bank|полиц|кнб|ұқк|прокур|egov|салық|налог|суд|сот|минист|госуслуг|служба безопасн"), 8, "ұйым атын жамылу"),
        SmsRule(Regex("код|пароль|cvv|cvc|растау|подтверд|верифик|данные карт|номер карт|иин|жсн"), 14, "құпия деректерді сұрау"),
        SmsRule(Regex("мама|папа|апа|әке|туыс|көмектес|помоги|авари|ауруха|больниц|беда"), 8, "эмоцияға әсер ету"),
        SmsRule(Regex("скача|установ|орнат|жүкте|apk|приложени|қосымша"), 12, "қосымша орнатуға мәжбүрлеу"),
    )

    private val URL_RE = Regex(
        "(https?://[^\\s\"'<>]+|(?:www\\.)?[a-z0-9\\-]+(?:\\.[a-z0-9\\-]+)*\\.[a-z]{2,24}(?:/[^\\s\"'<>]*)?)",
        RegexOption.IGNORE_CASE
    )

    /** Мәтіннен сілтемелерді табу. */
    fun findUrls(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return URL_RE.findAll(text)
            .map { it.value.trimEnd('.', ',', ';', '!', '?', ')', '»', '"', '\'') }
            .filter { it.contains('.') && it.length >= 4 && !it.matches(Regex("^\\d+(\\.\\d+)+$")) }
            .distinct()
            .toList()
    }

    private fun hostOf(raw: String): String {
        var s = raw.trim()
        s = s.substringAfter("://", s)
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfterLast('@')
        if (!s.startsWith("[")) s = s.substringBefore(':')
        return s.lowercase(Locale.ROOT)
    }

    private fun regDomain(host: String): String {
        val p = host.split('.')
        if (p.size <= 2) return host
        val last2 = p.takeLast(2).joinToString(".")
        val two = setOf("com.kz", "gov.kz", "org.kz", "edu.kz", "co.uk", "com.ru", "com.ua", "org.ru")
        return if (last2 in two) p.takeLast(3).joinToString(".") else last2
    }

    private fun under(host: String, dom: String) = host == dom || host.endsWith(".$dom")

    private fun deleet(s: String) = s.replace('0', 'o').replace('1', 'l').replace('3', 'e')
        .replace('4', 'a').replace('5', 's').replace('7', 't').replace("rn", "m").replace("vv", "w")

    private fun lev(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        return d[a.length][b.length]
    }

    fun analyze(rawUrl: String?, sms: String?, sender: String?): Result {
        var score = 0
        val reasons = mutableListOf<Pair<Int, String>>()
        fun add(w: Int, t: String) { score += w; if (w > 0) reasons += w to t }
        var brand: String? = null
        var official = false
        val url = rawUrl?.trim().orEmpty()

        if (url.isNotEmpty()) {
            val lower = url.lowercase(Locale.ROOT)
            if (lower.startsWith("javascript:") || lower.startsWith("data:") || lower.startsWith("file:")) {
                add(90, "Браузерде код орындайтын сілтеме")
            } else {
                val host = hostOf(url)
                val reg = regDomain(host)
                val label = reg.substringBefore('.')
                val tld = host.substringAfterLast('.')
                val path = lower.substringAfter(host, "")

                BRANDS.firstOrNull { b -> b.official.any { under(host, it) } }?.let { brand = it.name; official = true }
                if (!official && SAFE.any { under(host, it) }) official = true

                if (lower.startsWith("http://")) add(10, "Шифрланбаған байланыс (http)")
                if (Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)) add(30, "Домен орнына IP-мекенжай")
                if (host.contains("xn--")) add(28, "Punycode — жасырын әріптер")
                if (host.any { it.code > 127 }) add(25, "Доменде басқа алфавит әріптері")
                if (url.substringAfter("://", url).substringBefore('/').contains('@')) add(30, "Сілтемеде «@» белгісі")
                if (SHORTENERS.any { under(host, it) }) add(22, "Қысқартылған сілтеме — нағыз мекенжай жасырын")
                if (!official && tld in BAD_TLD) add(18, "«.$tld» — фишингте жиі қолданылатын аймақ")

                if (!official) {
                    val hay = deleet(host)
                    val hit = BRANDS.firstOrNull { b -> b.keys.any { hay.contains(it) || host.contains(it) } }
                    if (hit != null) {
                        brand = hit.name
                        add(42, "«${hit.name}» атын жамылған жалған домен")
                    } else {
                        val parts = deleet(label).split('-', '_')
                        loop@ for (b in BRANDS) for (d in b.official) {
                            val ol = d.substringBefore('.')
                            if (ol.length < 4) continue
                            for (p in parts) {
                                if (p != ol && p.length >= 4 && lev(p, ol) <= (if (ol.length > 6) 2 else 1)) {
                                    brand = b.name
                                    add(40, "«$p» — «$ol» атауына ұқсатылған жалған домен")
                                    break@loop
                                }
                            }
                        }
                    }
                } else {
                    score -= 30
                }

                val dots = host.count { it == '.' }
                if (dots >= 4) add(12, "Тым көп субдомен")
                if (label.count { it == '-' } >= 2) add(10, "Атауда көп сызықша")
                if (url.length > 110) add(6, "Тым ұзын сілтеме")
                if (Regex("\\d{5,}").containsMatchIn(label)) add(8, "Доменде кездейсоқ сандар")

                val ext = Regex("\\.(apk|xapk|apks|exe|scr|bat|msi|jar|zip|rar)(\\?|#|$)").find(path)?.groupValues?.get(1)
                if (ext != null) add(if (ext.startsWith("apk") || ext.startsWith("xapk")) 50 else 40, "Файл жүктеу (.$ext) — ең қауіпті схема")
                else if (Regex("/apk\\b|download|skachat|скачать").containsMatchIn(path)) add(20, "Қосымша жүктеуге апаруы мүмкін")

                if (!official) {
                    val hits = KEYWORDS.filter { (host + path).contains(it) }
                    if (hits.isNotEmpty()) add(minOf(hits.size * 6, 22), "Күдікті сөздер: ${hits.take(4).joinToString(", ")}")
                }
            }
        }

        val text = sms?.lowercase(Locale.ROOT).orEmpty()
        if (text.isNotBlank()) {
            for (r in SMS_RULES) if (r.re.containsMatchIn(text)) add(r.weight, "Хабарламада: ${r.title}")
        }

        val snd = sender?.trim().orEmpty()
        if (snd.isNotEmpty()) {
            var d = snd.filter { it.isDigit() }
            if (d.length == 11 && d.startsWith("8")) d = "7" + d.substring(1)
            if (d.length >= 10) {
                when {
                    d.length == 11 && d.startsWith("79") -> add(10, "Ресей нөмірінен жіберілген")
                    d.length == 11 && d.startsWith("77") -> if (brand != null) add(12, "Ұйым атынан қарапайым ұялы нөмірден жазылған")
                    !d.startsWith("7") -> add(14, "Шетелдік нөмірден жіберілген")
                }
            }
        }

        val res = score.coerceIn(if (official) 2 else 0, 99)
        return Result(res, reasons.sortedByDescending { it.first }.map { it.second }, url.ifEmpty { null }, brand)
    }

    fun verdict(score: Int): String = when {
        score >= 70 -> "🚨 Өте қауіпті — баспаңыз!"
        score >= 40 -> "⚠️ Күдікті сілтеме"
        score >= 20 -> "🟡 Абай болыңыз"
        else -> "✅ Қауіпсіз сияқты"
    }
}
