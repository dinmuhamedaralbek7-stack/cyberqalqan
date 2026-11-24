package kz.cyberqalqan

import android.content.Context

/** Пайдаланушы баптаулары (бір жерде). */
object Prefs {
    const val AI = "ai_on"                 // ChatGPT талдауы (әдепкі: қосулы, егер сервер рұқсат етсе)
    const val AI_SMS = "ai_sms"            // SMS мәтінін де ИИ-ге жіберу (әдепкі: ӨШІРУЛІ — құпиялылық)
    const val THRESH = "alarm_threshold"   // дабыл шегі, %
    const val CONSENT = "listener_consent" // хабарландыруларды оқуға ашық келісім
    const val AI_SRV = "ai_server"         // сервер ИИ кілті орнатылған ба (кэш)
    const val TORCH = "alarm_torch"        // дабыл кезінде фонарь жыпылықтасын (әдепкі: иә)

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(AlarmActivity.PREFS, Context.MODE_PRIVATE)
    fun bool(c: Context, k: String, def: Boolean) = sp(c).getBoolean(k, def)
    fun set(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()
    fun threshold(c: Context) = sp(c).getInt(THRESH, 60)
    fun setThreshold(c: Context, v: Int) = sp(c).edit().putInt(THRESH, v).apply()

    fun aiActive(c: Context) = bool(c, AI, true) && bool(c, AI_SRV, false)
    fun consent(c: Context) = bool(c, CONSENT, false)
}
