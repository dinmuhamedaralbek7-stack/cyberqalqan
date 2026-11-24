package kz.cyberqalqan

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

/**
 * ИИ-кеңесші: өз сайтымыздағы чатты (?inapp=ai) ашады — мәтін және дауыс.
 * Тек өз доменіміз ашылады; басқа сілтемелер (дереккөздер) CyberQalqan тексеруі арқылы сыртқа шығады.
 */
class AiActivity : Activity() {
    private lateinit var web: WebView
    private var host = ""
    private var micReq: PermissionRequest? = null
    private val MIC = 7702

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Security.secureWindow(this)
        val base = Family.siteBase(this)
        if (base.isEmpty()) { Toast.makeText(this, "Сайт мекенжайы бапталмаған", Toast.LENGTH_LONG).show(); finish(); return }
        host = Uri.parse(base).host.orEmpty()
        web = WebView(this); setContentView(web)
        web.setBackgroundColor(0xFF05070D.toInt())
        with(web.settings) {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false          // ИИ жауабын дауыстап оқу
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false); saveFormData = false
            setSupportMultipleWindows(false)
        }
        web.addJavascriptInterface(Bridge(), "CQApp")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme == "https" && u.host == host) return false
                try {
                    when (u.scheme) {
                        "tel" -> Dialer.call(this@AiActivity, u.schemeSpecificPart)
                        "https" -> startActivity(Intent(this@AiActivity, CheckActivity::class.java).putExtra(CheckActivity.EXTRA_URL, u.toString()))
                        "sms", "mailto" -> startActivity(Intent(Intent.ACTION_VIEW, u))
                    }
                } catch (_: Exception) {}
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val wantsMic = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    if (!wantsMic || request.origin.host != host) { request.deny(); return@runOnUiThread }
                    if (Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                        request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    else { micReq = request; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC) }
                }
            }
        }
        web.loadUrl("$base/?inapp=ai")
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, perms, grants)
        if (code == MIC) {
            val r = micReq; micReq = null
            if (grants.firstOrNull() == PackageManager.PERMISSION_GRANTED) r?.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            else { r?.deny(); Toast.makeText(this, "Дауыспен сұрау үшін микрофонға рұқсат беріңіз", Toast.LENGTH_LONG).show() }
        }
        Dialer.onResult(this, code, grants)
    }

    @Deprecated("Activity API")
    override fun onBackPressed() { if (::web.isInitialized && web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed() }

    override fun onDestroy() { if (::web.isInitialized) { web.stopLoading(); web.destroy() }; super.onDestroy() }

    inner class Bridge {
        @JavascriptInterface fun call(phone: String) { runOnUiThread { Dialer.call(this@AiActivity, phone.take(20)) } }
        @JavascriptInterface fun compose(method: String, phone: String, body: String): Boolean =
            Family.compose(this@AiActivity, if (method == "whatsapp") "whatsapp" else "sms", phone.take(20), body.take(1500))
    }
}
