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
    /** Familien-PIN liegt nur verschlüsselt (Android-Keystore) auf dem Gerät. */
    fun pin(ctx: Context): String = prefs(ctx).getString("pinEnc", null)?.let { SecretStore.decrypt(it) } ?: ""

    fun enable(ctx: Context, url: String, pin: String) {
        prefs(ctx).edit().putString("url", url).remove("pin")
            .apply { if (pin.isNotEmpty()) putString("pinEnc", SecretStore.encrypt(pin)) else remove("pinEnc") }.apply()
        start(ctx)
    }

    fun disable(ctx: Context) {
        prefs(ctx).edit().remove("url").remove("pin").remove("pinEnc").apply()
        ctx.stopService(Intent(ctx, PushService::class.java))
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_STATUS)
    }

    fun start(ctx: Context) {
        if (!enabled(ctx)) return
        val i = Intent(ctx, PushService::class.java)
        try {
            ctx.startForegroundService(i)
        } catch (_: Exception) {
            // z. B. Android 12+: Start aus dem Hintergrund nicht erlaubt – beim nächsten Öffnen der App erneut
        }
    }

    fun channels(ctx: Context) {
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

    private fun builder(ctx: Context, channel: String): Notification.Builder = Notification.Builder(ctx, channel)

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
            .setContentIntent(openIntent(ctx, safePath(data.optString("url")), id))
            // Sperrbildschirm: nur „Neue Mitteilung“, Inhalt erst nach dem Entsperren
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(builder(ctx, if (kind == "ring") CH_RING else CH_MSG)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(if (kind == "ring") "🔔 Es klingelt" else "Familienplaner")
                .setContentText("Neue Mitteilung").build())
            .apply {
                if (data.optInt("badge") > 0) setNumber(data.optInt("badge"))
                if (kind == "ring") setCategory(Notification.CATEGORY_CALL)
            }
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, n)
    }

    /** Nur Pfade innerhalb des Planers („/…“), keine fremden Adressen aus einer Mitteilung öffnen. */
    private fun safePath(p: String): String? =
        p.takeIf { it.startsWith("/") && !it.startsWith("//") && !it.contains('\\') && it.length < 500 }

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
