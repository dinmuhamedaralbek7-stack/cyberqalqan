package kz.cyberqalqan

import android.animation.ValueAnimator
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.abs

/** Барлық қосымшалардың үстінде тұратын домалақ CyberQalqan батырмасы. */
class BubbleService : Service() {

    companion object {
        const val ACTION_STOP = "kz.cyberqalqan.STOP_BUBBLE"
        @Volatile var instance: BubbleService? = null
        fun isRunning() = instance != null
        fun setBadge(score: Int) { instance?.updateBadge(score) }
        /** SMS-те жаңа сілтеме келді — батырма «!» белгісімен жыпылықтайды */
        fun newLink(score: Int) { instance?.markNew() }
    }

    private lateinit var wm: WindowManager
    private var root: FrameLayout? = null
    private lateinit var badge: TextView
    private lateinit var lp: WindowManager.LayoutParams
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startAsForeground()
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) { stopSelf(); return }
        addBubble()
        guard = InstallGuard.register(this)   // жаңа қосымша орнатылса / Accessibility берілсе — бірден тексеру
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    private fun startAsForeground() {
        Notify.ensureChannels(this)
        val stop = Intent(this, BubbleService::class.java).setAction(ACTION_STOP)
        val flags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val stopPi = PendingIntent.getService(this, 1, stop, flags)
        val openPi = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), flags)
        val n: Notification = Notify.builder(this, Notify.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("CyberQalqan қорғап тұр")
            .setContentText("SMS-те сілтеме келсе — батырманы басыңыз, өзі тексереді")
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_shield), "Батырманы жасыру", stopPi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun addBubble() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val size = dp(62)

        val circle = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#3CF2C3"), Color.parseColor("#5B8CFF"))
            ).apply { shape = GradientDrawable.OVAL; setStroke(dp(3), Color.parseColor("#9905070D")) }
            elevation = dp(8).toFloat()
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_shield) },
                FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        }
        badge = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6), dp(1), dp(6), dp(1))
            visibility = View.GONE
            elevation = dp(10).toFloat()
        }
        val frame = FrameLayout(this)
        frame.addView(circle, FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.START).apply {
            topMargin = dp(10); marginEnd = dp(12)
        })
        frame.addView(badge, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))

        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                   else WindowManager.LayoutParams.TYPE_PHONE
        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val dm = resources.displayMetrics
            x = dm.widthPixels - size - dp(16)
            y = (dm.heightPixels * 0.62).toInt()
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        frame.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                    circle.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        try { wm.updateViewLayout(frame, lp) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    circle.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(OvershootInterpolator()).start()
                    if (!moved && e.actionMasked == MotionEvent.ACTION_UP) { v.performClick(); onTap() } else snapToEdge(frame)
                }
            }
            true
        }
        try { wm.addView(frame, lp); root = frame } catch (_: Exception) { stopSelf() }
    }

    private fun snapToEdge(view: View) {
        val dm = resources.displayMetrics
        val w = view.width.takeIf { it > 0 } ?: dp(74)
        val target = if (lp.x + w / 2 < dm.widthPixels / 2) dp(4) else dm.widthPixels - w - dp(4)
        lp.y = lp.y.coerceIn(dp(40), dm.heightPixels - dp(140))
        ValueAnimator.ofInt(lp.x, target).apply {
            duration = 220
            addUpdateListener {
                lp.x = it.animatedValue as Int
                try { wm.updateViewLayout(view, lp) } catch (_: Exception) {}
            }
            start()
        }
    }

    // ================== Батырманы басқанда: көшірусіз тексеру ==================
    private var panel: View? = null
    private var hideTask: Runnable? = null

    private fun onTap() {
        if (panel != null) { removePanel(); return }
        val last = Vault.last(this)
        if (last == null) { openCheck(); return }   // соңғы 30 минутта сілтеме келмеген — қолмен/буферден тексеру
        val (url, sms, sender) = last
        showPanel(Color.parseColor("#5B8CFF"), "🔎 Тексеріп жатырмын…", Api.hostOf(url), listOf("Сілтеме АШЫЛМАЙДЫ — тек талданады"), url, sms, sender, false)
        Thread {
            val o = Checker.run(applicationContext, url, sms, sender)
            main.post { showResult(o, url, sms, sender) }
        }.start()
    }

    private fun openCheck(url: String? = null, sms: String = "", sender: String = "") {
        removePanel()
        val i = Intent(this, CheckActivity::class.java).apply {
            if (url != null) { putExtra(CheckActivity.EXTRA_URL, url); putExtra(CheckActivity.EXTRA_SMS, sms); putExtra(CheckActivity.EXTRA_SENDER, sender) }
            else putExtra(CheckActivity.EXTRA_FROM_BUBBLE, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(i)
    }

    private fun showResult(o: Checker.Out, url: String, sms: String, sender: String) {
        val sc = o.r.score
        updateBadge(sc)
        if (sc >= Prefs.threshold(this)) {
            removePanel()
            try { startActivity(AlarmActivity.intent(this, url, sc, sms, sender, o.r.brand).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: Exception) { Notify.alert(this, o.r, sms, sender, alarm = true) }
            return
        }
        val lines = mutableListOf<String>()
        val f = o.trust?.optJSONObject("facts")
        val age = Checker.ageText(o.trust)
        if (age.isNotEmpty()) lines += "📅 Домен жасы: $age"
        if (f != null) {
            if (f.has("rank") && !f.isNull("rank")) lines += "🌍 Әлемдік рейтинг: #${f.optInt("rank")}"
            if (f.has("https")) lines += if (f.optBoolean("https")) "🔒 HTTPS сертификаты бар" else "⚠️ HTTPS жоқ"
            f.optString("country").takeIf { it.isNotBlank() && it != "null" }?.let { lines += "📍 Сервер: $it" }
        }
        if (o.trust != null) lines += "🛡️ Сенім деңгейі: ${o.trust.optInt("score")}/100"
        o.ai?.optString("verdict")?.takeIf { it.isNotBlank() }?.let { lines += "🤖 ChatGPT: $it" }
        if (o.trust == null && o.ai == null) lines += "📴 Интернетсіз тексерілді (тек офлайн белгілер)"
        for (x in o.r.reasons.take(2)) lines += "• $x"
        if (sc >= 40) showPanel(Color.parseColor("#FFB547"), "⚠️ Күдікті — $sc%", Api.hostOf(url), lines + "Басуға асықпаңыз, жіберушіні тексеріңіз", url, sms, sender, true)
        else showPanel(Color.parseColor("#2EE59D"), "✅ Қауіпті емес — $sc%", Api.hostOf(url), lines, url, sms, sender, true)
    }

    @Suppress("DEPRECATION")
    private fun showPanel(color: Int, title: String, host: String, lines: List<String>, url: String, sms: String, sender: String, done: Boolean) {
        removePanel()
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.parseColor("#F20E1526")); setStroke(dp(2), color) }
            elevation = dp(12).toFloat()
        }
        fun tv(t: String, size: Float, c: Int, bold: Boolean = false) = TextView(this).apply {
            text = t; textSize = size; setTextColor(c); if (bold) typeface = Typeface.DEFAULT_BOLD; setLineSpacing(0f, 1.12f)
        }
        box.addView(tv(title, 19f, color, true))
        box.addView(tv(host, 14f, Color.parseColor("#E8EEFC"), true).apply { setPadding(0, dp(2), 0, dp(6)) })
        for (l in lines.take(8)) box.addView(tv(l, 13.5f, Color.parseColor("#C5D0EA")))
        if (done) {
            val row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
            fun btn(t: String, primary: Boolean, on: () -> Unit) = TextView(this).apply {
                text = t; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setTextColor(if (primary) Color.parseColor("#03140F") else Color.parseColor("#E8EEFC"))
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(if (primary) color else Color.parseColor("#1E2A44")) }
                setPadding(dp(10), dp(10), dp(10), dp(10)); setOnClickListener { on() }
            }
            row.addView(btn("Толық есеп", false) { openCheck(url, sms, sender) }, android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            row.addView(btn("Жабу", true) { removePanel() }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            box.addView(row)
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val plp = WindowManager.LayoutParams(
            resources.displayMetrics.widthPixels - dp(24), WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(48) }
        try { wm.addView(box, plp); panel = box } catch (_: Exception) {}
        box.alpha = 0f; box.translationY = -dp(20).toFloat()
        box.animate().alpha(1f).translationY(0f).setDuration(200).start()
        if (done) { hideTask = Runnable { removePanel() }.also { main.postDelayed(it, 25_000) } }
    }

    private fun removePanel() {
        hideTask?.let { main.removeCallbacks(it) }; hideTask = null
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
    }

    fun markNew() {
        main.post {
            if (root == null) return@post
            badge.text = "!"
            badge.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat(); setColor(Color.parseColor("#5B8CFF")); setStroke(dp(2), Color.parseColor("#05070D"))
            }
            badge.visibility = View.VISIBLE
            root?.animate()?.scaleX(1.15f)?.scaleY(1.15f)?.setDuration(150)?.withEndAction {
                root?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(150)?.start()
            }?.start()
        }
    }

    fun updateBadge(score: Int) {
        main.post {
            if (root == null) return@post
            val color = when {
                score >= 70 -> "#FF4D6D"; score >= 40 -> "#FFB547"; else -> "#2EE59D"
            }
            badge.text = "$score%"
            badge.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat(); setColor(Color.parseColor(color)); setStroke(dp(2), Color.parseColor("#05070D"))
            }
            badge.visibility = View.VISIBLE
            if (score >= 40) {
                root?.animate()?.translationX(dp(6).toFloat())?.setDuration(60)?.withEndAction {
                    root?.animate()?.translationX(-dp(6).toFloat())?.setDuration(60)?.withEndAction {
                        root?.animate()?.translationX(0f)?.setDuration(60)?.start()
                    }?.start()
                }?.start()
            }
        }
    }

    private var guard: InstallGuard.Handle? = null

    override fun onDestroy() {
        InstallGuard.unregister(this, guard); guard = null
        removePanel()
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null
        instance = null
        super.onDestroy()
    }
}
