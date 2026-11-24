package kz.cyberqalqan

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * 🛡️ Орнату күзетшісі — APK-алаяқтықтың ең қауіпті екі сәтін ұстайды:
 *  1) Жаңа қосымша орнатылды (PACKAGE_ADDED) → бірден тексеріледі, қауіпті болса — сигналка.
 *  2) Бөгде қосымшаға «Арнайы мүмкіндіктер» рұқсаты берілді (ContentObserver) → бірден сигналка.
 * Тірі қызметтер (домалақ батырма, SMS қорғанысы) жұмыс істеп тұрғанда — лезде;
 * әйтпесе JobScheduler әр 15 минут сайын тексереді.
 */
object InstallGuard {
    private const val KNOWN = "known_pkgs"
    private const val ALERTED = "alerted_pkgs"
    private const val JOB_ID = 7311
    private const val ALARM_AT = 45

    class Handle(val rx: BroadcastReceiver, val obs: ContentObserver)

    fun register(ctx: Context): Handle? = try {
        val app = ctx.applicationContext
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val pkg = i.data?.schemeSpecificPart ?: return
                if (i.getBooleanExtra(Intent.EXTRA_REPLACING, false) && i.action == Intent.ACTION_PACKAGE_ADDED) return
                Thread { inspect(app, pkg, "install") }.start()
            }
        }
        val f = IntentFilter().apply { addAction(Intent.ACTION_PACKAGE_ADDED); addDataScheme("package") }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(rx, f)
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { Thread { recheck(app, quiet = false) }.start() }
        }
        app.contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, obs)
        Handle(rx, obs)
    } catch (_: Exception) { null }

    fun unregister(ctx: Context, h: Handle?) {
        h ?: return
        try { ctx.applicationContext.unregisterReceiver(h.rx) } catch (_: Exception) {}
        try { ctx.applicationContext.contentResolver.unregisterContentObserver(h.obs) } catch (_: Exception) {}
    }

    /** 15 минут сайынғы фондық тексеру (телефон қайта қосылса да сақталады) */
    fun schedule(ctx: Context) {
        try {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (js.allPendingJobs.any { it.id == JOB_ID }) return
            js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, GuardJob::class.java))
                .setPeriodic(15 * 60_000L).setPersisted(true).build())
        } catch (_: Exception) {}
    }

    private fun set(ctx: Context, k: String): MutableSet<String> =
        Vault.get(ctx, k)?.split('\n')?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()
    private fun save(ctx: Context, k: String, s: Set<String>) = Vault.put(ctx, k, s.take(3000).joinToString("\n"))

    /** Бірінші іске қосылғанда — бар қосымшалар «таныс» деп белгіленеді (бірақ қауіптілері сканерде көрінеді) */
    fun baseline(ctx: Context) { if (Vault.get(ctx, KNOWN) == null) save(ctx, KNOWN, AppScanner.installedPackages(ctx)) }

    /** Жаңа пакеттер мен жаңадан берілген Accessibility рұқсатын тексеру */
    fun recheck(ctx: Context, quiet: Boolean) {
        val now = AppScanner.installedPackages(ctx)
        val known = set(ctx, KNOWN)
        if (known.isEmpty()) { save(ctx, KNOWN, now); return }
        for (p in now - known) inspect(ctx, p, "install", quiet)
        save(ctx, KNOWN, now)
        // Accessibility қосылған қауіпті қосымшалар (бұрын ескертілмеген болса)
        for (f in AppScanner.scan(ctx)) if (f.a11yOn && f.sideloaded) inspect(ctx, f.pkg, "a11y", quiet)
    }

    fun inspect(ctx: Context, pkg: String, why: String, quiet: Boolean = false) {
        val f = AppScanner.check(ctx, pkg) ?: return
        val key = "$pkg|$why|${f.a11yOn}"
        val alerted = set(ctx, ALERTED)
        if (key in alerted || f.score < ALARM_AT) return
        alerted += key; save(ctx, ALERTED, alerted)
        if (quiet) return
        Notify.appAlert(ctx, f, why)
        try { ctx.startActivity(AlarmActivity.appIntent(ctx, f, why)) } catch (_: Exception) {}
    }
}

/** Фондық тексеру (JobScheduler) */
class GuardJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Thread { try { InstallGuard.recheck(applicationContext, quiet = false) } finally { jobFinished(params, false) } }.start()
        return true
    }
    override fun onStopJob(params: JobParameters?) = true
}
