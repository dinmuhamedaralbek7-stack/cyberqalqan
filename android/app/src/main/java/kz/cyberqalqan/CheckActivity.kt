package kz.cyberqalqan

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/** Толық есеп экраны: сілтемені тексеріп, қауіп пайызын, салдарын, кеңестерді көрсетеді. */
class CheckActivity : Activity() {

    companion object {
        const val EXTRA_URL = "cq_url"
        const val EXTRA_SMS = "cq_sms"
        const val EXTRA_SENDER = "cq_sender"
        const val EXTRA_FROM_BUBBLE = "cq_from_bubble"
        const val EXTRA_OPEN = "cq_open"   // "family" — Отбасылық қалқан бөлімін ашу
        private const val PAGE = "file:///android_asset/report.html#app"
    }

    private lateinit var web: WebView
    private var loaded = false
    private var pendingJs: String? = null
    private var wantClipboard = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Security.secureWindow(this)
        Api.refreshAiEnabled(applicationContext)
        web = WebView(this)
        setContentView(web)
        web.setBackgroundColor(0xFF05070D.toInt())
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
            blockNetworkLoads = true          // ешбір интернет сұрауы жоқ
            saveFormData = false
        }
        web.addJavascriptInterface(Bridge(), "CQApp")
        web.webViewClient = object : WebViewClient() {
            // Қауіпсіздік: бет тек өз файлын көрсетеді, ешбір сыртқы сайтқа өтпейді
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !request.url.toString().startsWith("file:///android_asset/")

            override fun onPageFinished(view: WebView, url: String) {
                loaded = true
                pendingJs?.let { view.evaluateJavascript(it, null) }
                pendingJs = null
            }
        }
        web.loadUrl(PAGE)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i == null) return
        when (i.action) {
            Intent.ACTION_SEND -> {
                val t = i.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                submit(RiskEngine.findUrls(t).firstOrNull().orEmpty(), t, "")
            }
            Intent.ACTION_PROCESS_TEXT -> {
                val t = i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
                submit(RiskEngine.findUrls(t).firstOrNull() ?: t.trim(), t, "")
            }
            Intent.ACTION_VIEW -> submit(i.dataString.orEmpty(), "", "")
            else -> {
                val url = i.getStringExtra(EXTRA_URL)
                if (!url.isNullOrEmpty()) {
                    submit(url, i.getStringExtra(EXTRA_SMS).orEmpty(), i.getStringExtra(EXTRA_SENDER).orEmpty())
                } else if (i.getStringExtra(EXTRA_OPEN) == "family") {
                    evalJs("window.cqOpenFamily && window.cqOpenFamily()")
                } else if (i.getBooleanExtra(EXTRA_FROM_BUBBLE, false)) {
                    wantClipboard = true
                }
            }
        }
    }

    // Android 10+ буферді тек экран белсенді болғанда оқуға рұқсат береді
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && wantClipboard) {
            wantClipboard = false
            val t = readClipboard()
            val u = RiskEngine.findUrls(t).firstOrNull()
            if (u != null) submit(u, "", "") else evalJs("window.cqFocus && window.cqFocus()")
        }
    }

    private fun readClipboard(): String = try {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString().orEmpty() else ""
    } catch (_: Exception) { "" }

    private fun submit(url: String, sms: String, sender: String) {
        evalJs("window.cqCheck && window.cqCheck(${JSONObject.quote(url.take(2048))},${JSONObject.quote(sms.take(2000))},${JSONObject.quote(sender.take(60))})")
        if (url.isNotBlank()) BubbleService.setBadge(RiskEngine.analyze(url, sms, sender).score)
    }

    private fun evalJs(js: String) {
        if (loaded) web.evaluateJavascript(js, null) else pendingJs = js
    }

    private fun openExternal(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "http" && uri.scheme != "https") return
        val view = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        val chooser = Intent.createChooser(view, "Қай браузермен ашу?")
        chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, CheckActivity::class.java)))
        try { startActivity(chooser) } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, perms, grants)
        Dialer.onResult(this, code, grants)
    }

    inner class Bridge {
        @JavascriptInterface fun isApp(): Boolean = true
        @JavascriptInterface fun clip(): String = readClipboard()
        @JavascriptInterface fun openInBrowser(url: String) { runOnUiThread { openExternal(url) } }
        @JavascriptInterface fun close() { runOnUiThread { finish() } }
        @JavascriptInterface fun callPolice() { runOnUiThread { AlarmActivity.dialPolice(this@CheckActivity) } }
        @Suppress("DEPRECATION")
        @JavascriptInterface fun vibrateAlarm() {
            runOnUiThread {
                try {
                    val v = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
                    if (android.os.Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createWaveform(Notify.ALARM_VIBRATION, 0))
                    else v.vibrate(Notify.ALARM_VIBRATION, 0)
                } catch (_: Exception) {}
            }
        }
        /** Доменнің сенім деңгейін фонда сұрап, нәтижені window.cqTrustResult(...) арқылы қайтарады */
        @JavascriptInterface fun trust(domain: String) {
            val d = domain.take(253)
            Thread {
                val j = Api.trust(this@CheckActivity, d)
                runOnUiThread {
                    evalJs("window.cqTrustResult && window.cqTrustResult(${JSONObject.quote(d)}, ${j?.toString() ?: "null"})")
                }
            }.start()
        }
        // ---- Отбасылық қалқан: деректер Vault-та шифрланып сақталады ----
        @JavascriptInterface fun famLoad(): String = Family.load(this@CheckActivity).toString()
        @JavascriptInterface fun famSave(json: String): Boolean = Family.save(this@CheckActivity, json)
        @JavascriptInterface fun siteBase(): String = Family.siteBase(this@CheckActivity)
        /** SMS/WhatsApp қолданбасын дайын мәтінмен ашу (жіберуді пайдаланушы өзі басады) */
        @JavascriptInterface fun call(phone: String) { runOnUiThread { Dialer.call(this@CheckActivity, phone.take(20)) } }
        @JavascriptInterface fun compose(method: String, phone: String, body: String): Boolean =
            Family.compose(this@CheckActivity, if (method == "whatsapp") "whatsapp" else "sms", phone.take(20), body.take(1500))
        @JavascriptInterface fun aiEnabled(): Boolean = Prefs.aiActive(this@CheckActivity)
        /** ChatGPT талдауы: нәтиже window.cqAiResult(id, json) арқылы қайтады */
        @JavascriptInterface fun ai(id: String, json: String) {
            val safeId = id.take(24).replace(Regex("[^a-zA-Z0-9]"), "")
            Thread {
                val body = try { JSONObject(json.take(8000)) } catch (_: Exception) { null }
                val j = if (body != null) Api.ai(this@CheckActivity, body) else null
                runOnUiThread { evalJs("window.cqAiResult && window.cqAiResult(${JSONObject.quote(safeId)}, ${j?.toString() ?: "null"})") }
            }.start()
        }
        @JavascriptInterface fun stopVibrate() {
            runOnUiThread { try { (getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator).cancel() } catch (_: Exception) {} }
        }
    }

    override fun onDestroy() {
        try { (getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator).cancel() } catch (_: Exception) {}
        web.destroy()
        super.onDestroy()
    }
}
