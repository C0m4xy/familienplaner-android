package de.comaxy.familienplaner

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Hält im Hintergrund eine Verbindung zum Planer (/api/push-stream, Server-Sent Events)
 * und zeigt eintreffende Mitteilungen an. Als Vordergrund-Dienst mit kleiner Dauer-Mitteilung,
 * damit Android ihn nicht beendet. Bricht die Verbindung ab, wird sie neu aufgebaut.
 */
class PushService : Service() {

    private enum class Result { CLOSED, NETWORK, AUTH, GONE, LATER }

    @Volatile private var running = false
    @Volatile private var conn: HttpURLConnection? = null
    private var thread: Thread? = null
    private var status = ""

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        Push.channels(this)
        foreground("Verbindung wird aufgebaut …")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        foreground(status.ifEmpty { "Verbindung wird aufgebaut …" })
        if (!Push.enabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (thread?.isAlive != true) {
            running = true
            thread = Thread({ loop() }, "familienplaner-push").also { it.start() }
        } else {
            conn?.disconnect()   // z. B. neue Adresse/PIN → sofort neu verbinden
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        conn?.disconnect()
        thread?.interrupt()
        super.onDestroy()
    }

    private fun foreground(text: String) {
        status = text
        val n = Push.statusNotification(this, text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Push.ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(Push.ID_STATUS, n)
        }
    }

    private fun setStatus(text: String) {
        if (text == status || !running) return
        status = text
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(Push.ID_STATUS, Push.statusNotification(this, text))
    }

    private fun loop() {
        var wait = 3_000L
        var loginHintShown = false
        while (running && Push.enabled(this)) {
            val result = try {
                read()
            } catch (_: InterruptedException) {
                return
            } catch (_: Exception) {
                Result.NETWORK
            }
            if (!running) return
            when (result) {
                Result.GONE -> {   // auf dem Server abgemeldet
                    Push.disable(this)
                    stopSelf()
                    return
                }
                Result.AUTH -> {
                    if (!loginHintShown) Push.showLoginHint(this)
                    loginHintShown = true
                    setStatus("Anmeldung abgelaufen – bitte App öffnen")
                    wait = 10 * 60_000L
                }
                Result.LATER -> {
                    setStatus("Planer gerade nicht bereit – neuer Versuch gleich")
                    wait = 5 * 60_000L
                }
                Result.CLOSED -> {
                    loginHintShown = false
                    wait = 3_000L
                }
                Result.NETWORK -> {
                    setStatus("Keine Verbindung – neuer Versuch läuft")
                    wait = minOf(wait * 2, 30_000L)
                }
            }
            try {
                Thread.sleep(wait)
            } catch (_: InterruptedException) {
                return
            }
        }
        stopSelf()
    }

    /** Eine Verbindung: lesen, bis der Server sie schließt oder sie abbricht. */
    private fun read(): Result {
        val address = Push.url(this) ?: return Result.GONE
        val c = URL(address).openConnection() as HttpURLConnection
        conn = c
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 90_000   // Server schickt alle 25 s ein Lebenszeichen
            c.setRequestProperty("Accept", "text/event-stream")
            c.setRequestProperty("Cache-Control", "no-cache")
            c.setRequestProperty("x-pin", Push.pin(this))
            cookies(address)?.let { c.setRequestProperty("Cookie", it) }
            userAgent()?.let { c.setRequestProperty("User-Agent", it) }

            val code = c.responseCode
            when {
                code == 200 -> Unit
                code == 404 -> {
                    val body = try { c.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
                    return if (body.contains("gone")) Result.GONE else Result.LATER   // „gone“ = abgemeldet, sonst alter Server
                }
                code == 401 || code == 403 || code in 300..399 -> return Result.AUTH
                code == 423 || code == 503 -> return Result.LATER
                else -> return Result.NETWORK
            }
            Push.clearLoginHint(this)
            setStatus("Mitteilungen aktiv")

            val reader = BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8))
            var event = ""
            val data = StringBuilder()
            while (running) {
                val line = reader.readLine() ?: break
                when {
                    line.isEmpty() -> {
                        if (event == "push" && data.isNotEmpty()) {
                            try { Push.show(this, JSONObject(data.toString())) } catch (_: Exception) {}
                        }
                        event = ""
                        data.setLength(0)
                    }
                    line.startsWith(":") -> Unit   // Lebenszeichen
                    line.startsWith("event:") -> event = line.substring(6).trim()
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.substring(5).trimStart())
                    }
                }
            }
            return Result.CLOSED
        } finally {
            c.disconnect()
            conn = null
        }
    }

    /** Cookies (Cloudflare Access bzw. Anmeldung) aus dem Speicher der App – CookieManager nur auf dem Hauptthread. */
    private fun cookies(address: String): String? = onMain { CookieManager.getInstance().getCookie(address) }

    private fun userAgent(): String? = onMain {
        WebSettings.getDefaultUserAgent(this).replace("; wv)", ")").replace(Regex(" Version/\\d+(\\.\\d+)*"), "")
    }

    private fun <T> onMain(block: () -> T): T? {
        var value: T? = null
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try { value = block() } catch (_: Exception) {} finally { done.countDown() }
        }
        done.await(10, TimeUnit.SECONDS)
        return value
    }
}
