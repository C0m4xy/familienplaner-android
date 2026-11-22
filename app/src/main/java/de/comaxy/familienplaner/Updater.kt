package de.comaxy.familienplaner

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Automatische Updates aus dem öffentlichen GitHub-Repository:
 * Die neueste Release enthält `update.json` ({versionCode, versionName, apk, sha256, notes}) und die APK.
 * Ist die Version neuer, wird die APK geladen, per SHA-256 geprüft und über den PackageInstaller installiert.
 * Android lässt nur Updates zu, die mit demselben Schlüssel signiert sind wie die installierte App.
 */
object Updater {
    const val REPO = "C0m4xy/familienplaner-android"
    private const val LATEST = "https://github.com/$REPO/releases/latest/download/"
    private const val CHECK_EVERY = 12 * 3600_000L
    const val ACTION_DONE = "de.comaxy.familienplaner.UPDATE_DONE"

    /** Update, das auf die Installations-Erlaubnis wartet – wird nach der Rückkehr fortgesetzt. */
    var pending: Release? = null

    data class Release(val code: Int, val name: String, val apk: String, val sha256: String, val notes: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("update", Context.MODE_PRIVATE)

    @Suppress("DEPRECATION")
    fun currentCode(ctx: Context) = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
    fun currentName(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** Fällig? (beim Start und danach höchstens alle 12 Stunden) */
    fun due(ctx: Context) = System.currentTimeMillis() - prefs(ctx).getLong("checkedAt", 0) > CHECK_EVERY

    /** Neueste Release lesen; null = keine neuere Version (oder nicht erreichbar). */
    suspend fun check(ctx: Context): Release? = withContext(Dispatchers.IO) {
        prefs(ctx).edit().putLong("checkedAt", System.currentTimeMillis()).apply()
        val json = JSONObject(String(fetch(LATEST + "update.json", 64 * 1024), Charsets.UTF_8))
        val r = Release(json.getInt("versionCode"), json.optString("versionName"), json.getString("apk"),
            json.getString("sha256").lowercase(), json.optString("notes"))
        val minSdk = json.optInt("minSdk", 0)   // Gerät zu alt für die neue Version → nicht anbieten
        if (r.code > currentCode(ctx) && Build.VERSION.SDK_INT >= minSdk && r.apk.matches(Regex("[A-Za-z0-9._-]+\\.apk"))) r else null
    }

    /** APK laden, Prüfsumme vergleichen und Installation starten. */
    suspend fun install(activity: Activity, r: Release) {
        val bytes = withContext(Dispatchers.IO) { fetch(LATEST + r.apk, 100 * 1024 * 1024) }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (hash != r.sha256) throw IllegalStateException("Die heruntergeladene Datei ist beschädigt (Prüfsumme stimmt nicht).")
        withContext(Dispatchers.IO) { verifySignature(activity, bytes, r.code) }
        withContext(Dispatchers.IO) {
            val pi = activity.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(activity.packageName)
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                s.openWrite("familienplaner.apk", 0, bytes.size.toLong()).use { it.write(bytes); s.fsync(it) }
                val i = Intent(activity, UpdateReceiver::class.java).setAction(ACTION_DONE)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE   // System trägt das Ergebnis ein
                s.commit(PendingIntent.getBroadcast(activity, id, i, flags).intentSender)
            }
        }
    }

    /** Der Nutzer muss der App einmal erlauben, Apps zu installieren. */
    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(activity: Activity) = activity.startActivity(
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))

    /**
     * Doppelte Absicherung vor dem Installieren: gleiches Paket, höhere Version und
     * derselbe Signierschlüssel wie die installierte App (Android prüft das zusätzlich selbst).
     */
    private fun verifySignature(ctx: Context, apk: ByteArray, expectedCode: Int) {
        val f = java.io.File(ctx.cacheDir, "update-check.apk")
        try {
            f.writeBytes(apk)
            val pm = ctx.packageManager
            val flags = android.content.pm.PackageManager.PackageInfoFlags.of(android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            val new = pm.getPackageArchiveInfo(f.path, flags) ?: throw IllegalStateException("Update-Datei ist keine gültige App.")
            val own = pm.getPackageInfo(ctx.packageName, flags)
            if (new.packageName != ctx.packageName) throw IllegalStateException("Update gehört zu einer anderen App.")
            if (new.longVersionCode.toInt() != expectedCode) throw IllegalStateException("Update hat eine unerwartete Version.")
            val certs = { p: android.content.pm.PackageInfo ->
                (p.signingInfo?.apkContentsSigners ?: emptyArray()).map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toList() }.toSet() }
            val a = certs(new); val b = certs(own)
            if (a.isEmpty() || a != b) throw IllegalStateException("Update ist nicht mit dem Schlüssel der App signiert – Installation abgebrochen.")
        } finally {
            f.delete()
        }
    }

    /** Erlaubte Adressen für Update-Downloads (GitHub-Releases und dessen Datei-Server). */
    private fun allowedHost(h: String) = h == "github.com" || h.endsWith(".githubusercontent.com")

    private fun fetch(address: String, max: Int): ByteArray {
        var url = URL(address)
        repeat(5) {   // GitHub leitet zu objects.githubusercontent.com weiter
            // nur https und nur GitHub – auch nach Weiterleitungen
            if (url.protocol != "https" || !allowedHost(url.host.lowercase())) throw IllegalStateException("Unerwartete Update-Adresse")
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "Familienplaner-Android")
            try {
                val code = c.responseCode
                if (code in 300..399) { url = URL(url, c.getHeaderField("Location")); return@repeat }
                if (code != 200) throw IllegalStateException("Update-Server meldet $code")
                val out = java.io.ByteArrayOutputStream()
                c.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > max) throw IllegalStateException("Update zu groß")
                    }
                }
                return out.toByteArray()
            } finally {
                c.disconnect()
            }
        }
        throw IllegalStateException("Zu viele Weiterleitungen")
    }

    /** Ergebnis der Installation (bzw. Bitte um Bestätigung durch den Nutzer). */
    class UpdateReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> Unit   // App wird neu gestartet (MY_PACKAGE_REPLACED)
                PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
                else -> Toast.makeText(ctx, "Update fehlgeschlagen: " +
                    (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unbekannt"), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Hinweis-Dialog mit „Jetzt aktualisieren“. */
    fun offer(activity: MainActivity, r: Release, run: (suspend () -> Unit) -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("Update verfügbar: Version ${r.name}")
            .setMessage((if (r.notes.isNotBlank()) r.notes + "\n\n" else "") + "Installiert ist Version ${currentName(activity)}.")
            .setPositiveButton("Jetzt aktualisieren") { _, _ ->
                if (!canInstall(activity)) {
                    AlertDialog.Builder(activity)
                        .setTitle("Einmalige Erlaubnis nötig")
                        .setMessage("Damit sich die App selbst aktualisieren kann, bitte im nächsten Schritt „Aus dieser Quelle zulassen“ einschalten und danach zurückkehren.")
                        .setPositiveButton("Weiter") { _, _ -> pending = r; openInstallPermission(activity) }
                        .setNegativeButton("Abbrechen", null).show()
                    return@setPositiveButton
                }
                Toast.makeText(activity, "Update wird geladen …", Toast.LENGTH_SHORT).show()
                run { install(activity, r) }
            }
            .setNegativeButton("Später", null)
            .show()
    }
}
