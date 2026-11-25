package de.comaxy.familienplaner

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.min

class MainActivity : Activity() {

    enum class Screen { CHECKING, SETUP, CONNECTING, ENTER_CODE, VERIFYING, READY }

    private val scope = MainScope()
    private lateinit var config: ServerConfig
    private lateinit var login: AccessLogin
    private var planner: PlannerBrowser? = null
    private var screen = Screen.SETUP
    private var message: String? = null
    private var showingLoginPage = false
    private var pageColor = 0
    /** Pfad aus einer angetippten Mitteilung, z. B. „/?ring=…“ – wird beim Öffnen des Planers geladen. */
    private var pendingOpen: String? = null
    private var pushPending: Pair<String, String>? = null

    private lateinit var root: FrameLayout
    private lateinit var loginScreen: View
    private lateinit var checkingScreen: View
    private lateinit var plannerPane: View
    private lateinit var loginPageBar: View
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var setupGroup: View
    private lateinit var ownGroup: View
    private lateinit var modeGroup: RadioGroup
    private lateinit var ownType: RadioGroup
    private lateinit var accessGroup: View
    private lateinit var localGroup: View
    private lateinit var accessUrlField: EditText
    private lateinit var localUrlField: EditText
    private lateinit var portField: EditText
    private lateinit var emailField: EditText
    private lateinit var codeField: EditText
    private lateinit var messageView: TextView
    private lateinit var showLoginPage: View
    private lateinit var primaryButton: View
    private lateinit var primaryLabel: TextView
    private lateinit var primaryProgress: ProgressBar
    private lateinit var codeLinks: View
    private lateinit var demoHint: View
    private lateinit var demoButton: TextView
    private lateinit var versionLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        config = ServerConfig(getSharedPreferences("settings", MODE_PRIVATE))
        pageColor = getColor(R.color.background)
        bindViews()

        login = AccessLogin(this)
        findViewById<FrameLayout>(R.id.loginWebHolder).addView(login.webView, matchParent())

        setupInsets()
        setupBack()
        applyWindowOptions()
        pendingOpen = intent?.getStringExtra(Push.EXTRA_OPEN)
        Push.start(this)   // Mitteilungs-Verbindung (falls eingeschaltet) sicher laufen lassen
        render()
        if (!checkWebView()) launchSafely { onLaunch() }
        checkForUpdate(silent = true)
    }

    /** Mitteilung angetippt, während die App schon läuft. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val path = intent.getStringExtra(Push.EXTRA_OPEN) ?: return
        val p = planner
        if (screen == Screen.READY && p != null) originUrl()?.let { p.open(it + path) } else pendingOpen = path
    }

    override fun onResume() {
        super.onResume()
        val pending = Updater.pending
        if (pending != null && Updater.canInstall(this)) {   // zurück aus „Aus dieser Quelle zulassen“
            Updater.pending = null
            android.widget.Toast.makeText(this, "Update wird geladen …", android.widget.Toast.LENGTH_SHORT).show()
            runUpdate { Updater.install(this, pending) }
            return
        }
        if (Updater.due(this)) checkForUpdate(silent = true)   // Tablet bleibt oft tagelang offen
    }

    private fun runUpdate(job: suspend () -> Unit) {
        scope.launch {
            try { job() } catch (e: Exception) {
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("Update fehlgeschlagen")
                    .setMessage(e.message ?: e.toString()).setPositiveButton("OK", null).show()
            }
        }
    }

    /** Neue Version im GitHub-Repository? silent = ohne Meldung, wenn nichts Neues da ist. */
    private fun checkForUpdate(silent: Boolean) {
        if (silent && !Updater.due(this)) return
        scope.launch {
            val r = try { Updater.check(this@MainActivity) } catch (e: Exception) {
                if (!silent) android.widget.Toast.makeText(this@MainActivity, "Update-Prüfung nicht möglich: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            if (r != null) Updater.offer(this@MainActivity, r) { job -> runUpdate(job) }
            else if (!silent) android.widget.Toast.makeText(this@MainActivity, "Die App ist aktuell (Version ${Updater.currentName(this@MainActivity)}).", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        scope.cancel()
        planner?.destroy()
        login.webView.destroy()
        super.onDestroy()
    }

    /** Zurück-Taste/-Geste (ab Android 16 nur noch über OnBackInvokedCallback). */
    private fun setupBack() {
        onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            when {
                showingLoginPage -> closeLoginPage()
                screen == Screen.READY && planner?.goBack() == true -> Unit
                screen == Screen.ENTER_CODE -> backToSetup()
                else -> moveTaskToBack(true)   // wie die Standard-Zurück-Geste: App in den Hintergrund
            }
        }
    }

    @Deprecated("startActivityForResult ohne AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        planner?.onActivityResult(requestCode, resultCode, data)
    }

    // region Ablauf (wie AppModel der macOS-App)

    /** Beim Start: Cloud/Lokal → direkt zum Planer; Cloudflare Access → ist die Anmeldung noch gültig? */
    private suspend fun onLaunch() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (!prefs.getBoolean("configured", false)) { show(Screen.SETUP); return }
        val url = config.targetUrl
        if (url == null) { show(Screen.SETUP); return }
        if (config.mode != ServerConfig.Mode.ACCESS) { show(Screen.READY); return }
        show(Screen.CHECKING)
        try {
            when (login.start(url, config.email, sendCode = false)) {
                AccessLogin.StartResult.ALREADY_LOGGED_IN -> show(Screen.READY)
                else -> { message = "Bitte erneut anmelden."; show(Screen.SETUP) }
            }
        } catch (e: AccessLogin.LoginError) {
            message = e.message; show(Screen.SETUP)
        }
    }

    /** Schritt 1: Verbinden – bei Cloudflare Access den Code an die E-Mail-Adresse schicken lassen. */
    private suspend fun connect() {
        saveFields()
        message = null
        config.problem(config.mode, config.accessUrl, config.localServer, config.port, config.email)?.let {
            message = it; show(Screen.SETUP); return
        }
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("configured", true).apply()
        if (config.mode != ServerConfig.Mode.ACCESS) { show(Screen.READY); return }

        codeField.setText("")
        show(Screen.CONNECTING)
        try {
            when (login.start(config.targetUrl!!, config.email)) {
                AccessLogin.StartResult.ALREADY_LOGGED_IN -> show(Screen.READY)
                else -> show(Screen.ENTER_CODE)
            }
        } catch (e: AccessLogin.LoginError) {
            message = e.message; show(Screen.SETUP)
        }
    }

    /** Schritt 2: Code an Cloudflare Access weitergeben. */
    private suspend fun submitCode() {
        val otp = codeField.text.toString().filter { !it.isWhitespace() }
        if (otp.isEmpty() || screen == Screen.VERIFYING) return
        message = null
        show(Screen.VERIFYING)
        try {
            login.submit(otp)
            codeField.setText("")
            show(Screen.READY)
        } catch (e: AccessLogin.LoginError) {
            message = e.message
            codeField.setText("")
            show(Screen.ENTER_CODE)
        }
    }

    private fun backToSetup() {
        message = null
        codeField.setText("")
        show(Screen.SETUP)
    }

    /** Die Webseite leitet wieder auf Cloudflare Access um. */
    private fun sessionExpired() {
        message = "Die Anmeldung ist abgelaufen. Bitte neu anmelden."
        closePlanner()
        show(Screen.SETUP)
    }

    private fun logout() {
        Push.disable(this)   // ohne Anmeldung kämen ohnehin keine Mitteilungen mehr
        AccessLogin.logout()
        closePlanner()
        message = "Abgemeldet."
        codeField.setText("")
        show(Screen.SETUP)
    }

    /** Gewählte Variante laut Auswahlknöpfen. */
    private fun selectedMode() = when {
        modeGroup.checkedRadioButtonId != R.id.modeOwn -> ServerConfig.Mode.CLOUD
        ownType.checkedRadioButtonId == R.id.typeLocal -> ServerConfig.Mode.LOCAL
        else -> ServerConfig.Mode.ACCESS
    }

    private fun saveFields() {
        config.mode = selectedMode()
        config.accessUrl = ServerConfig.buildUrl(accessUrlField.text.toString(), "", "https")
            ?: accessUrlField.text.toString().trim()
        config.localServer = localUrlField.text.toString().trim()
        config.port = portField.text.toString().trim()
        config.email = emailField.text.toString().trim()
        accessUrlField.setText(config.accessUrl)
    }

    private fun launchSafely(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.localizedMessage ?: e.toString()
                show(Screen.SETUP)
            }
        }
    }

    // endregion

    // region Planer

    private fun openPlanner() {
        val url = config.targetUrl ?: return
        if (planner != null) return
        val browser = PlannerBrowser(this, originUrl() ?: return)
        browser.onSessionExpired = { sessionExpired() }
        browser.onPageColor = { color -> pageColor = color; applyBarColors() }
        browser.onChangeServer = {
            message = null
            closePlanner()
            show(Screen.SETUP)
        }
        browser.onRenderProcessGone = {
            closePlanner()
            if (screen == Screen.READY) openPlanner()
        }
        findViewById<FrameLayout>(R.id.plannerWebHolder).addView(browser.webView, matchParent())
        planner = browser
        val path = pendingOpen
        pendingOpen = null
        browser.open(if (path != null) (originUrl() ?: "") + path else url)
    }

    private fun closePlanner() {
        val p = planner ?: return
        planner = null
        (p.webView.parent as? ViewGroup)?.removeView(p.webView)
        p.destroy()
        pageColor = getColor(R.color.background)
        applyBarColors()
    }

    private fun showMenu(anchor: View) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, R.string.menu_back).isEnabled = planner?.webView?.canGoBack() == true
        menu.menu.add(0, 2, 1, R.string.menu_reload)
        menu.menu.add(0, 3, 2, R.string.menu_browser)
        menu.menu.add(0, 4, 3, R.string.menu_keep_on).apply {
            isCheckable = true; isChecked = prefs.getBoolean("keepScreenOn", false)
        }
        menu.menu.add(0, 5, 4, R.string.menu_fullscreen).apply {
            isCheckable = true; isChecked = prefs.getBoolean("fullscreen", false)
        }
        menu.menu.add(0, 7, 5, R.string.menu_info)
        menu.menu.add(0, 6, 6, R.string.menu_logout)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> planner?.goBack()
                2 -> planner?.reload()
                3 -> (planner?.webView?.url ?: config.targetUrl)?.let { planner?.openExternally(Uri.parse(it)) }
                4 -> { prefs.edit().putBoolean("keepScreenOn", !item.isChecked).apply(); applyWindowOptions() }
                5 -> { prefs.edit().putBoolean("fullscreen", !item.isChecked).apply(); applyWindowOptions() }
                6 -> logout()
                7 -> showInfo()
            }
            true
        }
        menu.show()
    }

    // endregion

    // region Anzeige

    private fun bindViews() {
        root = findViewById(R.id.root)
        loginScreen = findViewById(R.id.loginScreen)
        checkingScreen = findViewById(R.id.checkingScreen)
        plannerPane = findViewById(R.id.plannerPane)
        loginPageBar = findViewById(R.id.loginPageBar)
        title = findViewById(R.id.title)
        subtitle = findViewById(R.id.subtitle)
        setupGroup = findViewById(R.id.setupGroup)
        ownGroup = findViewById(R.id.ownGroup)
        modeGroup = findViewById(R.id.serverMode)
        ownType = findViewById(R.id.ownType)
        accessGroup = findViewById(R.id.accessGroup)
        localGroup = findViewById(R.id.localGroup)
        accessUrlField = findViewById(R.id.accessUrlField)
        localUrlField = findViewById(R.id.localUrlField)
        portField = findViewById(R.id.portField)
        emailField = findViewById(R.id.emailField)
        codeField = findViewById(R.id.codeField)
        messageView = findViewById(R.id.message)
        showLoginPage = findViewById(R.id.showLoginPage)
        primaryButton = findViewById(R.id.primaryButton)
        primaryLabel = findViewById(R.id.primaryLabel)
        primaryProgress = findViewById(R.id.primaryProgress)
        codeLinks = findViewById(R.id.codeLinks)
        demoHint = findViewById(R.id.demoHint)
        demoButton = findViewById(R.id.demoButton)
        versionLabel = findViewById(R.id.versionLabel)
        versionLabel.text = "Version ${Updater.currentName(this)} · Build ${BuildConfig.BUILD_DATE}"

        modeGroup.check(if (config.mode == ServerConfig.Mode.CLOUD) R.id.modeCloud else R.id.modeOwn)
        ownType.check(if (config.mode == ServerConfig.Mode.LOCAL) R.id.typeLocal else R.id.typeAccess)
        accessUrlField.setText(config.accessUrl)
        localUrlField.setText(config.localServer)
        portField.setText(config.port)
        emailField.setText(config.email)

        modeGroup.setOnCheckedChangeListener { _, _ -> message = null; render() }
        ownType.setOnCheckedChangeListener { _, _ -> message = null; render() }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { updatePrimaryEnabled() }
        }
        accessUrlField.addTextChangedListener(watcher)
        localUrlField.addTextChangedListener(watcher)
        portField.addTextChangedListener(watcher)
        emailField.addTextChangedListener(watcher)
        codeField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updatePrimaryEnabled()
                // Sobald sechs Ziffern drin sind, automatisch anmelden (wie iOS)
                if (screen == Screen.ENTER_CODE && s?.count { it.isDigit() } == 6) launchSafely { submitCode() }
            }
        })
        portField.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_GO) primaryAction(); true }
        emailField.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_GO) primaryAction(); true }
        codeField.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_GO) primaryAction(); true }

        primaryButton.setOnClickListener { primaryAction() }
        findViewById<View>(R.id.backButton).setOnClickListener { backToSetup() }
        findViewById<View>(R.id.newCodeButton).setOnClickListener { launchSafely { connect() } }
        showLoginPage.setOnClickListener { openLoginPage() }
        demoButton.setOnClickListener { requestDemo() }
        findViewById<View>(R.id.closeLoginPage).setOnClickListener { closeLoginPage() }
        findViewById<View>(R.id.menuButton).setOnClickListener { showMenu(it) }
        findViewById<View>(R.id.infoButton).setOnClickListener { showInfo() }
    }

    /**
     * Android 6/7: Der eingebaute Browser-Baustein wird über den Play Store aktualisiert.
     * Ist er zu alt, bliebe der Planer leer → vorher deutlich darauf hinweisen.
     * Liefert true, wenn der Hinweis gezeigt wird (Start geht nach „Trotzdem fortfahren“ weiter).
     */
    private fun checkWebView(): Boolean {
        val version = AccessLogin.webViewChromeVersion(this) ?: return false
        if (version >= AccessLogin.MIN_CHROME) return false
        show(Screen.SETUP)
        android.app.AlertDialog.Builder(this)
            .setTitle("Browser-Baustein zu alt")
            .setMessage("Der Familienplaner braucht „Android System WebView“ ab Version ${AccessLogin.MIN_CHROME}. " +
                "Auf diesem Gerät ist Version $version installiert – der Planer würde leer bleiben.\n\n" +
                "Bitte im Play Store „Android System WebView“ (bzw. „Chrome“) aktualisieren und die App danach neu starten.")
            .setPositiveButton("Play Store öffnen") { _, _ -> openWebViewInStore() }
            .setNegativeButton("Trotzdem fortfahren") { _, _ -> launchSafely { onLaunch() } }
            .setCancelable(false)
            .show()
        return true
    }

    private fun openWebViewInStore() {
        val id = "com.google.android.webview"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id")))
        } catch (_: android.content.ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id")))
        }
    }

    /** Info: App-Version und verwendete Adresse. */
    private fun showInfo() {
        val info = packageManager.getPackageInfo(packageName, 0)
        // Auf der Anmeldemaske zählt die aktuelle Auswahl, im Planer die gespeicherte Verbindung
        val mode = if (screen == Screen.READY) config.mode else selectedMode()
        val url = if (screen == Screen.READY) config.targetUrl else ServerConfig.urlFor(
            mode, accessUrlField.text.toString(), localUrlField.text.toString(), portField.text.toString())
        val modeText = when (mode) {
            ServerConfig.Mode.CLOUD -> "Cloud-Server"
            ServerConfig.Mode.ACCESS -> "Eigener Server (Cloudflare Access)"
            ServerConfig.Mode.LOCAL -> "Eigener Server (Lokal)"
        }
        @Suppress("DEPRECATION")
        val chrome = AccessLogin.webViewChromeVersion(this)
        val webView = when {
            chrome == null -> "unbekannt"
            chrome < AccessLogin.MIN_CHROME -> "$chrome – zu alt, bitte im Play Store aktualisieren"
            chrome < AccessLogin.FULL_CHROME -> "$chrome – ältere Version, manche Farben fehlen"
            else -> "$chrome"
        }
        val text = "Version ${info.versionName} (Build ${info.versionCode})\n" +
            "Android ${Build.VERSION.RELEASE}, Browser-Baustein (WebView): $webView\n\n" +
            "Verbindung: $modeText\nAdresse: ${url ?: "– noch nicht angegeben –"}"
        android.app.AlertDialog.Builder(this)
            .setTitle("Familienplaner für Android")
            .setIcon(R.mipmap.ic_launcher)
            .setMessage(text)
            .setPositiveButton("OK", null)
            .setNeutralButton("Nach Updates suchen") { _, _ -> checkForUpdate(silent = false) }
            .show()
    }

    /** „Demo anfordern“: Cloud-Server übernehmen und direkt das Demo-Formular der Anmeldeseite öffnen. */
    private fun requestDemo() {
        if (screen != Screen.SETUP || selectedMode() != ServerConfig.Mode.CLOUD) return
        hideKeyboard()
        saveFields()
        message = null
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("configured", true).apply()
        pendingOpen = ServerConfig.DEMO_PATH
        show(Screen.READY)
    }

    private fun primaryAction() {
        if (!primaryButton.isEnabled) return
        hideKeyboard()
        when (screen) {
            Screen.SETUP -> launchSafely { connect() }
            Screen.ENTER_CODE -> launchSafely { submitCode() }
            else -> Unit
        }
    }

    private fun show(s: Screen) {
        val wasReady = screen == Screen.READY
        screen = s
        if (s == Screen.READY) {
            hideKeyboard()
            if (!wasReady) login.unload()
            openPlanner()
        } else if (wasReady) {
            closePlanner()
        }
        render()
        if (s == Screen.ENTER_CODE) {
            codeField.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(codeField, 0)
        }
    }

    private fun render() {
        val mode = selectedMode()
        val codeStep = screen == Screen.ENTER_CODE || screen == Screen.VERIFYING
        val busy = screen == Screen.CONNECTING || screen == Screen.VERIFYING

        plannerPane.visibility = if (screen == Screen.READY) View.VISIBLE else View.GONE
        checkingScreen.visibility = if (screen == Screen.CHECKING) View.VISIBLE else View.GONE
        loginScreen.visibility =
            if (screen == Screen.READY || screen == Screen.CHECKING || showingLoginPage) View.GONE else View.VISIBLE
        loginPageBar.visibility = if (showingLoginPage) View.VISIBLE else View.GONE
        versionLabel.visibility = loginScreen.visibility

        title.setText(if (codeStep) R.string.code_title else R.string.app_name)
        subtitle.text = if (codeStep) getString(R.string.code_subtitle, config.email) else getString(R.string.setup_subtitle)
        setupGroup.visibility = if (codeStep) View.GONE else View.VISIBLE
        ownGroup.visibility = if (mode != ServerConfig.Mode.CLOUD) View.VISIBLE else View.GONE
        accessGroup.visibility = if (mode == ServerConfig.Mode.ACCESS) View.VISIBLE else View.GONE
        localGroup.visibility = if (mode == ServerConfig.Mode.LOCAL) View.VISIBLE else View.GONE
        codeField.visibility = if (codeStep) View.VISIBLE else View.GONE
        codeLinks.visibility = if (codeStep) View.VISIBLE else View.GONE
        // „Demo anfordern“ gibt es nur beim Cloud-Server (famplan.comaxy.de)
        val demo = if (!codeStep && mode == ServerConfig.Mode.CLOUD) View.VISIBLE else View.GONE
        demoHint.visibility = demo
        demoButton.visibility = demo
        demoButton.isEnabled = !busy
        for (v in listOf(accessUrlField, localUrlField, portField, emailField, codeField)) v.isEnabled = !busy
        for (g in listOf(modeGroup, ownType)) for (i in 0 until g.childCount) g.getChildAt(i).isEnabled = !busy

        messageView.text = message
        messageView.visibility = if (message.isNullOrEmpty()) View.GONE else View.VISIBLE
        // „Anmeldeseite anzeigen“ nur bei Cloudflare Access
        showLoginPage.visibility =
            if (!message.isNullOrEmpty() && mode == ServerConfig.Mode.ACCESS) View.VISIBLE else View.GONE

        primaryProgress.visibility = if (busy) View.VISIBLE else View.GONE
        primaryLabel.setText(
            when (screen) {
                Screen.CONNECTING -> R.string.connecting
                Screen.VERIFYING -> R.string.verifying
                Screen.ENTER_CODE -> R.string.sign_in
                else -> if (mode == ServerConfig.Mode.ACCESS) R.string.request_code else R.string.connect
            }
        )
        updatePrimaryEnabled()
        applyBarColors()
    }

    private fun updatePrimaryEnabled() {
        val enabled = when (screen) {
            Screen.SETUP -> config.problem(selectedMode(), accessUrlField.text.toString(),
                localUrlField.text.toString(), portField.text.toString(), emailField.text.toString()) == null
            Screen.ENTER_CODE -> codeField.text.isNotBlank()
            else -> false
        }
        primaryButton.isEnabled = enabled
        primaryButton.alpha = if (enabled || screen == Screen.CONNECTING || screen == Screen.VERIFYING) 1f else 0.6f
    }

    private fun openLoginPage() {
        showingLoginPage = true
        hideKeyboard()
        render()
    }

    /** Nach dem Schließen der sichtbaren Anmeldeseite: hat die Anmeldung dort geklappt? */
    private fun closeLoginPage() {
        showingLoginPage = false
        val url = config.targetUrl
        if (screen != Screen.READY && url != null && login.isOnTargetNow(url)) {
            CookieManager.getInstance().flush()
            message = null
            show(Screen.READY)
        } else {
            render()
        }
    }

    private fun hideKeyboard() {
        val view = currentFocus ?: return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
        root.requestFocus()
    }

    private fun matchParent() =
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    /** Ab Android 15 zeichnet die App unter Status- und Navigationsleiste → selbst Abstand halten. */
    private fun setupInsets() {
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        // Anmeldekarte auf Tablets nicht zu breit werden lassen
        val card = findViewById<LinearLayout>(R.id.card)
        root.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val max = (520 * resources.displayMetrics.density).toInt()
            val width = min(r - l - (48 * resources.displayMetrics.density).toInt(), max)
            if (card.layoutParams.width != width && width > 0) {
                card.layoutParams = card.layoutParams.also { it.width = width }
            }
        }
    }

    /** Farbe der Ränder (Status-/Navigationsleiste) passend zur Seite. */
    private fun applyBarColors() {
        val color = if (screen == Screen.READY) pageColor else getColor(R.color.background)
        root.setBackgroundColor(color)
        val light = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000 > 150
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (light) mask else 0, mask)
    }

    /** „Bildschirm anlassen“ und „Vollbild“ aus dem Menü. */
    private fun applyWindowOptions() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (prefs.getBoolean("keepScreenOn", false)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val full = prefs.getBoolean("fullscreen", false)
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let {
            if (full) {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                it.show(WindowInsets.Type.systemBars())
            }
        }
        root.requestApplyInsets()
    }

    // endregion

    // region Mitteilungen

    /** „scheme://host[:port]“ der gewählten Verbindung. */
    private fun originUrl(): String? {
        val u = Uri.parse(config.targetUrl ?: return null)
        return "${u.scheme}://${u.encodedAuthority}"
    }

    /** Von der Planer-Seite: Mitteilungen auf diesem Gerät einschalten. */
    fun enablePush(streamUrl: String, pin: String) {
        // Nur zum eigenen Planer verbinden (die Adresse kommt von der Seite): exakt Herkunft + Pfad + Gerätekennung
        val origin = originUrl()
        if (origin == null || streamUrl != "$origin/api/push-stream?dev=" + Uri.encode(Push.device(this))) {
            planner?.pushResult(false); return
        }
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pushPending = streamUrl to pin
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFY)
            return
        }
        finishEnablePush(streamUrl, pin)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_NOTIFY) return
        val pending = pushPending ?: return
        pushPending = null
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) finishEnablePush(pending.first, pending.second)
        else planner?.pushResult(false)
    }

    private fun finishEnablePush(streamUrl: String, pin: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (!nm.areNotificationsEnabled()) { planner?.pushResult(false); return }
        Push.enable(this, streamUrl, pin)
        planner?.pushResult(true)
        askBatteryExemption()
    }

    /** Ohne Ausnahme vom Akku-Sparen trennt Android die Verbindung im Ruhezustand (v. a. auf Handys). */
    private fun askBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        android.app.AlertDialog.Builder(this)
            .setTitle("Mitteilungen auch im Ruhezustand")
            .setMessage("Damit Mitteilungen auch ankommen, wenn das Gerät länger nicht benutzt wird, " +
                "sollte Android die Verbindung der App nicht zum Akku-Sparen trennen.\n\n" +
                "Auf einem Tablet am Ladegerät ist das nicht nötig.")
            .setPositiveButton("Akku-Sparen ausschalten") { _, _ ->
                try {
                    @android.annotation.SuppressLint("BatteryLife")
                    val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    startActivity(i)
                } catch (_: Exception) {
                    startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            .setNegativeButton("Später", null)
            .show()
    }

    // endregion

    companion object {
        const val REQUEST_FILE = 1
        const val REQUEST_SAVE = 2
        const val REQUEST_NOTIFY = 3
    }
}
