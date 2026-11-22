package de.comaxy.familienplaner

import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.os.Message
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * Anzeige des Planers: Navigation, Dialoge (alert/confirm/prompt übernimmt Android selbst),
 * Datei-Auswahl, Downloads (auch `blob:`-Downloads wie die Datensicherung) und Links.
 *
 * Sicherheit: Die Brücke zur App (`window.FamilienplanerAndroid`) gibt es nur auf Seiten des
 * eingestellten Planers ([origin], z. B. „https://famplan.comaxy.de“) – fremde Seiten und
 * eingebettete fremde Inhalte erreichen sie nicht (addWebMessageListener mit Herkunftsregel).
 */
class PlannerBrowser(private val activity: MainActivity, private val origin: String) {

    var onSessionExpired: () -> Unit = {}
    var onPageColor: (Int) -> Unit = {}
    var onRenderProcessGone: () -> Unit = {}
    var onChangeServer: () -> Unit = {}
    private var failedUrl: String? = null

    val webView: WebView = WebView(activity)
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    /** Datei, die gerade über „Speichern unter“ abgelegt wird. */
    private var pendingSave: ByteArray? = null

    init {
        AccessLogin.configure(webView)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.setBackgroundColor(Color.TRANSPARENT)
        installBridge()
        webView.webViewClient = Client()
        webView.webChromeClient = Chrome()
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            download(url, contentDisposition, mimeType)
        }
    }

    fun open(url: String) = webView.loadUrl(url)

    /** Antwort an die Seite (Ergebnis von pushEnable). */
    fun pushResult(ok: Boolean) {
        refreshInfo()
        webView.evaluateJavascript("window.fpPushDone&&window.fpPushDone($ok)", null)
    }

    // region Brücke zur Seite (nur eigener Planer)

    private var infoScript: ScriptHandler? = null
    private val originHost = AccessLogin.hostOf(origin)

    private fun installBridge() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return   // sehr altes WebView: ohne Brücke
        WebViewCompat.addWebMessageListener(webView, "FamilienplanerBridge", setOf(origin)) { _, message, source, isMainFrame, _ ->
            if (isMainFrame && source.toString().trimEnd('/') == origin) onBridgeMessage(message)
        }
        refreshInfo()
    }

    /** Stellt `window.FamilienplanerAndroid` bereit; pushInfo() liefert den Stand beim Laden der Seite. */
    private fun refreshInfo() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val info = JSONObject().put("device", Push.device(activity)).put("enabled", Push.enabled(activity)).toString()
        val js = """(function(){ if (window.top !== window) return; let info = ${JSONObject.quote(info)};
            const send = o => FamilienplanerBridge.postMessage(JSON.stringify(o));
            window.FamilienplanerAndroid = Object.freeze({
              pushInfo: () => info,
              pushEnable: (url, pin) => send({ a: 'pushEnable', url: String(url), pin: String(pin || '') }),
              pushDisable: () => { info = info.replace('"enabled":true', '"enabled":false'); send({ a: 'pushDisable' }); },
              saveFile: (name, type, data) => send({ a: 'saveFile', name: String(name), type: String(type), data: String(data) })
            }); })();"""
        infoScript?.remove()
        infoScript = WebViewCompat.addDocumentStartJavaScript(webView, js, setOf(origin))
    }

    private fun onBridgeMessage(message: WebMessageCompat) {
        val o = try { JSONObject(message.data ?: return) } catch (_: Exception) { return }
        when (o.optString("a")) {
            "pushEnable" -> activity.enablePush(o.optString("url"), o.optString("pin"))
            "pushDisable" -> { Push.disable(activity); refreshInfo() }
            "saveFile" -> {
                val bytes = try { Base64.decode(o.optString("data"), Base64.DEFAULT) } catch (_: IllegalArgumentException) { return }
                if (bytes.size > 50 * 1024 * 1024) return
                askWhereToSave(o.optString("name").take(120).ifBlank { "download" }, o.optString("type").take(100), bytes)
            }
        }
    }

    // endregion

    fun reload() {
        failedUrl?.let { failedUrl = null; webView.loadUrl(it); return }
        val url = webView.url
        if (url.isNullOrEmpty() || url == "about:blank") return
        webView.reload()
    }

    fun goBack(): Boolean {
        if (!webView.canGoBack()) return false
        webView.goBack()
        return true
    }

    fun destroy() {
        webView.stopLoading()
        webView.destroy()
    }

    // region Navigation

    private inner class Client : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            val host = url.host?.lowercase() ?: ""
            val scheme = url.scheme?.lowercase() ?: ""

            // Knöpfe der eigenen Fehlerseite
            if (host == ERROR_HOST) {
                when (url.path) { "/retry" -> reload(); "/change" -> onChangeServer() }
                return true
            }

            // Umleitung zur Anmeldeseite = Anmeldung abgelaufen → wieder die native Anmeldung zeigen
            if (host.endsWith("cloudflareaccess.com") && request.isForMainFrame) {
                onSessionExpired()
                return true
            }
            // mailto:, tel:, webcal: (→ Kalender-App) … – nur bekannte Schemata, nie intent:/file:/content:
            if (scheme !in listOf("http", "https", "about", "blob", "data")) {
                if (scheme in EXTERNAL_SCHEMES) openExternally(url)
                return true
            }
            // Unverschlüsselt (http) nur im Heimnetz – sonst nicht in der App laden
            if (scheme == "http" && request.isForMainFrame && !ServerConfig.isPrivateHost(host)) {
                openExternally(url)
                return true
            }
            // Links zu fremden Seiten im Browser öffnen (nur bei Klicks)
            val current = view.url?.let { AccessLogin.hostOf(it) } ?: ""
            if (request.hasGesture() && request.isForMainFrame && host != current && current.isNotEmpty()) {
                openExternally(url)
                return true
            }
            return false
        }

        override fun onPageFinished(view: WebView, url: String?) {
            // Name von <a download="…"> merken, damit blob:-Downloads richtig heißen.
            view.evaluateJavascript(DOWNLOAD_NAME_HOOK_JS, null)
            view.evaluateJavascript("getComputedStyle(document.body).backgroundColor") { raw ->
                parseCssColor(raw?.trim('"') ?: "")?.let(onPageColor)
            }
        }

        /** Server nicht erreichbar → eigene, verständliche Fehlerseite statt der Chromium-Seite. */
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
            if (!request.isForMainFrame) return
            failedUrl = request.url.toString()
            view.loadDataWithBaseURL("https://$ERROR_HOST/", errorPage(request.url.toString(), error.description?.toString() ?: ""),
                "text/html", "utf-8", null)
        }

        /** Android hat den Web-Prozess beendet → neu aufbauen statt weißer Seite. */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            onRenderProcessGone()
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {

        // window.open (bei setSupportMultipleWindows(false) selten) → im selben Fenster bzw. extern
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
            val href = view.hitTestResult.extra ?: return false
            val uri = Uri.parse(href)
            if (uri.scheme?.lowercase() in listOf("http", "https") && uri.host?.lowercase() == originHost) view.loadUrl(href)
            else if (uri.scheme?.lowercase() in EXTERNAL_SCHEMES) openExternally(uri)
            return false
        }

        override fun onShowFileChooser(
            view: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            return try {
                activity.startActivityForResult(params.createIntent(), MainActivity.REQUEST_FILE)
                true
            } catch (_: ActivityNotFoundException) {
                fileCallback = null
                false
            }
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            MainActivity.REQUEST_FILE -> {
                fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
                fileCallback = null
            }
            MainActivity.REQUEST_SAVE -> {
                val bytes = pendingSave
                pendingSave = null
                val target = data?.data
                if (resultCode != Activity.RESULT_OK || bytes == null || target == null) return
                try {
                    activity.contentResolver.openOutputStream(target)?.use { it.write(bytes) }
                    toast("Gespeichert.")
                } catch (e: Exception) {
                    toast("Speichern fehlgeschlagen: ${e.localizedMessage}")
                }
            }
        }
    }

    // endregion

    // region Downloads

    private fun download(url: String, contentDisposition: String?, mimeType: String?) {
        when {
            url.startsWith("blob:") || url.startsWith("data:") -> {
                // blob:-Adressen kennt nur die Seite selbst → dort auslesen und herüberreichen.
                webView.evaluateJavascript(blobFetchJs(url, mimeType ?: ""), null)
            }
            url.startsWith("http") -> {
                // Anmelde-Cookies nur an den eigenen Planer – fremde Downloads im Browser öffnen
                if (AccessLogin.hostOf(url) != originHost) { openExternally(Uri.parse(url)); return }
                val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val request = DownloadManager.Request(Uri.parse(url))
                    .setMimeType(mimeType)
                    .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                    .addRequestHeader("User-Agent", webView.settings.userAgentString)
                    .setTitle(name)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                toast("Download gestartet: $name")
            }
        }
    }

    /** Wie „In Dateien sichern“ auf iOS: Android fragt, wo die Datei abgelegt werden soll. */
    private fun askWhereToSave(name: String, mimeType: String, bytes: ByteArray) {
        pendingSave = bytes
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mimeType.ifBlank { "application/octet-stream" })
            .putExtra(Intent.EXTRA_TITLE, name)
        try {
            activity.startActivityForResult(intent, MainActivity.REQUEST_SAVE)
        } catch (_: ActivityNotFoundException) {
            pendingSave = null
            toast("Keine App zum Speichern gefunden.")
        }
    }

    // endregion

    fun openExternally(uri: Uri) {
        if (uri.scheme?.lowercase() !in EXTERNAL_SCHEMES) return
        var target = uri
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, target).addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // Keine Kalender-App für webcal: → als https im Browser öffnen
            if (target.scheme == "webcal") {
                target = target.buildUpon().scheme("https").build()
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, target))
                    return
                } catch (_: ActivityNotFoundException) {}
            }
            toast("Keine passende App gefunden.")
        }
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    companion object {
        /** Schemata, die an andere Apps weitergegeben werden dürfen. */
        private val EXTERNAL_SCHEMES = setOf("http", "https", "mailto", "tel", "sms", "webcal", "geo")
        /** Adresse der eigenen Fehlerseite (.invalid gibt es nie im Internet). */
        private const val ERROR_HOST = "app.familienplaner.invalid"

        private const val DOWNLOAD_NAME_HOOK_JS = """
            (function(){
              if (window.__fpDownloadHook) return;
              window.__fpDownloadHook = true;
              const click = HTMLAnchorElement.prototype.click;
              HTMLAnchorElement.prototype.click = function() {
                if (this.hasAttribute('download')) window.__fpDownloadName = this.download || '';
                return click.apply(this, arguments);
              };
              document.addEventListener('click', e => {
                const a = e.target && e.target.closest && e.target.closest('a[download]');
                if (a) window.__fpDownloadName = a.download || '';
              }, true);
            })();
        """

        private fun blobFetchJs(url: String, mimeType: String) = """
            (async function(){
              try {
                const r = await fetch(${JSONObject.quote(url)});
                const blob = await r.blob();
                const data = await new Promise((ok, fail) => {
                  const fr = new FileReader();
                  fr.onload = () => ok(String(fr.result).split(',')[1] || '');
                  fr.onerror = fail;
                  fr.readAsDataURL(blob);
                });
                if (!window.FamilienplanerAndroid) return;
                FamilienplanerAndroid.saveFile(window.__fpDownloadName || 'download',
                  blob.type || ${JSONObject.quote(mimeType)}, data);
              } catch (e) { alert('Download fehlgeschlagen: ' + e); }
            })();
        """

        private fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        private fun errorPage(url: String, reason: String) = """
            <!doctype html><html lang="de"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              :root { color-scheme: light dark; }
              body { margin:0; min-height:100vh; display:flex; align-items:center; justify-content:center;
                     font-family:system-ui,sans-serif; background:#FBF8FF; color:#1F1B2E; }
              @media (prefers-color-scheme: dark) { body { background:#15131C; color:#F1EEF8; } .card { background:#211E2B !important; } }
              .card { background:#fff; border-radius:20px; padding:32px; max-width:460px; margin:24px; text-align:center;
                      box-shadow:0 2px 12px rgba(0,0,0,.08); }
              h1 { font-size:24px; margin:0 0 8px; }
              p { color:#6B6680; line-height:1.45; margin:6px 0; word-break:break-all; }
              button { font:inherit; font-weight:600; border:0; border-radius:12px; padding:14px; width:100%; margin-top:14px; }
              .main { background:#6C4CE0; color:#fff; } .alt { background:transparent; color:#6C4CE0; }
            </style></head><body><div class="card">
              <div style="font-size:44px">📡</div>
              <h1>Keine Verbindung</h1>
              <p>Der Familienplaner unter <b>${esc(url)}</b> ist gerade nicht erreichbar.</p>
              <p style="font-size:13px">${esc(reason)}</p>
              <a href="/retry"><button class="main">Erneut versuchen</button></a>
              <a href="/change"><button class="alt">Verbindung ändern</button></a>
            </div></body></html>
        """.trimIndent()

        /** Liest „rgb(251, 248, 255)“ / „rgba(…)“; durchsichtige Farben werden ignoriert. */
        fun parseCssColor(text: String): Int? {
            val parts = Regex("[0-9.]+").findAll(text).mapNotNull { it.value.toDoubleOrNull() }.toList()
            if (parts.size < 3) return null
            if (parts.size >= 4 && parts[3] < 0.5) return null
            return Color.rgb(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        }
    }
}
