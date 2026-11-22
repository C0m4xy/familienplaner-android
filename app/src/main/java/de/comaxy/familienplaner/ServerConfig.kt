package de.comaxy.familienplaner

import android.content.SharedPreferences
import android.net.Uri

/**
 * Wohin sich die App verbindet:
 * - Cloud-Server (famplan.comaxy.de) – direkt die Anmeldung der Webseite, ohne Cloudflare-Logik
 * - Eigener Server über Cloudflare Access – Anmeldung per E-Mail-Code (wie bisher)
 * - Eigener Server lokal – Adresse + Port im Heimnetz, direkt
 */
class ServerConfig(private val prefs: SharedPreferences) {

    enum class Mode(val key: String) { CLOUD("cloud"), ACCESS("access"), LOCAL("local") }

    var mode: Mode
        get() = Mode.values().firstOrNull { it.key == prefs.getString("serverMode", null) } ?: Mode.CLOUD
        set(v) = prefs.edit().putString("serverMode", v.key).apply()
    var accessUrl: String
        get() = prefs.getString("accessUrl", "") ?: ""
        set(v) = prefs.edit().putString("accessUrl", v).apply()
    var localServer: String
        get() = prefs.getString("localServer", "") ?: ""
        set(v) = prefs.edit().putString("localServer", v).apply()
    var port: String
        get() = prefs.getString("port", "") ?: ""
        set(v) = prefs.edit().putString("port", v).apply()
    var email: String
        get() = prefs.getString("email", "") ?: ""
        set(v) = prefs.edit().putString("email", v).apply()

    /** Adresse, die geöffnet wird. */
    val targetUrl: String?
        get() = urlFor(mode, accessUrl, localServer, port)

    /** Fehlertext für die Eingaben (oder null, wenn alles passt). */
    fun problem(mode: Mode, accessUrl: String, localServer: String, port: String, email: String): String? =
        when (mode) {
            Mode.CLOUD -> null
            Mode.ACCESS -> when {
                buildUrl(accessUrl, "", "https") == null -> "Bitte eine gültige Adresse eingeben."
                !emailIsValid(email) -> "Bitte eine gültige E-Mail-Adresse eingeben."
                else -> null
            }
            Mode.LOCAL -> when {
                port.isNotBlank() && port.trim().toIntOrNull()?.takeIf { it in 1..65535 } == null ->
                    "Der Port muss eine Zahl von 1 bis 65535 sein."
                buildUrl(localServer, port, "http") == null -> "Bitte eine gültige Server-Adresse eingeben."
                else -> null
            }
        }

    companion object {
        const val CLOUD_URL = "https://famplan.comaxy.de/"

        fun emailIsValid(e: String) = e.trim().let { it.contains("@") && it.contains(".") && !it.contains(" ") }

        fun urlFor(mode: Mode, accessUrl: String, localServer: String, port: String): String? = when (mode) {
            Mode.CLOUD -> CLOUD_URL
            Mode.ACCESS -> buildUrl(accessUrl, "", "https")
            Mode.LOCAL -> buildUrl(localServer, port, "http")
        }

        /** Baut aus „familie.de“, „192.168.1.20“ o. ä. plus Port eine vollständige Adresse. */
        fun buildUrl(server: String, port: String, defaultScheme: String): String? {
            var text = server.trim()
            if (text.isEmpty() || text.contains(' ')) return null
            val lower = text.lowercase()
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) text = "$defaultScheme://$text"
            val uri = Uri.parse(text)
            val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
            // „10“ o. ä. ist keine sinnvolle Adresse (würde als 0.0.0.10 gelesen)
            if (host.all { it.isDigit() }) return null
            if (host.all { it.isDigit() || it == '.' } && host.split('.').size != 4) return null
            val scheme = uri.scheme!!.lowercase()
            val p = port.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: uri.port.takeIf { it > 0 }
            val hostPart = if (host.contains(':')) "[$host]" else host   // IPv6
            val portPart = when {
                p == null -> ""
                scheme == "https" && p == 443 -> ""
                scheme == "http" && p == 80 -> ""
                else -> ":$p"
            }
            val path = uri.encodedPath?.takeIf { it.isNotEmpty() } ?: "/"
            return "$scheme://$hostPart$portPart$path"
        }
    }
}
