package kz.cyberqalqan

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import java.util.Locale

/**
 * 📦 APK-алаяқтыққа қарсы сканер.
 * Схема: алаяқ «банктің жаңа қосымшасы / бонус / eGov жаңарту» деп APK жібереді → адам орнатады →
 * қосымша «Арнайы мүмкіндіктер» (Accessibility) немесе SMS рұқсатын сұрайды → экранды оқиды, кодтарды ұстайды,
 * ақшаны өзі аударады. Бұл сканер осындай қосымшаларды белгілері бойынша табады.
 *
 * Тек ресми Android API: PackageManager (манифесттегі <queries> арқылы көрінетін қосымшалар),
 * AccessibilityManager, DevicePolicyManager. QUERY_ALL_PACKAGES керек емес.
 */
object AppScanner {

    data class Finding(
        val pkg: String, val label: String, val score: Int, val reasons: List<String>,
        val sideloaded: Boolean, val a11yOn: Boolean, val admin: Boolean, val remote: Boolean, val installed: Long
    ) { val danger get() = score >= 60 }

    /** Ресми дүкендер — осылардан орнатылған қосымшаға сенім жоғары */
    private val TRUSTED = setOf(
        "com.android.vending", "com.google.android.feedback", "com.sec.android.app.samsungapps",
        "com.huawei.appmarket", "com.xiaomi.mipicks", "com.xiaomi.market", "com.xiaomi.discover",
        "ru.vk.store", "com.heytap.market", "com.oppo.market", "com.bbk.appstore", "com.vivo.appstore",
        "com.amazon.venezia", "com.transsion.phonemaster.appstore"
    )

    /** Қашықтан басқару қосымшалары — заңды, бірақ «банк қызметкері» орнатуды сұраса, бұл алаяқтық */
    val REMOTE = mapOf(
        "com.anydesk.anydeskandroid" to "AnyDesk",
        "com.carriez.flutter_hbb" to "RustDesk",
        "com.teamviewer.quicksupport.market" to "TeamViewer QuickSupport",
        "com.teamviewer.host.market" to "TeamViewer Host",
        "com.teamviewer.teamviewer.market.mobile" to "TeamViewer",
        "com.sand.airdroid" to "AirDroid",
        "com.realvnc.viewer.android" to "VNC Viewer",
    )

    /** Қосымша атауында болса — банк/мемлекет/«жаңарту» атын жамылу белгісі */
    private val BAIT = listOf(
        "kaspi", "каспи", "halyk", "халык", "халық", "homebank", "bcc", "centercredit", "центркредит",
        "jusan", "freedom", "forte", "otbasy", "отбасы", "egov", "е-gov", "eгов", "егов", "enpf", "бжзқ",
        "салық", "налог", "kazpost", "казпочт", "қазпошта", "банк", "bank", "sber", "сбер",
        "госуслуг", "gosuslug", "update", "обновлен", "жаңарту", "бонус", "bonus", "выплат", "төлем",
        "доставк", "delivery", "pochta", "почта", "google play", "play market", "security", "защит", "антивирус"
    )

    private fun installer(pm: PackageManager, pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName
        else @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
    } catch (_: Exception) { null }

    private fun granted(p: PackageInfo, perm: String): Boolean {
        val req = p.requestedPermissions ?: return false
        val flags = p.requestedPermissionsFlags
        for (i in req.indices) if (req[i] == perm) {
            return flags == null || (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
        }
        return false
    }
    private fun requested(p: PackageInfo, perm: String) = p.requestedPermissions?.contains(perm) == true

    private data class Ctx(val a11yOn: Set<String>, val a11yAny: Set<String>, val admins: Set<String>, val listeners: Set<String>)

    private fun context(ctx: Context): Ctx {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val on = try { am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.mapNotNull { it.resolveInfo?.serviceInfo?.packageName }?.toSet() } catch (_: Exception) { null } ?: emptySet()
        // Қосымша: жүйелік баптаудан тікелей оқу (кейбір телефондарда тізім толығырақ)
        val onSec = try { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.mapNotNull { it.substringBefore('/').takeIf { s -> s.isNotBlank() } }?.toSet() } catch (_: Exception) { null } ?: emptySet()
        val any = try { am?.installedAccessibilityServiceList?.mapNotNull { it.resolveInfo?.serviceInfo?.packageName }?.toSet() }
            catch (_: Exception) { null } ?: emptySet()
        val adm = try { (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager)?.activeAdmins
            ?.map { it.packageName }?.toSet() } catch (_: Exception) { null } ?: emptySet()
        val nls = try { Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            ?.split(':')?.mapNotNull { it.substringBefore('/').takeIf { s -> s.isNotBlank() } }?.toSet() } catch (_: Exception) { null } ?: emptySet()
        return Ctx(on + onSec, any, adm, nls)
    }

    @Suppress("DEPRECATION")
    private fun pkgInfo(pm: PackageManager, pkg: String): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        else pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
    } catch (_: Exception) { null }

    private fun evaluate(ctx: Context, pm: PackageManager, p: PackageInfo, c: Ctx): Finding? {
        val pkg = p.packageName ?: return null
        if (pkg == ctx.packageName) return null
        val ai = p.applicationInfo ?: return null
        if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 || (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) return null

        val label = try { pm.getApplicationLabel(ai).toString() } catch (_: Exception) { pkg }
        val inst = installer(pm, pkg)
        val side = inst == null || inst !in TRUSTED
        val reasons = mutableListOf<Pair<Int, String>>()
        var s = 0
        fun add(w: Int, t: String) { s += w; reasons += w to t }

        val a11yOn = pkg in c.a11yOn
        val admin = pkg in c.admins
        val remote = REMOTE[pkg]
        if (a11yOn) add(if (side) 55 else 22, "«Арнайы мүмкіндіктер» қосулы — экранды оқи алады және сіздің орныңызға баса алады")
        else if (pkg in c.a11yAny && side) add(15, "«Арнайы мүмкіндіктер» рұқсатын сұрауға дайын")
        if (admin) add(if (side) 40 else 15, "Құрылғы әкімшісі — оны өшіру қиындатылған")
        if (side && (granted(p, "android.permission.RECEIVE_SMS") || granted(p, "android.permission.READ_SMS")))
            add(30, "SMS-тарды оқи алады — банк кодтарын ұстап алуы мүмкін")
        if (side && pkg in c.listeners) add(20, "Хабарландыруларыңызды оқиды")
        if (side && requested(p, "android.permission.SYSTEM_ALERT_WINDOW")) add(8, "Басқа қосымшалардың үстінен жалған терезе көрсете алады")
        if (side && requested(p, "android.permission.REQUEST_INSTALL_PACKAGES")) add(6, "Басқа қосымшаларды өзі орната алады")
        if (side && requested(p, "android.permission.CALL_PHONE")) add(5, "Қоңырауларды басқара алады")
        if (side) {
            val low = (label + " " + pkg).lowercase(Locale.ROOT)
            if (BAIT.any { low.contains(it) }) add(40, "«$label» — банк/мемлекет/«жаңарту» атын жамылған, бірақ ресми дүкеннен орнатылмаған")
            add(18, "Play Store-дан емес, APK файлдан орнатылған")
            if (pm.getLaunchIntentForPackage(pkg) == null) add(18, "Иконкасы жасырылған — мәзірде көрінбейді")
            if (System.currentTimeMillis() - p.firstInstallTime < 3L * 24 * 3600_000) add(8, "Жуырда орнатылған")
        }
        if (remote != null) add(35, "$remote — қашықтан басқару қосымшасы. «Банк қызметкері» орнатуды сұраса — бұл алаяқтық")

        val score = s.coerceAtMost(99)
        // Тек шынымен назар аударарлық қосымшаларды көрсетеміз
        if (score < 30 && remote == null) return null
        return Finding(pkg, label, score, reasons.sortedByDescending { it.first }.map { it.second },
            side, a11yOn, admin, remote != null, p.firstInstallTime)
    }

    /** Барлық көрінетін қосымшаларды тексеру (фондық ағында шақырыңыз) */
    @Suppress("DEPRECATION")
    fun scan(ctx: Context): List<Finding> {
        val pm = ctx.packageManager
        val c = context(ctx)
        val list = try {
            if (Build.VERSION.SDK_INT >= 33) pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            else pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        } catch (_: Exception) { emptyList() }
        // Accessibility/әкімші тізіміндегі, бірақ жалпы тізімде көрінбей қалған пакеттерді де қосамыз
        val names = (list.map { it.packageName } + c.a11yOn + c.admins).toSet()
        return names.mapNotNull { n -> (list.firstOrNull { it.packageName == n } ?: pkgInfo(pm, n))?.let { evaluate(ctx, pm, it, c) } }
            .sortedByDescending { it.score }
    }

    /** Бір қосымшаны тексеру (жаңа орнатылғанда) */
    fun check(ctx: Context, pkg: String): Finding? {
        val pm = ctx.packageManager
        val p = pkgInfo(pm, pkg) ?: return null
        return evaluate(ctx, pm, p, context(ctx))
    }

    fun installedPackages(ctx: Context): Set<String> = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getInstalledPackages(0).mapNotNull { it.packageName }.toSet()
    } catch (_: Exception) { emptySet() }
}
