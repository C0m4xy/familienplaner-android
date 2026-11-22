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
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject

/**
 * Anzeige des Planers: Navigation, Dialoge (alert/confirm/prompt übernimmt Android selbst),
 * Datei-Auswahl, Downloads (auch `blob:`-Downloads wie die Datensicherung) und Links.
 */
class PlannerBrowser(private val activity: MainActivity) {

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
        webView.addJavascriptInterface(Bridge(), "FamilienplanerAndroid")
        webView.webViewClient = Client()
        webView.webChromeClient = Chrome()
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            download(url, contentDisposition, mimeType)
        }
    }

    fun open(url: String) = webView.loadUrl(url)

    /** Antwort an die Seite (Ergebnis von pushEnable). */
    fun pushResult(ok: Boolean) =
        webView.evaluateJavascript("window.fpPushDone&&window.fpPushDone($ok)", null)

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

            // Umleitung zur Anmeldeseite = Anmeldung abgelaufen → wieder die native Anmeldung zeigen
            if (host.endsWith("cloudflareaccess.com") && request.isForMainFrame) {
                onSessionExpired()
                return true
            }
            // mailto:, tel:, webcal: (→ Kalender-App) …
            if (scheme !in listOf("http", "https", "about", "blob", "data", "javascript")) {
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
            view.loadDataWithBaseURL(null, errorPage(request.url.toString(), error.description?.toString() ?: ""),
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
            if (uri.host?.lowercase() == view.url?.let { AccessLogin.hostOf(it) }) view.loadUrl(href)
            else openExternally(uri)
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
                val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val request = DownloadManager.Request(Uri.parse(url))
                    .setMimeType(mimeType)
                    .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                    .addRequestHeader("User-Agent", webView.settings.userAgentString)
                    .setTitle(name)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .apply {
                        // Bis Android 9 bräuchte der öffentliche Download-Ordner eine Speicher-Berechtigung
                        if (android.os.Build.VERSION.SDK_INT >= 29) setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                        else setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, name)
                    }
                val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                toast("Download gestartet: $name")
            }
        }
    }

    /** Wird von der Seite aufgerufen, sobald der Inhalt eines blob:-Downloads gelesen ist. */
    private inner class Bridge {
        @JavascriptInterface
        fun saveFile(name: String, mimeType: String, base64: String) {
            val bytes = try {
                Base64.decode(base64, Base64.DEFAULT)
            } catch (_: IllegalArgumentException) {
                return
            }
            activity.runOnUiThread { askWhereToSave(name.ifBlank { "download" }, mimeType, bytes) }
        }

        /** Knöpfe der Fehlerseite (nur dort eingebunden, siehe errorPage). */
        @JavascriptInterface
        fun retry() = activity.runOnUiThread { reload() }

        @JavascriptInterface
        fun changeServer() = activity.runOnUiThread { onChangeServer() }

        // Mitteilungen: Die Seite meldet das Gerät mit der App-Kennung an (statt Web-Push)
        @JavascriptInterface
        fun pushInfo(): String = JSONObject()
            .put("device", Push.device(activity))
            .put("enabled", Push.enabled(activity))
            .toString()

        @JavascriptInterface
        fun pushEnable(streamUrl: String, pin: String) = activity.runOnUiThread { activity.enablePush(streamUrl, pin) }

        @JavascriptInterface
        fun pushDisable() = activity.runOnUiThread { Push.disable(activity) }
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
        var target = uri
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
              <button class="main" onclick="FamilienplanerAndroid.retry()">Erneut versuchen</button>
              <button class="alt" onclick="FamilienplanerAndroid.changeServer()">Verbindung ändern</button>
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
