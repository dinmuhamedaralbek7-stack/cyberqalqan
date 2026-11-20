package kz.cyberqalqan

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Басты экран: қорғаныс функцияларын қосу. */
class MainActivity : Activity() {

    private val BG = Color.parseColor("#05070D")
    private val CARD = Color.parseColor("#0E1526")
    private val LINE = Color.parseColor("#1E2A44")
    private val TXT = Color.parseColor("#E8EEFC")
    private val MUT = Color.parseColor("#8A98B8")
    private val ACC = Color.parseColor("#3CF2C3")
    private val OK = Color.parseColor("#2EE59D")
    private val WARN = Color.parseColor("#FFB547")

    private val REQ_BROWSER = 91
    private var wantBubble = false
    private var authInProgress = false
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Security.secureWindow(this)
        Notify.ensureChannels(this)
        Api.refreshAiEnabled(applicationContext)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(28))
        }
        scroll.addView(list)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        if (Security.needsUnlock(this)) {
            list.visibility = View.INVISIBLE
            if (!authInProgress) {
                authInProgress = true
                val secondFactor = {
                    if (Vault.hasPin(this)) askPin("2-қадам: CyberQalqan PIN-коды") { good ->
                        authInProgress = false
                        if (good) { Security.markUnlocked(); list.visibility = View.VISIBLE; afterUnlock() } else { Security.relock(); finish() }
                    } else { authInProgress = false; list.visibility = View.VISIBLE; afterUnlock() }
                }
                if (Security.deviceSecure(this)) {
                    // 1-қадам: саусақ ізі / бет / телефон құлпы
                    Security.authenticate(this) { ok -> runOnUiThread { if (ok) secondFactor() else { authInProgress = false; Security.relock(); finish() } } }
                } else secondFactor()
            }
            return
        }
        list.visibility = View.VISIBLE
        afterUnlock()
    }

    private fun afterUnlock() {
        if (wantBubble && canOverlay()) { wantBubble = false; startBubble() }
        render()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == Security.REQ_CREDENTIAL) Security.onCredentialResult(resultCode)
        if (requestCode == REQ_BROWSER) {
            if (isDefaultBrowser()) Toast.makeText(this, "✅ Енді SMS-тегі сілтемені басқанда CyberQalqan алдымен тексереді", Toast.LENGTH_LONG).show()
            render()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** PIN енгізу терезесі. onDone(true) — дұрыс. */
    private fun askPin(title: String, onDone: (Boolean) -> Unit) {
        val wait = Vault.lockedFor(this)
        if (wait > 0) {
            Toast.makeText(this, "Тым көп қате PIN. ${wait / 60000 + 1} минуттан кейін қайталаңыз", Toast.LENGTH_LONG).show()
            onDone(false); return
        }
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN (4–8 сан)"; gravity = Gravity.CENTER; textSize = 22f
        }
        var answered = false
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title).setView(et).setCancelable(false)
            .setPositiveButton("Растау") { _, _ -> answered = true; onDone(Vault.checkPin(this, et.text.toString())) }
            .setNegativeButton("Болдырмау") { _, _ -> answered = true; onDone(false) }
            .setOnDismissListener { if (!answered) onDone(false) }
            .show()
    }

    /** Жаңа PIN орнату (екі рет енгізу) */
    private fun setupPin() {
        val et1 = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; hint = "Жаңа PIN (4–8 сан)" }
        val et2 = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; hint = "PIN-ды қайталаңыз" }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(et1); addView(et2) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("🔢 Екінші фактор: PIN").setMessage("Баптауларды ашу үшін саусақ ізінен кейін осы PIN сұралады. Оны ешкімге айтпаңыз.")
            .setView(box)
            .setPositiveButton("Сақтау") { _, _ ->
                val a = et1.text.toString(); val b = et2.text.toString()
                when {
                    !Regex("^\\d{4,8}$").matches(a) -> Toast.makeText(this, "PIN 4–8 саннан тұруы керек", Toast.LENGTH_LONG).show()
                    a != b -> Toast.makeText(this, "PIN сәйкес келмеді", Toast.LENGTH_LONG).show()
                    Regex("^(\\d)\\1+$").matches(a) || "0123456789".contains(a) || "9876543210".contains(a) ->
                        Toast.makeText(this, "Тым оңай PIN (1111, 1234…) — басқасын таңдаңыз", Toast.LENGTH_LONG).show()
                    else -> { Vault.setPin(this, a); Security.setLock(this, true); Toast.makeText(this, "✅ PIN орнатылды — 2FA қосулы", Toast.LENGTH_SHORT).show(); render() }
                }
            }
            .setNegativeButton("Болдырмау", null).show()
    }

    /** Google Play талабы: хабарландыруларды оқудан бұрын ашық түсіндіру және келісім */
    private fun askListenerConsent(then: () -> Unit) {
        if (Prefs.consent(this)) { then(); return }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("📩 Хабарландыруларға рұқсат")
            .setMessage(
                "CyberQalqan келген SMS, WhatsApp, Telegram хабарландыруларының мәтінін оқып, ішіндегі СІЛТЕМЕНІ тексереді.\n\n" +
                "• Хабарлама мәтіні телефоннан шықпайды.\n" +
                "• Серверге тек сілтеменің домені жіберіледі (домен жасы мен фишинг базаларын тексеру үшін).\n" +
                "• ChatGPT талдауы қосулы болса — сілтеме мен белгілер жіберіледі; SMS мәтіні тек баптауда өзіңіз рұқсат етсеңіз.\n" +
                "• Соңғы сілтеме телефонда шифрланып 30 минут сақталады.\n\n" +
                "Кез келген уақытта өшіре аласыз. Келісесіз бе?")
            .setPositiveButton("Келісемін") { _, _ -> Prefs.set(this, Prefs.CONSENT, true); then() }
            .setNegativeButton("Жоқ", null).show()
    }

    private fun showPrivacy() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("🔐 Құпиялылық")
            .setMessage(
                "• Хабарлама мәтіні тек телефонда өңделеді.\n" +
                "• Серверге: сілтеменің домені (сенім деңгейі үшін).\n" +
                "• ChatGPT (қосулы болса): сілтеме, домен, белгілер. SMS мәтіні — тек «SMS мәтінін ИИ-ге жіберу» қосулы болса.\n" +
                "• Тарих пен PIN хэші Android Keystore кілтімен шифрланған.\n" +
                "• Жарнама, трекер, деректер сату жоқ.\n\n" +
                "Толық нұсқасы: cyberqalqan.vercel.app/privacy.html")
            .setPositiveButton("Түсінікті", null).show()
    }
    private fun canOverlay() = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
    private fun listenerOn(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true

    /** Lite нұсқада хабарландыру тыңдаушысы манифестте жоқ (Play Protect бұғаттамауы үшін) */
    private fun hasListener(): Boolean = try {
        packageManager.getServiceInfo(android.content.ComponentName(this, SmsWatcherService::class.java), 0); true
    } catch (_: Exception) { false }

    /** Android 10+: жүйелік терезе арқылы «әдепкі браузер» рөлін бір батырмамен сұрау */
    private fun requestBrowserRole() {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val rm = getSystemService(android.app.role.RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER) &&
                    !rm.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER)) {
                    startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_BROWSER), REQ_BROWSER)
                    return
                }
            } catch (_: Exception) {}
        }
        Toast.makeText(this, "«Браузер» бөлімінен CyberQalqan-ды таңдаңыз", Toast.LENGTH_LONG).show()
        try { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
        catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun isDefaultBrowser(): Boolean {
        if (Build.VERSION.SDK_INT >= 29) try {
            val rm = getSystemService(android.app.role.RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER))
                return rm.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER)
        } catch (_: Exception) {}
        return isDefaultBrowserLegacy()
    }

    private fun isDefaultBrowserLegacy(): Boolean = try {
        val ri = packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com")), PackageManager.MATCH_DEFAULT_ONLY)
        ri?.activityInfo?.packageName == packageName
    } catch (_: Exception) { false }

    private fun round(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat(); setColor(color); if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(if (primary) Color.parseColor("#03140F") else TXT)
        background = if (primary) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ACC, Color.parseColor("#7AF7DA"))).apply { cornerRadius = dp(14).toFloat() }
                     else round(Color.parseColor("#0A1020"), 14, LINE)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun card(emoji: String, title: String, desc: String, on: Boolean?, btn: View) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(CARD, 18, LINE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text(emoji, 22f, TXT))
        head.addView(text("  $title", 16f, TXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (on != null) {
            head.addView(text(if (on) "● Қосулы" else "○ Өшірулі", 12f, if (on) OK else WARN, true))
        }
        c.addView(head)
        c.addView(gap(6))
        c.addView(text(desc, 13.5f, MUT))
        c.addView(gap(12))
        c.addView(btn)
        list.addView(c)
        list.addView(gap(12))
    }

    private fun render() {
        list.removeAllViews()

        val logo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        logo.addView(ImageView(this).apply { setImageResource(R.drawable.ic_launcher) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        logo.addView(text("  Cyber", 26f, TXT, true))
        logo.addView(text("Qalqan", 26f, ACC, true))
        list.addView(logo)
        list.addView(gap(8))
        list.addView(text("Сілтемені басудан бұрын тексеретін қалқан. Алаяқтардың SMS, WhatsApp және Telegram сілтемелерінен қорғайды.", 14.5f, MUT))
        list.addView(gap(20))

        // ---- Қорғаныс күйі ----
        val genuine = Security.isGenuine(this)
        val rooted = Security.isRooted()
        val lockOn = Security.lockEnabled(this)
        val devSecure = Security.deviceSecure(this)
        if (!genuine) {
            card("⛔", "Жалған қосымша!",
                "Бұл CyberQalqan-ның түпнұсқасы емес — оны біреу өзгерткен. Дереу өшіріп, ресми сайттан қайта жүктеңіз.",
                false, button("Қосымшаны өшіру", true) {
                    startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
                })
        }
        val secBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        secBox.addView(text(
            (if (genuine) "✅ Түпнұсқа қосымша (қолтаңба расталды)" else "⛔ Қолтаңба сәйкес емес") + "\n" +
            "✅ SMS мәтіні телефоннан шықпайды — серверге тек домен атауы жіберіледі\n" +
            "✅ Скриншот пен экран жазуға тыйым салынған\n" +
            (if (rooted) "⚠️ Телефон root-талған — қауіпсіздік төмен" else "✅ Root анықталмады") + "\n" +
            (if (!lockOn) "○ Баптаулар құлпы өшірулі" else if (devSecure && Vault.hasPin(this)) "✅ 2FA: саусақ ізі/телефон құлпы + CyberQalqan PIN" else if (Vault.hasPin(this)) "✅ Құлып: CyberQalqan PIN (телефонда экран құлпы жоқ)" else if (devSecure) "◐ 1 фактор: саусақ ізі/телефон құлпы — PIN қосыңыз" else "⚠️ Құлып жоқ — PIN орнатыңыз") + "\n" +
            "✅ Тарих пен соңғы сілтеме AES-256 (Android Keystore) шифрланған",
            13.5f, TXT))
        secBox.addView(gap(10))
        secBox.addView(button(if (Vault.hasPin(this)) "🔢 PIN-ды өзгерту" else "🔢 PIN орнату (2FA)", !Vault.hasPin(this)) { setupPin() })
        secBox.addView(gap(8))
        secBox.addView(button(if (lockOn) "🔐 Құлыпты өшіру" else "🔐 Құлыпты қосу", false) {
            val apply = { Security.setLock(this, !lockOn); if (lockOn) Vault.setPin(this, null); render() }
            if (Vault.hasPin(this)) askPin("Растау үшін PIN") { if (it) apply() }
            else if (devSecure) Security.authenticate(this) { ok -> runOnUiThread { if (ok) apply() } }
            else apply()
        })
        card("🛡️", "Қорғаныс", "Бұл қосымшаны бұзу және жалған көшірмесін жасау қиын етіп жасалған.", genuine && !rooted, secBox)

        val bubbleOn = BubbleService.isRunning()
        card("🛡️", "Қалқымалы батырма",
            "Экран шетінде домалақ батырма тұрады. SMS-те сілтеме келгенде батырманы басыңыз — сілтемені көшірудің қажеті жоқ: CyberQalqan өзі тексеріп, нәтижені экранға шығарады, қауіпті болса дабыл қағады. («SMS-ті автоматты тексеру» қосулы болуы керек.)",
            bubbleOn,
            button(if (bubbleOn) "Батырманы жасыру" else "Батырманы қосу", !bubbleOn) {
                if (bubbleOn) {
                    startService(Intent(this, BubbleService::class.java).setAction(BubbleService.ACTION_STOP))
                    list.postDelayed({ render() }, 300)
                } else if (canOverlay()) startBubble()
                else {
                    wantBubble = true
                    Toast.makeText(this, "CyberQalqan үшін «Басқа қосымшалардың үстінен көрсету» рұқсатын қосыңыз", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            })

        val br = isDefaultBrowser()
        card("👆", "SMS-тегі сілтемені басқанда тексеру",
            "Ең оңай жол. Кез келген чатта (SMS, WhatsApp, Telegram) сілтемені басқанда, ол бірден ашылмайды — алдымен CyberQalqan тексереді. Қауіпті болса дабыл қағады, қауіпсіз болса өз браузеріңізде ашасыз.",
            br,
            button(if (br) "✅ Қосулы — баптауларды ашу" else "Қосу (1 батырма)", !br) {
                if (br) try { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) } catch (_: Exception) {}
                else requestBrowserRole()
            })

        val full = hasListener()
        val lis = full && listenerOn()
        if (full) {
            card("📩", "SMS-ті автоматты тексеру",
                "Жаңа SMS, WhatsApp, Telegram хабарламасында сілтеме келсе, CyberQalqan оны өзі тексеріп, қауіпті болса ескертеді. Хабарламалар телефоннан еш жаққа жіберілмейді.",
                lis && Prefs.consent(this),
                button(if (lis) "Баптауларды ашу" else "Автоматты тексеруді қосу", !lis) {
                    askListenerConsent {
                        Toast.makeText(this, "Тізімнен «CyberQalqan SMS қорғанысы» қосыңыз", Toast.LENGTH_LONG).show()
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                })
        } else {
            card("ℹ️", "Lite нұсқа",
                "Бұл нұсқа хабарламаларыңызды оқымайды — сондықтан Google Play Protect оны бұғаттамайды. Сілтемені басқанда тексеру, домалақ батырма, дабыл және отбасылық қалқан толық жұмыс істейді. SMS-ті автоматты тексеру керек болса — сайттан «Толық нұсқаны» орнатыңыз.",
                null, text("", 1f, MUT))
        }

        val prefs = getSharedPreferences(AlarmActivity.PREFS, MODE_PRIVATE)
        val sound = prefs.getBoolean(AlarmActivity.KEY_SOUND, true)
        val alarmBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        alarmBox.addView(button("🚨 Дабылды сынап көру", true) {
            startActivity(AlarmActivity.intent(this, "http://kaspi-bonus-kz.xyz/login", 96,
                "Kaspi: Сізге 50 000 ₸ бонус! 24 сағат ішінде алыңыз", "+7 705 412 88 19", "Kaspi.kz"))
        })
        alarmBox.addView(gap(8))
        alarmBox.addView(button(if (sound) "🔊 Дабыл дыбысы: қосулы" else "🔇 Дабыл дыбысы: өшірулі", false) {
            prefs.edit().putBoolean(AlarmActivity.KEY_SOUND, !sound).apply(); render()
        })
        val auto = prefs.getBoolean(SmsWatcherService.KEY_AUTO, false)
        if (full) {
            alarmBox.addView(gap(8))
            alarmBox.addView(button(if (auto) "⚡ Режим: автоматты дабыл" else "🛡️ Режим: «Тексеру» батырмасы", false) {
                prefs.edit().putBoolean(SmsWatcherService.KEY_AUTO, !auto).apply(); render()
            })
        }
        card("🚨", "Дабыл режимі",
            "Сілтемесі бар хабарлама келгенде жанында «🛡️ Тексеру» батырмасы шығады. Басқанда сілтеме ашылмайды — CyberQalqan өзі тексереді: қауіпсіз болса «✅ Қауіпті емес» дейді, қауіпті болса телефон дабыл қағып, «БАСПАҢЫЗ!» және 102 батырмасы шығады. Автоматты режимде дабыл батырмасыз-ақ бірден қағылады.",
            lis, alarmBox)

        val testBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        testBox.addView(button("📩 Қауіпті тест хабарлама", true) {
            Notify.linkFound(this, "http://kaspi-bonus-kz.xyz/login",
                "Kaspi: Сізге 50 000 ₸ бонус есептелді! 24 сағат ішінде алыңыз: kaspi-bonus-kz.xyz/login", "+7 705 412 88 19")
            Toast.makeText(this, "Хабарландыруды ашып, «🛡️ ТЕКСЕРУ» басыңыз", Toast.LENGTH_LONG).show()
        })
        testBox.addView(gap(8))
        testBox.addView(button("📩 Қауіпсіз тест хабарлама", false) {
            Notify.linkFound(this, "https://kaspi.kz/shop", "Тапсырысыңыз дайын: https://kaspi.kz/shop", "KASPI.KZ")
            Toast.makeText(this, "Хабарландыруды ашып, «🛡️ ТЕКСЕРУ» басыңыз", Toast.LENGTH_LONG).show()
        })
        card("🧪", "Тест жасау",
            "Екінші телефон болмаса — осы батырмалар SMS сияқты хабарландыру жасайды. Нағыз тест үшін басқа телефоннан сілтемесі бар SMS жіберіңіз (алдымен «SMS-ті автоматты тексеру» қосулы болсын).",
            null, testBox)

        // ---- Отбасылық қалқан ----
        val famAct = Family.active(this).size
        val famPend = Family.pendingCount(this)
        card("👨‍👩‍👧", "Отбасылық қалқан",
            "Күмәнді хабарлама келсе, бір батырмамен сенімді туысыңызға ескерту жіберіңіз. Ол сізге қоңырау шалып, алаяқтықтың алдын алуға көмектеседі. Хабарламаға сілтеме мен кодтар қосылмайды.",
            if (famAct > 0) true else if (famPend > 0) false else null,
            button(if (famAct > 0) "Отбасылық қорғаныс белсенді — ашу" else if (famPend > 0) "Туыстың растау кодын енгізу" else "➕ Сенімді адам қосу", famAct == 0) {
                startActivity(Intent(this, CheckActivity::class.java).putExtra(CheckActivity.EXTRA_OPEN, "family"))
            })

        // ---- Баптаулар ----
        val setBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val srv = Prefs.bool(this, Prefs.AI_SRV, false)
        val aiOn = Prefs.bool(this, Prefs.AI, true)
        setBox.addView(button(if (!srv) "🤖 ChatGPT: серверде кілт орнатылмаған" else if (aiOn) "🤖 ChatGPT талдауы: қосулы" else "🤖 ChatGPT талдауы: өшірулі", false) {
            if (srv) { Prefs.set(this, Prefs.AI, !aiOn); render() } else Api.refreshAiEnabled(applicationContext)
        })
        setBox.addView(gap(8))
        val aiSms = Prefs.bool(this, Prefs.AI_SMS, false)
        setBox.addView(button(if (aiSms) "✉️ SMS мәтінін ИИ-ге жіберу: иә" else "✉️ SMS мәтінін ИИ-ге жіберу: жоқ (тек сілтеме)", false) {
            if (aiSms) { Prefs.set(this, Prefs.AI_SMS, false); render() }
            else AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("SMS мәтінін жіберу").setMessage("ИИ дәлірек талдауы үшін хабарлама мәтіні OpenAI серверіне жіберіледі. Мәтінде жеке деректер болуы мүмкін. Келісесіз бе?")
                .setPositiveButton("Иә") { _, _ -> Prefs.set(this, Prefs.AI_SMS, true); render() }.setNegativeButton("Жоқ", null).show()
        })
        setBox.addView(gap(8))
        val th = Prefs.threshold(this)
        val thName = when (th) { 50 -> "жоғары (50%)"; 70 -> "төмен (70%)"; else -> "орташа (60%)" }
        setBox.addView(button("🎚️ Дабыл сезімталдығы: $thName", false) {
            Prefs.setThreshold(this, when (th) { 60 -> 50; 50 -> 70; else -> 60 }); render()
        })
        setBox.addView(gap(8))
        setBox.addView(button("🗑️ Тексеру тарихын өшіру", false) {
            Vault.wipe(this); Toast.makeText(this, "Тарих өшірілді", Toast.LENGTH_SHORT).show()
        })
        setBox.addView(gap(8))
        if (full) {
            setBox.addView(button(if (Prefs.consent(this)) "📩 Хабарландыру келісімін қайтарып алу" else "📩 Хабарландыру келісімі: берілмеген", false) {
                if (Prefs.consent(this)) {
                    Prefs.set(this, Prefs.CONSENT, false); Vault.wipe(this)
                    Toast.makeText(this, "Келісім алынды. Толық өшіру үшін тізімнен CyberQalqan-ды өшіріңіз", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } else askListenerConsent { render() }
            })
        }
        setBox.addView(gap(8))
        setBox.addView(button("🔐 Құпиялылық саясаты", false) { showPrivacy() })
        card("⚙️", "Баптаулар", "ИИ, дабыл сезімталдығы, құпиялылық — бәрін өзіңіз басқарасыз.", null, setBox)

        card("🔍", "Сілтемені қолмен тексеру",
            "Сілтемені, SMS мәтінін немесе жіберуші нөмірін қойып, толық есеп алыңыз. Кеңес: SMS-тегі мәтінді белгілеп, мәзірден «CyberQalqan тексеру» таңдасаңыз да болады.",
            null,
            button("Тексеруді ашу", true) { startActivity(Intent(this, CheckActivity::class.java)) })

        list.addView(gap(6))
        list.addView(text("🔒 Сілтеме тексеру кезінде ешқашан ашылмайды. Нәтиже — ықтималдық бағасы. Күмән болса, банкке тек картаның артындағы нөмір арқылы хабарласыңыз.", 12f, Color.parseColor("#5B6888")))
    }

    private fun startBubble() {
        val i = Intent(this, BubbleService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        list.postDelayed({ render() }, 400)
        Toast.makeText(this, "Батырма қосылды — экран шетін қараңыз 🛡️", Toast.LENGTH_SHORT).show()
    }
}
