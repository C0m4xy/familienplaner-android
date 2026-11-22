package de.comaxy.familienplaner

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * Meldet sich im Hintergrund bei Cloudflare Access an (wie die macOS-App).
 *
 * Ein Browser (WebView), der unter der Anmeldemaske liegt, öffnet die Adresse, landet auf
 * der Anmeldeseite von Cloudflare Access, trägt dort die E-Mail-Adresse ein und später den
 * Code aus der E-Mail. Das Anmelde-Cookie (`CF_Authorization`) landet im gemeinsamen
 * Cookie-Speicher der App und gilt danach auch für den Planer.
 */
class AccessLogin(context: Context) {

    enum class StartResult { ALREADY_LOGGED_IN, CODE_SENT, NEEDS_LOGIN }

    class LoginError(message: String) : Exception(message) {
        companion object {
            fun loginPageNotFound() =
                LoginError("Die Anmeldeseite von Cloudflare Access wurde nicht gefunden. Ist die Adresse richtig?")
            fun emailRejected(text: String) =
                LoginError(text.ifEmpty { "Die E-Mail-Adresse wurde nicht angenommen." })
            fun codeRejected(text: String) =
                LoginError(text.ifEmpty { "Der Code ist falsch oder abgelaufen." })
            fun timeout(what: String) = LoginError("Zeitüberschreitung: $what")
            fun network(text: String) = LoginError("Verbindungsfehler: $text")
        }
    }

    val webView: WebView = WebView(context)
    private var targetHost = ""
    private var finishedLoads = 0
    private var isLoading = false
    private var lastNetworkError: String? = null

    init {
        configure(webView)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                isLoading = true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                isLoading = false
                finishedLoads += 1
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) lastNetworkError = error.description?.toString() ?: "unbekannt"
            }
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
    }

    // region Ablauf

    /** Schritt 1: Adresse öffnen und – falls nötig – den Code an die E-Mail-Adresse schicken lassen. */
    suspend fun start(url: String, email: String, sendCode: Boolean = true): StartResult {
        targetHost = hostOf(url)
        lastNetworkError = null
        isLoading = true
        webView.loadUrl(url, mapOf("Cache-Control" to "no-cache"))

        // Warten, bis entweder die Webseite selbst oder die Anmeldeseite erscheint.
        var clickedOtpButton = false
        val deadline = now() + 30_000
        while (now() < deadline) {
            pause()
            lastNetworkError?.let { throw LoginError.network(it) }
            val page = probe()
            if (isOnTarget(page)) return StartResult.ALREADY_LOGGED_IN
            if (page.code) {
                // Cloudflare zeigt direkt die Code-Eingabe (z. B. Code schon angefordert)
                return if (sendCode) StartResult.CODE_SENT else StartResult.NEEDS_LOGIN
            }
            if (page.email) {
                if (!sendCode) return StartResult.NEEDS_LOGIN
                val loadsBefore = finishedLoads
                fillAndSubmit(EMAIL_SELECTOR, email)
                return waitForCodeField(loadsBefore)
            }
            if (page.otpButton && !clickedOtpButton) {
                // Mehrere Anmeldearten: „One-time PIN“ auswählen
                clickedOtpButton = true
                eval(CLICK_OTP_BUTTON_JS)
            }
        }
        throw LoginError.loginPageNotFound()
    }

    /** Schritt 2: Code eintragen und Anmeldung abschließen. */
    suspend fun submit(code: String) {
        lastNetworkError = null
        val loadsBefore = finishedLoads
        val result = fillAndSubmit(CODE_SELECTOR, code)
        if (result == "nofield") {
            throw LoginError.codeRejected("Die Code-Eingabe ist nicht mehr da. Bitte neuen Code anfordern.")
        }
        val deadline = now() + 30_000
        while (now() < deadline) {
            pause()
            lastNetworkError?.let { throw LoginError.network(it) }
            val page = probe()
            if (isOnTarget(page)) {
                CookieManager.getInstance().flush()
                return
            }
            val settled = finishedLoads > loadsBefore && page.ready && !isLoading
            if (settled && page.code) throw LoginError.codeRejected(page.error)
            if (settled && page.email) {
                // Zurück auf der E-Mail-Seite: Code abgelaufen o. ä.
                throw LoginError.codeRejected(page.error.ifEmpty { "Der Code ist abgelaufen. Bitte neuen Code anfordern." })
            }
        }
        throw LoginError.timeout("Cloudflare Access hat die Anmeldung nicht bestätigt.")
    }

    private suspend fun waitForCodeField(loadsBefore: Int): StartResult {
        val deadline = now() + 30_000
        while (now() < deadline) {
            pause()
            lastNetworkError?.let { throw LoginError.network(it) }
            val page = probe()
            if (page.code) return StartResult.CODE_SENT
            if (isOnTarget(page)) return StartResult.ALREADY_LOGGED_IN
            if (finishedLoads > loadsBefore && page.ready && !isLoading && page.email) {
                throw LoginError.emailRejected(page.error)
            }
        }
        throw LoginError.timeout("Cloudflare Access hat keinen Code verschickt.")
    }

    /**
     * Nach erfolgreicher Anmeldung: Seite im verdeckten Browser schließen,
     * damit der Planer nicht doppelt läuft (Akku, doppelter Türklingel-Ton).
     */
    fun unload() {
        webView.stopLoading()
        webView.loadUrl("about:blank")
    }

    /** Steht der (sichtbar gemachte) Anmelde-Browser schon auf dem Planer? */
    fun isOnTargetNow(targetUrl: String): Boolean {
        val current = webView.url ?: return false
        return hostOf(current) == hostOf(targetUrl) && !pathOf(current).startsWith("/cdn-cgi/access")
    }

    private fun isOnTarget(page: PageState) =
        page.ready && !isLoading && page.host == targetHost && !page.path.startsWith("/cdn-cgi/access")

    private suspend fun pause() = delay(400)
    private fun now() = System.currentTimeMillis()

    // endregion

    // region Seite auslesen und ausfüllen

    private data class PageState(
        val host: String = "",
        val path: String = "",
        val ready: Boolean = false,
        val email: Boolean = false,
        val code: Boolean = false,
        val otpButton: Boolean = false,
        val error: String = "",
    )

    private suspend fun probe(): PageState {
        val json = eval(call(PROBE_JS, EMAIL_SELECTOR, CODE_SELECTOR)) ?: return PageState()
        return try {
            val o = JSONObject(json)
            PageState(
                host = o.optString("host"),
                path = o.optString("path"),
                ready = o.optBoolean("ready"),
                email = o.optBoolean("email"),
                code = o.optBoolean("code"),
                otpButton = o.optBoolean("otpButton"),
                error = o.optString("error"),
            )
        } catch (_: Exception) {
            PageState()
        }
    }

    private suspend fun fillAndSubmit(selector: String, value: String): String =
        eval(call(FILL_JS, selector, value)) ?: ""

    /** Führt JavaScript aus und liefert einen zurückgegebenen Text (oder null). */
    private suspend fun eval(js: String): String? = suspendCancellableCoroutine { cont ->
        webView.evaluateJavascript(js) { raw ->
            val value = try {
                JSONTokener(raw ?: "null").nextValue()
            } catch (_: Exception) {
                null
            }
            if (cont.isActive) cont.resume(value?.let { if (it == JSONObject.NULL) null else it.toString() })
        }
    }

    // endregion

    companion object {
        const val EMAIL_SELECTOR = "input[type=email], input[name=email]"
        const val CODE_SELECTOR =
            "input[name=code], input[autocomplete=one-time-code], input[name=otp], input[name=pin]"

        fun hostOf(url: String): String = android.net.Uri.parse(url).host?.lowercase() ?: ""
        fun pathOf(url: String): String = android.net.Uri.parse(url).path ?: ""

        /** Ruft einen Funktionsrumpf mit zwei Text-Argumenten auf (a, b). */
        private fun call(body: String, a: String, b: String) =
            "(function(a, b){\n$body\n})(${JSONObject.quote(a)}, ${JSONObject.quote(b)})"

        private const val PROBE_JS = """
            const emailSel = a, codeSel = b;
            const usable = el => el && el.type !== 'hidden' && !el.disabled && el.getClientRects().length > 0;
            const find = sel => Array.from(document.querySelectorAll(sel)).find(usable);
            const codeEl = find(codeSel);
            const emailEl = find(emailSel);
            const otpButton = Array.from(document.querySelectorAll('button, a')).some(b =>
                /one-time pin|one time pin|einmal|otp/i.test(b.innerText || ''));
            const errEl = Array.from(document.querySelectorAll('[role=alert], .error, .alert, [class*=error], [class*=Error]'))
                .find(e => (e.innerText || '').trim().length > 0);
            return JSON.stringify({
                host: location.hostname.toLowerCase(),
                path: location.pathname,
                ready: document.readyState === 'complete',
                email: !!emailEl,
                code: !!codeEl,
                otpButton: otpButton,
                error: errEl ? errEl.innerText.trim().slice(0, 300) : ''
            });
        """

        private const val FILL_JS = """
            const sel = a, value = b;
            const el = Array.from(document.querySelectorAll(sel))
                .find(e => e.type !== 'hidden' && !e.disabled && e.getClientRects().length > 0);
            if (!el) return 'nofield';
            const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
            el.focus();
            setter.call(el, value);
            el.dispatchEvent(new Event('input', { bubbles: true }));
            el.dispatchEvent(new Event('change', { bubbles: true }));
            const form = el.form || el.closest('form');
            if (form) {
                const btn = form.querySelector('button[type=submit], input[type=submit], button:not([type])');
                if (btn) { btn.click(); return 'clicked'; }
                if (form.requestSubmit) form.requestSubmit(); else form.submit();
                return 'submitted';
            }
            const btn = Array.from(document.querySelectorAll('button'))
                .find(b => /code|send|sign|anmelden|senden|weiter|continue/i.test(b.innerText || ''));
            if (btn) { btn.click(); return 'clicked'; }
            el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true }));
            return 'enter';
        """

        private const val CLICK_OTP_BUTTON_JS = """
            (function(){
            const b = Array.from(document.querySelectorAll('button, a'))
                .find(b => /one-time pin|one time pin|einmal|otp/i.test(b.innerText || ''));
            if (b) b.click();
            return !!b;
            })()
        """

        /** Gemeinsame Browser-Einstellungen für Anmeldung und Planer. */
        @SuppressLint("SetJavaScriptEnabled")
        fun configure(view: WebView) {
            with(view.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false   // Türklingel-Ton/-Video
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportMultipleWindows(false)          // target="_blank" im selben Fenster
                // Härtung: keine lokalen Dateien/Inhalte, keine unsicheren Inhalte auf https-Seiten
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setGeolocationEnabled(false)
                safeBrowsingEnabled = true
                // Normale Chrome-Kennung (ohne „wv“), damit Cloudflare die normale Seite liefert.
                userAgentString = userAgentString
                    .replace("; wv)", ")")
                    .replace(Regex(" Version/\\d+(\\.\\d+)*"), "")
            }
            // Passkeys: WebAuthn-Anfragen im Namen der App erlauben. Die Website muss die App dafür in
            // /.well-known/assetlinks.json freigeben. Geht nur mit aktuellem WebView und ab Android 9.
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.WEB_AUTHENTICATION)) {
                androidx.webkit.WebSettingsCompat.setWebAuthenticationSupport(
                    view.settings, androidx.webkit.WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP)
            }
        }

        /** Ab dieser Chrome-Version läuft der Planer (moderne JavaScript-Syntax). */
        const val MIN_CHROME = 80
        /** Ab dieser Version stimmen auch alle Farben (CSS color-mix). */
        const val FULL_CHROME = 111

        /** Hauptversion des eingebauten Browser-Bausteins (Android System WebView), z. B. 106. */
        fun webViewChromeVersion(context: Context): Int? = try {
            Regex("Chrome/(\\d+)").find(android.webkit.WebSettings.getDefaultUserAgent(context))
                ?.groupValues?.get(1)?.toIntOrNull()
        } catch (_: Exception) {
            null
        }

        /** Löscht Cookies und Daten der Webseite und von Cloudflare Access. */
        fun logout() {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
    }
}
