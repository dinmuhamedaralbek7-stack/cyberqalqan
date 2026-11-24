package kz.cyberqalqan

import android.content.pm.PackageInfo
import java.util.Locale

/**
 * APK қауіп санаттары (банк трояндарының белгілі әрекеттері бойынша):
 *  fake_bank           — Жалған банк/мемлекет қосымшасы
 *  sms_stealer         — SMS-код ұрлаушы
 *  credential_phishing — Логин/құпиясөз ұрлау (жалған кіру формасы)
 *  rat                 — Қашықтан басқару трояны (Accessibility)
 *  spyware             — Шпиондық бағдарлама
 *  overlay             — Банк экранының үстіне жалған терезе
 *  crypto_scam         — Криптовалюта алаяқтығы
 *  remote_access       — Қашықтан басқару құралы (AnyDesk, RustDesk…)
 * Тек манифест деректері талданады (файл орындалмайды).
 */
object ApkRules {
    data class Cat(val id: String, val title: String, val why: String)
    data class Verdict(val score: Int, val cats: List<Cat>, val reasons: List<String>, val perms: List<String>, val a11y: Boolean, val admin: Boolean, val sms: Boolean)

    private val BAIT = listOf(
        "kaspi", "каспи", "halyk", "халык", "халық", "homebank", "bcc", "centercredit", "центркредит", "jusan", "freedom",
        "forte", "otbasy", "отбасы", "egov", "егов", "enpf", "бжзқ", "салық", "налог", "kazpost", "казпочт", "қазпошта",
        "банк", "bank", "sber", "сбер", "госуслуг", "gosuslug", "выплат", "төлем", "бонус", "bonus", "штраф", "айыппұл",
        "доставк", "delivery", "посылк", "сәлемдеме", "pochta", "почта", "update", "обновлен", "жаңарту", "фото", "photo", "видео"
    )
    private val CRYPTO = listOf("crypto", "крипт", "usdt", "binance", "bitcoin", "btc", "wallet", "кошел", "майнинг", "mining", "инвест", "invest", "trading", "forex", "трейд")
    private val SPY = mapOf(
        "android.permission.READ_CONTACTS" to "контактілер", "android.permission.READ_CALL_LOG" to "қоңыраулар тізімі",
        "android.permission.RECORD_AUDIO" to "микрофон", "android.permission.ACCESS_FINE_LOCATION" to "дәл орналасу",
        "android.permission.CAMERA" to "камера", "android.permission.READ_PHONE_STATE" to "телефон деректері",
        "android.permission.READ_MEDIA_IMAGES" to "фотолар", "android.permission.READ_EXTERNAL_STORAGE" to "файлдар"
    )
    /** Адамға түсінікті рұқсат атаулары */
    val HUMAN = mapOf(
        "android.permission.RECEIVE_SMS" to "SMS қабылдау (кодтарды ұстау)", "android.permission.READ_SMS" to "SMS оқу",
        "android.permission.SEND_SMS" to "SMS жіберу", "android.permission.SYSTEM_ALERT_WINDOW" to "Басқа қосымшалардың үстінен терезе",
        "android.permission.REQUEST_INSTALL_PACKAGES" to "Басқа қосымшаларды орнату", "android.permission.CALL_PHONE" to "Қоңырау шалу",
        "android.permission.QUERY_ALL_PACKAGES" to "Барлық қосымшаларды көру (банк қосымшасын іздеу)",
        "android.permission.PACKAGE_USAGE_STATS" to "Қай қосымша ашық екенін бақылау",
        "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" to "Экранды түсіру/тарату",
        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" to "Фонда үздіксіз жұмыс",
        "android.permission.RECEIVE_BOOT_COMPLETED" to "Телефон қосылғанда өзі іске қосылу",
    ) + SPY.mapValues { it.value.replaceFirstChar { c -> c.uppercase() } }

    fun analyze(p: PackageInfo, label: String, offStore: Boolean): Verdict {
        val perms = p.requestedPermissions?.toSet().orEmpty()
        val has = { x: String -> x in perms }
        val a11y = p.services?.any { it.permission == "android.permission.BIND_ACCESSIBILITY_SERVICE" } == true
        val nls = p.services?.any { it.permission == "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" } == true
        val admin = p.receivers?.any { it.permission == "android.permission.BIND_DEVICE_ADMIN" } == true
        val sms = has("android.permission.RECEIVE_SMS") || has("android.permission.READ_SMS")
        val overlay = has("android.permission.SYSTEM_ALERT_WINDOW")
        val low = (label + " " + p.packageName).lowercase(Locale.ROOT)
        val bait = BAIT.firstOrNull { low.contains(it) }
        val crypto = CRYPTO.any { low.contains(it) }
        val spyN = SPY.keys.count(has)
        val remote = AppScanner.REMOTE[p.packageName]

        var s = 0
        val reasons = mutableListOf<String>()
        val cats = mutableListOf<Cat>()
        fun add(w: Int, t: String) { s += w; reasons += t }

        if (offStore) add(15, "Ресми дүкеннен тыс тараған APK файл")
        if (bait != null) {
            add(40, "Атауы «$label» — банк/мемлекет/«$bait» атын жамылады. Ресми қосымшалар тек Play Store-да")
            cats += Cat("fake_bank", "Жалған банк / мемлекет қосымшасы", "Танымал ұйымның атын жамылып, сенім алуға тырысады")
        }
        if (sms) {
            add(35, "SMS-тарды оқиды/ұстайды — банк растау кодтарын алаяққа жібере алады")
            cats += Cat("sms_stealer", "SMS-код ұрлаушы", "Банктен келетін бір реттік кодтарды ұстап алады")
        }
        if (a11y) {
            add(40, "«Арнайы мүмкіндіктер» (Accessibility) қызметі бар — экранды оқып, сіздің орныңызға баса алады")
            cats += Cat("rat", "Қашықтан басқару трояны", "Рұқсат берсеңіз, алаяқ банк қосымшасын өзі басқарып, ақша аударады")
        }
        if (admin) add(15, "Құрылғы әкімшісі болуға тырысады — оны өшіру қиын болады")
        if (overlay && (a11y || has("android.permission.QUERY_ALL_PACKAGES") || has("android.permission.PACKAGE_USAGE_STATS"))) {
            add(15, "Банк қосымшасы ашылғанда үстінен жалған кіру терезесін шығара алады")
            cats += Cat("overlay", "Экран үстіне жалған терезе", "Банк қосымшасының дизайнын көшіріп, логин мен PIN-ді ұрлайды")
        } else if (overlay) add(6, "Басқа қосымшалардың үстінен терезе көрсете алады")
        if (bait != null && !sms && !a11y && spyN <= 1)
            cats += Cat("credential_phishing", "Логин/құпиясөз ұрлау", "Жалған кіру формасы арқылы банк деректерін сұрайды")
        if (spyN >= 3 || nls) {
            add(if (nls) 20 else 15, if (nls) "Хабарландыруларыңызды оқиды" else "Көп жеке деректерге рұқсат сұрайды: " + SPY.filterKeys(has).values.joinToString(", "))
            cats += Cat("spyware", "Шпиондық бағдарлама", "Контакт, хабарлама, микрофон сияқты жеке деректерді жинайды")
        }
        if (crypto) {
            add(30, "Криптовалюта/инвестиция атауы — «тез табыс» алаяқтығының жиі белгісі")
            cats += Cat("crypto_scam", "Криптовалюта алаяқтығы", "Жалған биржа/әмиян: салған ақшаңызды шығара алмайсыз")
        }
        if (remote != null) {
            add(35, "$remote — қашықтан басқару құралы. «Банк қызметкері» орнатуды сұраса — бұл алаяқтық")
            cats += Cat("remote_access", "Қашықтан басқару құралы", "Экраныңызды алаяққа толық көрсетеді")
        }
        if (has("android.permission.REQUEST_INSTALL_PACKAGES") && offStore) add(6, "Басқа қосымшаларды өзі орната алады")

        val human = perms.mapNotNull { HUMAN[it] }.distinct()
        return Verdict(s.coerceIn(0, 99), cats, reasons, human, a11y, admin, sms)
    }
}
