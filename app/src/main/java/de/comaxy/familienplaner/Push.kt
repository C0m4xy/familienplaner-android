package de.comaxy.familienplaner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Mitteilungen ohne Web-Push: Der eingebettete Browser kann kein Web-Push, daher hält
 * [PushService] eine eigene Verbindung zum Planer (/api/push-stream) und zeigt die
 * Mitteilungen als normale Android-Mitteilungen an. Kein Google/Firebase nötig.
 */
object Push {
    private const val PREFS = "push"
    const val CH_STATUS = "verbindung"
    const val CH_MSG = "mitteilungen"
    const val CH_RING = "tuerklingel"
    const val ID_STATUS = 1
    const val ID_LOGIN = 2
    const val EXTRA_OPEN = "open"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Feste Kennung dieses Geräts, z. B. „app:3f9c…“ (32 Hex-Zeichen). */
    fun device(ctx: Context): String {
        prefs(ctx).getString("device", null)?.let { return it }
        val b = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val id = "app:" + b.joinToString("") { "%02x".format(it) }
        prefs(ctx).edit().putString("device", id).apply()
        return id
    }

    fun enabled(ctx: Context) = prefs(ctx).getString("url", null) != null
    fun url(ctx: Context): String? = prefs(ctx).getString("url", null)
    fun pin(ctx: Context): String = prefs(ctx).getString("pin", "") ?: ""

    fun enable(ctx: Context, url: String, pin: String) {
        prefs(ctx).edit().putString("url", url).putString("pin", pin).apply()
        start(ctx)
    }

    fun disable(ctx: Context) {
        prefs(ctx).edit().remove("url").remove("pin").apply()
        ctx.stopService(Intent(ctx, PushService::class.java))
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_STATUS)
    }

    fun start(ctx: Context) {
        if (!enabled(ctx)) return
        val i = Intent(ctx, PushService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (_: Exception) {
            // z. B. Android 12+: Start aus dem Hintergrund nicht erlaubt – beim nächsten Öffnen der App erneut
        }
    }

    fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Verbindung zum Planer", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Dauerhafte Anzeige, solange die App auf Mitteilungen wartet"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_MSG, "Mitteilungen", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Erinnerungen, neue Aufgaben, Termine und Pinnwand-Einträge"
        })
        nm.createNotificationChannel(NotificationChannel(CH_RING, "Türklingel", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Es klingelt an der Tür"
        })
    }

    private fun builder(ctx: Context, channel: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, channel)
        else @Suppress("DEPRECATION") Notification.Builder(ctx)

    /** Öffnet die App (optional mit Pfad im Planer, z. B. „/?ring=…“). */
    private fun openIntent(ctx: Context, path: String?, code: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (!path.isNullOrBlank()) i.putExtra(EXTRA_OPEN, path)
        return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun statusNotification(ctx: Context, text: String): Notification =
        builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Familienplaner verbunden")
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openIntent(ctx, null, 0))
            .apply { @Suppress("DEPRECATION") setPriority(Notification.PRIORITY_MIN) }
            .build()

    /** Zeigt eine Mitteilung vom Planer an: {title, body, tag, url, kind, badge}. */
    fun show(ctx: Context, data: JSONObject) {
        val kind = data.optString("kind")
        val tag = data.optString("tag").ifEmpty { data.optString("title") + data.optLong("at") }
        val id = 1000 + (tag.hashCode() and 0x3fffffff)
        val n = builder(ctx, if (kind == "ring") CH_RING else CH_MSG)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(data.optString("title", "Familienplaner"))
            .setContentText(data.optString("body"))
            .setStyle(Notification.BigTextStyle().bigText(data.optString("body")))
            .setAutoCancel(true)
            .setContentIntent(openIntent(ctx, data.optString("url").ifEmpty { null }, id))
            .apply {
                if (data.optInt("badge") > 0) setNumber(data.optInt("badge"))
                if (kind == "ring") setCategory(Notification.CATEGORY_CALL)
                @Suppress("DEPRECATION") setPriority(Notification.PRIORITY_HIGH)
                @Suppress("DEPRECATION") setDefaults(Notification.DEFAULT_ALL)
            }
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, n)
    }

    /** Die Anmeldung beim Planer ist abgelaufen – ohne neue Anmeldung kommen keine Mitteilungen. */
    fun showLoginHint(ctx: Context) {
        val n = builder(ctx, CH_MSG)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Familienplaner: bitte neu anmelden")
            .setContentText("Die Anmeldung ist abgelaufen – bis dahin kommen keine Mitteilungen.")
            .setAutoCancel(true)
            .setContentIntent(openIntent(ctx, null, ID_LOGIN))
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID_LOGIN, n)
    }

    fun clearLoginHint(ctx: Context) =
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_LOGIN)
}
