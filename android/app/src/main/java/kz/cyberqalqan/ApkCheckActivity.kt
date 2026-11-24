package kz.cyberqalqan

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.security.MessageDigest

/**
 * APK файлды ОРНАТПАЙ тексеру.
 * Пайдаланушы WhatsApp/Telegram/Жүктеулерден .apk файлды «CyberQalqan арқылы ашу» не «Бөлісу → CyberQalqan» жасайды.
 * Файл орындалмайды: тек манифесті оқылады (рұқсаттар, қызметтер), SHA-256 есептеліп, CyberQalqan базасынан ізделеді.
 */
class ApkCheckActivity : Activity() {

    private val BG = Color.parseColor("#05070D"); private val CARD = Color.parseColor("#0E1526")
    private val LINE = Color.parseColor("#1E2A44"); private val TXT = Color.parseColor("#E8EEFC")
    private val MUT = Color.parseColor("#8A98B8"); private val ACC = Color.parseColor("#3CF2C3")
    private val OK = Color.parseColor("#2EE59D"); private val WARN = Color.parseColor("#FFB547")
    private val BAD = Color.parseColor("#FF4D6D")
    private val PICK = 41

    private lateinit var list: LinearLayout
    private var tmp: File? = null
    private var sourceUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Security.secureWindow(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(28)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(list) })
        val uri = incoming(intent)
        if (uri != null) start(uri) else pick()
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); incoming(i)?.let { start(it) } }

    @Suppress("DEPRECATION")
    private fun incoming(i: Intent?): Uri? = when (i?.action) {
        Intent.ACTION_VIEW -> i.data
        Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)
        else -> null
    }

    private fun pick() {
        status("📦", "APK файлды таңдаңыз", "Сізге жіберілген .apk файлды таңдаңыз — CyberQalqan оны орнатпай тексереді.")
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
            }, PICK)
        } catch (_: Exception) { finish() }
    }

    @Deprecated("Activity API")
    override fun onActivityResult(req: Int, code: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(req, code, data)
        if (req == PICK) { val u = data?.data; if (code == RESULT_OK && u != null) start(u) else finish() }
    }

    private fun start(uri: Uri) {
        sourceUri = uri
        status("🔍", "Тексерілуде…", "Файл орнатылмайды және іске қосылмайды — тек ішіндегі рұқсаттар оқылады.")
        Thread {
            val r = try { analyze(uri) } catch (e: Exception) { null }
            runOnUiThread { if (!isFinishing) { if (r == null) status("⚠️", "Файлды оқу мүмкін болмады", "Бұл APK бүлінген немесе әдейі жасырылған болуы мүмкін. Оны ОРНАТПАҢЫЗ.") else show(r) } }
        }.start()
    }

    data class Result(val name: String, val label: String, val pkg: String, val ver: String, val sha: String, val size: Long,
                      val v: ApkRules.Verdict, val score: Int, val extra: List<String>, val dbReports: Int)

    @Suppress("DEPRECATION")
    private fun analyze(uri: Uri): Result? {
        val name = displayName(uri)
        val dir = File(cacheDir, "apkcheck").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val f = File(dir, "check.apk"); tmp = f
        val md = MessageDigest.getInstance("SHA-256")
        var size = 0L
        contentResolver.openInputStream(uri)?.use { inp ->
            f.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(buf); if (n < 0) break
                    md.update(buf, 0, n); out.write(buf, 0, n); size += n
                    if (size > 300L * 1024 * 1024) throw IllegalStateException("too big")
                }
            }
        } ?: return null
        val sha = md.digest().joinToString("") { "%02x".format(it) }

        val pm = packageManager
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
            (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        val p: PackageInfo = pm.getPackageArchiveInfo(f.absolutePath, flags) ?: return null
        val ai = p.applicationInfo
        ai?.sourceDir = f.absolutePath; ai?.publicSourceDir = f.absolutePath
        val label = try { ai?.loadLabel(pm)?.toString() } catch (_: Exception) { null } ?: p.packageName
        val v = ApkRules.analyze(p, label, true)

        var score = v.score
        val extra = mutableListOf<String>()
        // Түпнұсқа қосымшаның атын жамылған ба? (бірдей пакет аты, бірақ басқа қолтаңба)
        try {
            val inst = pm.getPackageInfo(p.packageName, if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
            if (sigs(inst) != sigs(p) && sigs(p).isNotEmpty()) { score += 50; extra += "Телефоныңыздағы түпнұсқа «$label» қосымшасының атын жамылған, бірақ қолтаңбасы БАСҚА — бұл жалған көшірме" }
        } catch (_: Exception) {}
        if (p.packageName == packageName) { score = 0; extra.clear() }

        // CyberQalqan базасы (Supabase): бұл файлды басқа пайдаланушылар қауіпті деп белгіледі ме?
        var db = 0
        Api.intelCheck(applicationContext, "apk_sha256", sha)?.let { db = it.optInt("reports") }
        if (db == 0) Api.intelCheck(applicationContext, "apk_package", p.packageName)?.let { if (it.optInt("reports") >= 2) db = it.optInt("reports") }
        if (db > 0) { score += if (db >= 3) 45 else 30; extra += "CyberQalqan базасы: бұл APK-ны $db пайдаланушы қауіпті деп белгілеген" }
        score = score.coerceIn(0, 99)

        Api.intelLog(applicationContext, "apk", score >= 60)
        if (score >= 60) {
            val cat = v.cats.firstOrNull()?.id ?: "other"
            Api.intelReport(applicationContext, "apk_sha256", sha, cat, score)
            Api.intelReport(applicationContext, "apk_package", p.packageName, cat, score)
        }
        return Result(name, label, p.packageName, p.versionName ?: "", sha, size, v, score, extra, db)
    }

    @Suppress("DEPRECATION")
    private fun sigs(p: PackageInfo): Set<String> = try {
        val arr = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory } else p.signatures
        arr?.map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }?.toSet().orEmpty()
    } catch (_: Exception) { emptySet() }

    private fun displayName(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) { null } ?: (uri.lastPathSegment ?: "file.apk")

    private fun show(r: Result) {
        list.removeAllViews()
        val danger = r.score >= 60; val warn = r.score >= 30
        val col = if (danger) BAD else if (warn) WARN else OK
        if (danger) try { Siren.start(applicationContext, Prefs.bool(this, Prefs.TORCH, true)) } catch (_: Exception) {}

        list.addView(text(if (danger) "🚨 ҚАУІПТІ APK!" else if (warn) "⚠️ Күдікті APK" else "✅ Айқын қауіп табылмады", 26f, col, true))
        list.addView(gap(6))
        list.addView(text("${r.label}  •  ${r.score}% қауіп", 16f, TXT, true))
        list.addView(text("Файл: ${r.name}\nПакет: ${r.pkg}" + (if (r.ver.isNotEmpty()) "  v${r.ver}" else "") + "\nКөлемі: ${r.size / 1024} КБ", 12.5f, MUT))
        list.addView(gap(14))

        if (danger) {
            box(BAD, "ОРНАТПАҢЫЗ!", "Бұл файл телефоныңызды толық басқаруға, SMS-кодтарды ұрлауға және банк қосымшасынан ақша аударуға мүмкіндік береді. " +
                "Оны жіберген адам «банк», «жеткізу», «eGov» не таныс атынан жазса да — бұл алаяқтық.")
        }
        if (r.v.cats.isNotEmpty()) {
            list.addView(text("Қауіп түрі", 14f, MUT, true)); list.addView(gap(6))
            r.v.cats.forEach { c -> box(col, c.title, c.why) }
        }
        val reasons = r.extra + r.v.reasons
        if (reasons.isNotEmpty()) {
            list.addView(text("Неге?", 14f, MUT, true)); list.addView(gap(6))
            list.addView(text(reasons.joinToString("\n") { "• $it" }, 14f, TXT)); list.addView(gap(14))
        }
        if (r.v.perms.isNotEmpty()) {
            list.addView(text("Сұрайтын рұқсаттары", 14f, MUT, true)); list.addView(gap(6))
            list.addView(text(r.v.perms.joinToString("\n") { "▸ $it" }, 13.5f, TXT)); list.addView(gap(14))
        }
        list.addView(text("SHA-256: ${r.sha.take(16)}…", 11f, MUT)); list.addView(gap(16))

        if (Siren.isPlaying()) { list.addView(button("🔕 Дабылды өшіру", false) { Siren.stop(applicationContext); it.visibility = View.GONE }); list.addView(gap(10)) }
        list.addView(button("🗑 Файлды жою", true) { deleteSource() }); list.addView(gap(10))
        if (!danger) {
            list.addView(button("Сенемін — орнату жолы", false) {
                android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Орнатпас бұрын")
                    .setMessage("CyberQalqan өзі ешнәрсе орнатпайды.\n\nФайлды жіберушіні шынымен танитыныңызға сенімді болсаңыз ғана «Файлдар» қосымшасынан ашып орнатыңыз.\n\nОрнатқан соң қосымша «Арнайы мүмкіндіктерді» (Accessibility) сұраса — ЕШҚАШАН рұқсат бермеңіз. CyberQalqan мұны бақылап, бірден дабыл қағады.")
                    .setPositiveButton("Түсіндім", null).show()
            }); list.addView(gap(10))
        }
        if (!danger) {
            list.addView(button("🚩 Алаяқтық деп хабарлау", false) {
                Api.intelReport(applicationContext, "apk_sha256", r.sha, r.v.cats.firstOrNull()?.id ?: "other", 70, user = true)
                (it as TextView).text = "✓ Хабарланды. Рахмет!"; it.isEnabled = false
            }); list.addView(gap(10))
        }
        list.addView(button("Жабу", false) { finish() })
    }

    private fun deleteSource() {
        Siren.stop(applicationContext)
        val u = sourceUri
        val ok = try { u != null && (android.provider.DocumentsContract.deleteDocument(contentResolver, u) || contentResolver.delete(u, null, null) > 0) } catch (_: Exception) { false }
        android.widget.Toast.makeText(this, if (ok) "🗑 Файл жойылды" else "Файлды «Жүктеулер»/чаттан өзіңіз жойыңыз. Ең бастысы — ОРНАТПАҢЫЗ.", android.widget.Toast.LENGTH_LONG).show()
        if (ok) finish()
    }

    override fun onDestroy() {
        try { tmp?.delete() } catch (_: Exception) {}
        if (isFinishing) Siren.stop(applicationContext)
        super.onDestroy()
    }

    // ---- UI көмекшілері ----
    private fun status(emoji: String, title: String, desc: String) {
        list.removeAllViews()
        list.addView(text(emoji, 48f, TXT).apply { gravity = Gravity.CENTER })
        list.addView(gap(10)); list.addView(text(title, 22f, TXT, true).apply { gravity = Gravity.CENTER })
        list.addView(gap(8)); list.addView(text(desc, 14f, MUT).apply { gravity = Gravity.CENTER })
    }

    private fun box(color: Int, title: String, desc: String) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(CARD); setStroke(dp(1), color) }
        }
        c.addView(text(title, 15f, color, true)); c.addView(text(desc, 13.5f, TXT))
        list.addView(c); list.addView(gap(10))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD; setLineSpacing(0f, 1.15f)
    }
    private fun button(label: String, primary: Boolean, onClick: (View) -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(if (primary) Color.parseColor("#03140F") else TXT)
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(if (primary) ACC else Color.parseColor("#0A1020")); if (!primary) setStroke(dp(1), LINE) }
        setPadding(dp(14), dp(14), dp(14), dp(14)); isClickable = true
        setOnClickListener { onClick(it) }
    }
}
