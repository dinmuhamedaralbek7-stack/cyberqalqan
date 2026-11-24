package kz.cyberqalqan

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.tanh

/**
 * 🚨 «Сигналка» — көлік сигнализациясы сияқты қатты, үздіксіз дабыл.
 *  - Дыбыс телефонның ішінде синтезделеді (файл керек емес): ұлу → хай-лоу → шиқылдау → жылдам «вуп».
 *  - USAGE_ALARM ағыны: көп телефонда «Без звука» режимінде де естіледі.
 *  - Дабыл кезінде дабыл дыбысының деңгейі ең жоғарыға қойылады, өшкенде бұрынғы деңгейі қайтарылады.
 *  - Фонарь (жарқыл) жыпылықтайды — көлік шамдары сияқты (рұқсат керек емес, Android 6+).
 */
object Siren {
    private const val SR = 22050
    private var track: AudioTrack? = null
    private var prevVol = -1
    private var torchId: String? = null
    private var torchOn = false
    private val main = Handler(Looper.getMainLooper())
    private var strobe: Runnable? = null

    /** Бір цикл (~6 с) сигнализация дыбысы, 16-bit PCM */
    private fun pattern(): ShortArray {
        val out = ArrayList<Short>(SR * 7)
        var ph = 0.0
        fun tone(sec: Double, f0: Double, f1: Double = f0, gate: Double = 0.0) {
            val n = (sec * SR).toInt()
            for (i in 0 until n) {
                val t = i.toDouble() / n
                val f = f0 + (f1 - f0) * t
                ph += 2 * PI * f / SR
                // «Квадратқа» жақын толқын — құлаққа әлдеқайда қатты естіледі
                var v = tanh(3.2 * sin(ph)) * 0.97
                if (gate > 0 && ((i.toDouble() / SR) % (2 * gate)) >= gate) v = 0.0
                out += (v * Short.MAX_VALUE).toInt().toShort()
            }
        }
        repeat(3) { tone(0.32, 700.0, 1700.0); tone(0.18, 1700.0, 700.0) }   // 1) ұлу
        repeat(9) { tone(0.11, 1450.0); tone(0.11, 950.0) }                  // 2) хай-лоу
        tone(1.4, 2800.0, gate = 0.05)                                       // 3) шиқылдау (пулемет)
        repeat(4) { tone(0.24, 600.0, 2100.0) }                              // 4) жылдам «вуп»
        return out.toShortArray()
    }

    fun start(ctx: Context, torch: Boolean) {
        stop(ctx)
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        try {
            if (am != null) {
                prevVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            }
        } catch (_: Exception) { prevVol = -1 }
        try {
            val pcm = pattern()
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            val fmt = AudioFormat.Builder().setSampleRate(SR).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            val t = AudioTrack(attrs, fmt, pcm.size * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE)
            t.write(pcm, 0, pcm.size)
            t.setLoopPoints(0, pcm.size, -1)   // шексіз қайталау — адам өзі өшіргенше
            t.setVolume(1f)
            t.play()
            track = t
        } catch (_: Exception) { track = null }
        if (torch) startStrobe(ctx)
    }

    fun isPlaying() = track != null

    private fun startStrobe(ctx: Context) {
        if (Build.VERSION.SDK_INT < 23) return
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        torchId = try {
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        } catch (_: Exception) { null }
        val id = torchId ?: return
        val r = object : Runnable {
            override fun run() {
                torchOn = !torchOn
                try { cm.setTorchMode(id, torchOn) } catch (_: Exception) { return }
                main.postDelayed(this, if (torchOn) 120 else 180)
            }
        }
        strobe = r; main.post(r)
    }

    fun stop(ctx: Context) {
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
        strobe?.let { main.removeCallbacks(it) }; strobe = null
        if (Build.VERSION.SDK_INT >= 23) torchId?.let { id ->
            try { (ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager)?.setTorchMode(id, false) } catch (_: Exception) {}
        }
        torchOn = false
        if (prevVol >= 0) {
            try { (ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.setStreamVolume(AudioManager.STREAM_ALARM, prevVol, 0) } catch (_: Exception) {}
            prevVol = -1
        }
    }
}
