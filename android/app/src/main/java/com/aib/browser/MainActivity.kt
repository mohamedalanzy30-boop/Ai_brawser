package com.aib.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException

private const val ASSET_HOST = "appassets.min.local"
private const val HOME = "https://www.google.com"
private const val FILE_REQ = 41
private const val PLAYER_REQ = 42

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var refresher: SwipeRefreshLayout
    private lateinit var content: FrameLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var addressPill: FrameLayout
    private lateinit var addressText: TextView
    private lateinit var lock: ImageView
    /** hosts whose http address we switched to https: if https fails, they get the http page instead */
    private val upgraded = HashSet<String>()
    private val noHttps = HashSet<String>()
    private lateinit var urlEdit: EditText
    private lateinit var progress: ProgressBar
    private lateinit var backBtn: ImageView
    private lateinit var fwdBtn: ImageView
    private lateinit var aiBtn: ImageView
    private lateinit var clearBtn: ImageView
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var scriptHandler: ScriptHandler? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("enhance", Context.MODE_PRIVATE) }
    private fun dp(v: Int) = Ui.dp(this, v)
    private fun on(k: String) = prefs.getBoolean(k, defaults[k] == true)

    /* ======================= diagnostics log ======================= */
    private val logLines = ArrayDeque<String>()
    private val timeFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    private fun addLog(line: String) = synchronized(logLines) {
        val last = logLines.lastOrNull()
        if (last != null && last.substringAfter(' ').replace(Regex(" ×\\d+$"), "") == line) {
            val n = Regex(" ×(\\d+)$").find(last)?.groupValues?.get(1)?.toInt() ?: 1
            logLines.removeLast(); logLines.addLast("${timeFmt.format(java.util.Date())} $line ×${n + 1}")
        } else {
            logLines.addLast("${timeFmt.format(java.util.Date())} $line")
            while (logLines.size > 400) logLines.removeFirst()
        }
    }
    private fun logText(): String {
        val wv = WebViewCompat.getCurrentWebViewPackage(this)
        val d = windowManager.defaultDisplay
        val hdr = d.hdrCapabilities?.supportedHdrTypes?.joinToString { when (it) { 1 -> "DolbyVision"; 2 -> "HDR10"; 3 -> "HLG"; 4 -> "HDR10+"; else -> "$it" } } ?: "none"
        val m = android.util.DisplayMetrics().also { d.getRealMetrics(it) }
        val head = "display=${m.widthPixels}x${m.heightPixels} dpi=${m.densityDpi} refresh=${Math.round(d.refreshRate)}Hz hdr=$hdr\n" +
            "AI Browser ${packageManager.getPackageInfo(packageName, 0).versionName} | ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} | Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) | WebView ${wv?.versionName}\n" +
            "config=${config()}\nadblock=${if (adblockOn) "on" else "off"} hosts=${adHosts.size} adult=${adultHosts.size} blocked=$blocked popups=$popupsBlocked\nurl=${web.url}\n"
        val pl = synchronized(PlayerActivity.log) { PlayerActivity.log.joinToString("\n") }
        return head + synchronized(logLines) { logLines.joinToString("\n") } + (if (pl.isNotEmpty()) "\n--- native player ---\n$pl" else "")
    }

    /* ======================= settings ======================= */
    private val defaults = mapOf(
        "enabled" to true, "neural" to true, "tone" to true, "images" to true,
        "autoNative" to true, "gestures" to false, "brightness" to false,
        "adblock" to true, "popups" to true, "protect" to true, "adult" to false, "dark" to true, "cookies" to true, "noAutoplay" to true
    )
    private val zooms = intArrayOf(0, 90, 110, 125)
    private val zoomLabels = arrayOf("حسب الجوال", "أصغر", "أكبر", "كبير جداً")
    private fun textZoomNow(): Int = prefs.getInt("textZoom", 0).let { if (it > 0) it else Math.round(resources.configuration.fontScale * 100) }
    private val targets = intArrayOf(0, 720, 1080, 1440, 2160)
    private val targetLabels = arrayOf("تلقائي", "720p", "1080p", "2K", "4K")
    private val denoise = floatArrayOf(0f, 0.2f, 0.35f, 0.6f)
    private val denoiseLabels = arrayOf("بدون", "خفيف", "متوسط", "قوي")

    /** What the in-page engine needs (keys must match engine.js cfg). */
    private fun config(): JSONObject = JSONObject().apply {
        for (k in listOf("enabled", "neural", "tone", "images", "gestures", "autoNative")) put(k, on(k))
        put("target", prefs.getInt("target", 0)); put("denoise", prefs.getFloat("denoise", 0.35f).toDouble())
    }

    /* ======================= blocking: ads, trackers, adult (bundled lists, no network) ======================= */
    @Volatile private var adHosts: Set<String> = emptySet()
    @Volatile private var adultHosts: Set<String> = emptySet()
    @Volatile private var blocked = 0
    @Volatile private var popupsBlocked = 0
    @Volatile private var adblockOn = true
    @Volatile private var adultOn = false
    private fun loadList(name: String): Set<String> = try { assets.open(name).bufferedReader().useLines { l -> l.filter { it.isNotBlank() }.toHashSet() } } catch (e: Exception) { emptySet() }
    private fun loadLists() = Thread {
        adHosts = loadList("adhosts.txt"); addLog("[AIB] ad blocker: ${adHosts.size} hosts")
        if (adultOn) { adultHosts = loadList("adulthosts.txt"); addLog("[AIB] adult filter: ${adultHosts.size} hosts") }
    }.start()
    private fun inList(list: Set<String>, host: String?): Boolean {
        if (host == null || list.isEmpty()) return false
        var h = host.lowercase()
        while (true) { // also matches subdomains (x.ads.example.com -> ads.example.com)
            if (h in list) return true
            val i = h.indexOf('.'); if (i < 0 || h.indexOf('.', i + 1) < 0) return false
            h = h.substring(i + 1)
        }
    }
    private fun isAd(host: String?) = inList(adHosts, host)
    private fun isAdult(host: String?) = adultOn && inList(adultHosts, host)
    @Volatile private var pageHost: String? = null

    /** Registrable domain, good enough to tell "same site" from "another site". */
    private fun site(host: String?): String {
        val p = (host ?: "").lowercase().split('.').filter { it.isNotEmpty() }
        if (p.size <= 2) return p.joinToString(".")
        val sld = p[p.size - 2]
        val n = if (sld.length <= 3 && sld in setOf("co", "com", "net", "org", "gov", "edu", "ac", "or", "ne")) 3 else 2
        return p.takeLast(n).joinToString(".")
    }
    /** Sign-in pages that must open as a real pop-up window (they talk back to the page that opened them). */
    private val authHosts = setOf("accounts.google.com", "appleid.apple.com", "www.facebook.com", "m.facebook.com", "login.microsoftonline.com",
        "login.live.com", "github.com", "api.twitter.com", "twitter.com", "x.com", "discord.com", "www.linkedin.com", "accounts.spotify.com")

    /* ======================= native player hand-off ======================= */
    private data class Media(val url: String, val referer: String?, val hls: Boolean)
    private val media = java.util.concurrent.CopyOnWriteArrayList<Media>()
    private val mediaExt = Regex("\\.(mp4|m4v|webm|mkv|mov)$", RegexOption.IGNORE_CASE)
    @Volatile private var videoPlaying = false
    @Volatile private var sessPos = 0.0; @Volatile private var sessPage: String? = null; @Volatile private var pageUrl: String? = null
    private var pendingSeek: Pair<String, Double>? = null
    /** title of pageUrl, read on the UI thread (the bridge thread must not touch the WebView); throttle of the page player's Resume saves */
    @Volatile private var pageTitle: String? = null; @Volatile private var lastResume = 0L; @Volatile private var lastResumePage: String? = null
    @Volatile private var videoAspect = android.util.Rational(16, 9)
    /** subtitle files the page loaded (.vtt / .srt): handed to the native player */
    private val subs = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val subExt = Regex("\\.(vtt|webvtt|srt)$", RegexOption.IGNORE_CASE)
    private val subSegment = Regex("(?i)(seg|segment|chunk|frag)[^/]*$|[_-]\\d{1,5}\\.(vtt|webvtt)$") // pieces of an HLS subtitle stream, not whole files
    private fun noteMedia(req: WebResourceRequest) {
        val u = req.url; val path = u.path ?: ""
        if (subExt.containsMatchIn(path)) {
            val s = u.toString()
            if (!subSegment.containsMatchIn(path) && s !in subs) { subs.add(s); while (subs.size > 8) subs.removeAt(0) }
            return
        }
        val hls = path.endsWith(".m3u8", true) || path.endsWith(".m3u", true)
        if (!hls && !mediaExt.containsMatchIn(path)) return
        val s = u.toString()
        if (media.none { it.url == s }) { media.add(Media(s, req.requestHeaders["Referer"], hls)); while (media.size > 30) media.removeAt(0) }
    }
    /** sites where the native player could not play the video: they stay in the page player for this session */
    private val noNative = java.util.Collections.synchronizedSet(HashSet<String?>())
    private var nativeHost: String? = null
    private var nativeOpen = false
    private fun openNative(src: String, posSec: Double) {
        val direct = if (src.startsWith("http")) Media(src, web.url, src.contains(".m3u8", true)) else null
        // HLS players request the master playlist first, so the first .m3u8 is the best choice
        val m = direct ?: media.firstOrNull { it.hls } ?: media.lastOrNull()
        if (m == null) {
            addLog("[AIB] native player: no video address found (src=${src.take(40)}, seen=${media.size})")
            Toast.makeText(this, "لم أجد رابط الفيديو الحقيقي (قد يكون محمياً)", Toast.LENGTH_LONG).show(); return
        }
        val page = web.url ?: ""
        val saved = Resume.position(this, page)
        val pos = if (posSec < 5 && saved > 0) saved else (posSec * 1000).toLong()
        addLog("[AIB] native player: ${if (m.hls) "HLS" else "file"} ${m.url.take(120)} from ${pos / 1000}s")
        nativeHost = pageHost; nativeOpen = true
        startActivityForResult(Intent(this, PlayerActivity::class.java)
            .putStringArrayListExtra("subs", ArrayList(subs))
            .putExtra("url", m.url).putExtra("hls", m.hls).putExtra("referer", m.referer ?: page)
            .putExtra("ua", web.settings.userAgentString).putExtra("cookie", CookieManager.getInstance().getCookie(m.url) ?: "")
            .putExtra("pos", pos).putExtra("title", web.title ?: "").putExtra("page", page), PLAYER_REQ)
    }

    /** Small bridge for the in-page player controls. Every method is harmless if a page calls it. */
    inner class Bridge {
        @android.webkit.JavascriptInterface fun openNative(src: String, pos: Double) { ui.post { this@MainActivity.openNative(src, pos) } }
        /** auto mode: only switches when a real video address is known; otherwise the page keeps playing */
        @android.webkit.JavascriptInterface fun autoNative(src: String, pos: Double): Boolean {
            if (!on("autoNative") || pageHost in noNative) return false
            if (!src.startsWith("http") && media.isEmpty()) return false
            ui.post { this@MainActivity.openNative(src, pos) }
            return true
        }
        @android.webkit.JavascriptInterface fun playing(playing: Boolean, w: Int, h: Int) {
            videoPlaying = playing
            if (w > 0 && h > 0) { val r = w.toDouble() / h; videoAspect = if (r > 2.39) android.util.Rational(239, 100) else if (r < 0.42) android.util.Rational(42, 100) else android.util.Rational(w, h) }
            ui.post { keepScreenOn(playing); updatePipParams() }
        }
        @android.webkit.JavascriptInterface fun brightness(delta: Float): Float {
            val lp = window.attributes
            val cur = if (lp.screenBrightness < 0) try { android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f } catch (e: Exception) { 0.5f } else lp.screenBrightness
            val v = (cur + delta).coerceIn(0.02f, 1f)
            ui.post { window.attributes = window.attributes.also { it.screenBrightness = v } }
            return v
        }
        @android.webkit.JavascriptInterface fun volume(delta: Float): Float {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            val next = (cur + Math.round(delta * max)).coerceIn(0, max)
            if (next != cur) am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, next, 0)
            return next.toFloat() / max
        }
        @android.webkit.JavascriptInterface fun pip() { ui.post { enterPip() } }
        @android.webkit.JavascriptInterface fun pullOk(ok: Boolean) { pullOk = ok }
        /** playing film position (any frame), kept for restoring the session after Android closes the app and for "continue watching" */
        @android.webkit.JavascriptInterface fun position(t: Double, dur: Double) {
            val page = pageUrl ?: return
            if (!dur.isFinite() || !t.isFinite() || dur <= 0 || t <= 5 || !page.startsWith("http")) return // live streams, not started yet
            if (dur - t <= 20) { // finished: reopening must not jump to the credits, and it leaves "continue watching"
                sessPos = 0.0; sessPage = null; lastResume = 0L; Resume.remove(this@MainActivity, page); return
            }
            sessPos = t; sessPage = page
            if (t < 30) return // the first seconds (autoplay before the saved position is restored) must not erase the saved entry
            // "continue watching" for films played in the site's own player too, at most every 10 s
            val now = android.os.SystemClock.elapsedRealtime()
            if (page == lastResumePage && now - lastResume < 10_000L) return
            lastResume = now; lastResumePage = page
            Resume.save(this@MainActivity, page, pageTitle ?: "", (t * 1000).toLong(), (dur * 1000).toLong())
        }
    }

    /** The screen must not go dark while a video plays (the phone's sleep timer ignores web video). */
    private fun keepScreenOn(keep: Boolean) {
        if (keep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /* ======================= picture in picture ======================= */
    private fun pipParams(): android.app.PictureInPictureParams {
        val b = android.app.PictureInPictureParams.Builder().setAspectRatio(videoAspect)
        if (android.os.Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(videoPlaying).setSeamlessResizeEnabled(true)
        return b.build()
    }
    private fun updatePipParams() { try { setPictureInPictureParams(pipParams()) } catch (e: Exception) { } }
    private fun enterPip() { if (videoPlaying) try { enterPictureInPictureMode(pipParams()) } catch (e: Exception) { addLog("[AIB] PiP failed: $e") } }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); if (android.os.Build.VERSION.SDK_INT < 31 && videoPlaying) enterPip() }
    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        bottomBar.visibility = if (inPip) View.GONE else View.VISIBLE
        if (inPip) content.setPadding(0, 0, 0, 0) else content.requestApplyInsets()
        web.evaluateJavascript("window.__aibPip&&window.__aibPip($inPip)", null)
    }

    /* ======================= protection ======================= */
    private val trackingParams = Regex("^(utm_.*|fbclid|gclid|dclid|gbraid|wbraid|msclkid|yclid|mc_eid|mc_cid|igshid|_hsenc|_hsmi|ref_src)$")
    /** Removes tracking parameters (utm_*, fbclid, gclid...) from a URL; returns null if nothing changed. */
    private fun cleanUrl(u: Uri): Uri? {
        val names = try { u.queryParameterNames } catch (e: Exception) { return null }
        if (names.none { trackingParams.matches(it) }) return null
        val b = u.buildUpon().clearQuery()
        for (n in names) if (!trackingParams.matches(n)) for (v in u.getQueryParameters(n)) b.appendQueryParameter(n, v)
        return b.build()
    }

    /* ======================= in-page engine ======================= */
    private fun asset(path: String): String = assets.open(path).bufferedReader().use { it.readText() }
    private val cookieJs by lazy { asset("cookies.js") }
    private val bundle by lazy { asset("ort/ort.webgpu.min.js") + "\n;" + asset("engine.js") }
    /** Engine + ONNX Runtime injected at document start into EVERY frame (also embedded players in iframes). */
    private fun registerScript() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        scriptHandler?.remove()
        val extra = (if (on("cookies")) "\n;" + cookieJs else "") + "\n;" + PULL_JS
        scriptHandler = WebViewCompat.addDocumentStartJavaScript(web, "window.__minCfg0=${config()};\n$bundle$extra", setOf("*"))
    }
    /** Tells the app on every touch whether the touched content is scrolled to its top (inner scroll areas too), so pull-to-refresh never fires mid-page. */
    private val PULL_JS = """(function(){if(window.__aibPull)return;window.__aibPull=1;addEventListener('touchstart',function(e){var n=window.AIBNative;if(!n||!n.pullOk)return;var ok=(document.scrollingElement||document.documentElement).scrollTop<=0;for(var el=e.target;ok&&el&&el.nodeType===1;el=el.parentElement){if(el.scrollTop>0)ok=false}try{n.pullOk(ok)}catch(x){}},{passive:true,capture:true});window.__aibResume=function(t,play,fresh){var vs=[].slice.call(document.querySelectorAll('video')).filter(function(v){return v.duration>0});vs.sort(function(a,b){return b.duration-a.duration});var v=vs[0];if(v){if(t>1&&!(fresh&&v.currentTime>2))v.currentTime=t;if(play)v.play().catch(function(){})}for(var i=0;i<frames.length;i++)try{frames[i].postMessage({__aibResume:[t,play,fresh]},'*')}catch(x){}};addEventListener('message',function(e){if(e.data&&e.data.__aibResume&&e.source===parent)window.__aibResume(e.data.__aibResume[0],e.data.__aibResume[1],e.data.__aibResume[2])})})();"""
    @Volatile private var pullOk = true
    private fun applyConfig() {
        registerScript()
        web.evaluateJavascript("window.__minEnhanceSet&&window.__minEnhanceSet(${config()})", null)
    }
    private fun injectFallback() { // main frame only, used if document-start scripts are unsupported
        web.evaluateJavascript("if(!window.__minEnhance){window.__minCfg0=${config()};$bundle\n}", null)
    }

    /* ======================= UI ======================= */
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.SHEET
        adblockOn = on("adblock"); adultOn = on("adult")

        content = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        web = WebView(this).apply { setBackgroundColor(Ui.BG) }
        refresher = SwipeRefreshLayout(this).apply {
            addView(web)
            setColorSchemeColors(Ui.BLUE); setProgressBackgroundColorSchemeColor(Ui.CARD)
            setOnRefreshListener { web.reload() }
            // only when the page itself is scrolled to the top (pages with their own scrolling area keep working)
            setOnChildScrollUpCallback { _, _ -> web.scrollY > 0 || customView != null || !pullOk }
        }
        column.addView(refresher, LinearLayout.LayoutParams(-1, 0, 1f))
        bottomBar = buildBottomBar()
        column.addView(bottomBar, LinearLayout.LayoutParams(-1, -2))
        content.addView(column, FrameLayout.LayoutParams(-1, -1))
        setContentView(content)

        // Android 15 draws edge-to-edge: keep content clear of the system bars; the bar colour runs under the nav bar
        content.setOnApplyWindowInsetsListener { v, insets ->
            // the camera cutout counts too: in landscape the page and the bar must not slide under it
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, 0)
            bottomBar.setPadding(dp(12), dp(8), dp(12), b.bottom + dp(2))
            WindowInsets.CONSUMED
        }

        setupWebView()
        loadLists()
        registerScript()
        val restore = prefs.getString("restoreUrl", null); prefs.edit().remove("restoreUrl").apply()
        when {
            restore != null -> go(restore) // renderer crash: reload that page (crash-loop guard picks HOME)
            intent?.dataString != null -> go(intent.dataString!!)
            !restoreSession() -> go(HOME)
        }
    }

    private fun buildBottomBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.SHEET)
            setPadding(dp(12), dp(8), dp(12), dp(2)) // same as in the insets listener, so the first frame is already right
        }
        // hairline on top
        bar.addView(View(this).apply { setBackgroundColor(Ui.SEPARATOR) }, LinearLayout.LayoutParams(-1, 1).apply { bottomMargin = dp(8); marginStart = -dp(12); marginEnd = -dp(12) })

        // address pill: lock + domain (centered, like Safari); tap = edit; swipe toward the back button = back, the other way = forward
        addressPill = FrameLayout(this).apply {
            background = Ui.round(this@MainActivity, Ui.FIELD, 12); clipToOutline = true // the loading line follows the rounded corners
            contentDescription = "شريط العنوان"; isClickable = true
            setOnClickListener { startEdit() } // TalkBack double-tap; touches are handled below
        }
        val shown = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(16), 0, dp(16), 0) }
        lock = Ui.icon(this, R.drawable.ic_lock, Ui.SECONDARY, "اتصال آمن")
        addressText = Ui.text(this, "", 16f).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END; textDirection = View.TEXT_DIRECTION_FIRST_STRONG }
        shown.addView(lock, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(6) })
        shown.addView(addressText)
        urlEdit = EditText(this).apply {
            setSingleLine(); hint = "ابحث أو اكتب عنواناً"; setHintTextColor(Ui.SECONDARY); setTextColor(Ui.LABEL); textSize = 16f
            background = null; setPaddingRelative(dp(16), 0, dp(44), 0); visibility = View.GONE
            // direction follows what is typed: addresses run left-to-right, Arabic searches (and the empty field) right-to-left
            imeOptions = EditorInfo.IME_ACTION_GO; textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { _, _, _ -> go(text.toString()); endEdit(); true }
            setOnFocusChangeListener { _, f -> if (!f) endEdit() }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { if (visibility == View.VISIBLE) clearBtn.visibility = if (e.isNullOrEmpty()) View.GONE else View.VISIBLE }
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            })
        }
        clearBtn = Ui.icon(this, R.drawable.ic_clear, Ui.SECONDARY, "مسح النص") { urlEdit.setText("") }.apply { visibility = View.GONE }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE; progressTintList = ColorStateList.valueOf(Ui.BLUE); progressBackgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addressPill.addView(shown, FrameLayout.LayoutParams(-1, -1))
        addressPill.addView(urlEdit, FrameLayout.LayoutParams(-1, -1))
        addressPill.addView(clearBtn, FrameLayout.LayoutParams(dp(44), -1, Gravity.END or Gravity.CENTER_VERTICAL))
        addressPill.addView(progress, FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM))
        var x0 = 0f; var swiped = false
        addressPill.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.x; swiped = false }
                MotionEvent.ACTION_MOVE -> if (!swiped && Math.abs(e.x - x0) > dp(60)) {
                    swiped = true
                    // the back button sits on the left in English and on the right in Arabic: swiping toward it goes back
                    val towardBack = (e.x > x0) != (v.layoutDirection == View.LAYOUT_DIRECTION_RTL)
                    if (towardBack) goBackOnce() else if (web.canGoForward()) web.goForward()
                }
                MotionEvent.ACTION_UP -> if (!swiped) v.performClick()
            }
            true
        }
        bar.addView(addressPill, LinearLayout.LayoutParams(-1, dp(48)))

        // toolbar: back · forward · AI · history · reload (48dp touch targets)
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        backBtn = Ui.icon(this, R.drawable.ic_back, Ui.BLUE, "رجوع") { goBackOnce() }.apply { setOnLongClickListener { showHistory(); true } }
        fwdBtn = Ui.icon(this, R.drawable.ic_forward, Ui.BLUE, "تقدّم") { if (web.canGoForward()) web.goForward() }
        aiBtn = Ui.icon(this, R.drawable.ic_sparkle, Ui.BLUE, "الإعدادات") { showSettings() }
        val hist = Ui.icon(this, R.drawable.ic_history, Ui.BLUE, "السجل") { showHistory() }
        val reload = Ui.icon(this, R.drawable.ic_reload, Ui.BLUE, "إعادة التحميل") { web.reload() }
        for (v in listOf(backBtn, fwdBtn, aiBtn, hist, reload)) tools.addView(v, LinearLayout.LayoutParams(0, dp(48), 1f))
        bar.addView(tools, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        return bar
    }
    private fun startEdit() {
        urlEdit.setText(web.url ?: ""); urlEdit.visibility = View.VISIBLE; addressText.parent.let { (it as View).visibility = View.INVISIBLE }
        clearBtn.visibility = if (urlEdit.text.isEmpty()) View.GONE else View.VISIBLE
        urlEdit.requestFocus(); urlEdit.selectAll()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(urlEdit, InputMethodManager.SHOW_IMPLICIT)
    }
    private fun endEdit() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(urlEdit.windowToken, 0)
        urlEdit.visibility = View.GONE; clearBtn.visibility = View.GONE; (addressText.parent as View).visibility = View.VISIBLE; urlEdit.clearFocus()
        showAddress(web.url) // a redirect may have landed while the field was open
    }
    private fun showAddress(url: String?) {
        val u = Uri.parse(url ?: "")
        addressText.text = when {
            u.host == null -> url ?: ""
            u.host!!.contains("google.") && u.path == "/search" -> u.getQueryParameter("q") ?: u.host
            else -> u.host!!.removePrefix("www.")
        }
        lock.visibility = if (u.scheme == "https") View.VISIBLE else View.GONE
        backBtn.alpha = if (web.canGoBack()) 1f else 0.35f; backBtn.isEnabled = web.canGoBack() || customView != null
        fwdBtn.alpha = if (web.canGoForward()) 1f else 0.35f; fwdBtn.isEnabled = web.canGoForward()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            // data saver: videos start only when you tap play (pages cannot autoplay)
            mediaPlaybackRequiresUserGesture = on("noAutoplay")
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
            useWideViewPort = true; loadWithOverviewMode = true
            standardFontFamily = "sans-serif"; sansSerifFontFamily = "sans-serif"; fixedFontFamily = "monospace"; serifFontFamily = "serif"
            // text follows the phone's font size setting (or the size picked in ✨), and desktop-only pages get readable
            // text the way Chrome does it (font boosting) instead of tiny zoomed-out text
            textZoom = textZoomNow()
            @Suppress("DEPRECATION") layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
            setSupportMultipleWindows(true); javaScriptCanOpenWindowsAutomatically = false
            offscreenPreRaster = true
            // look like Chrome, not like an embedded WebView: Google sign-in and some sites refuse WebViews
            userAgentString = userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
            allowFileAccess = false; allowContentAccess = false; setGeolocationEnabled(false)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) WebSettingsCompat.setSafeBrowsingEnabled(this, true)
        }
        applyDark()
        // third-party cookies stay allowed: embedded movie players and sign-in need them; trackers are cut by the host list
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        web.addJavascriptInterface(Bridge(), "AIBNative")
        web.setDownloadListener { url, ua, disposition, mime, _ -> download(url, ua, disposition, mime) }
        web.webChromeClient = Chrome()
        web.webViewClient = Client()
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, p: Int) {
            progress.progress = p; progress.visibility = if (p in 1..99) View.VISIBLE else View.GONE
            if (p == 100) refresher.isRefreshing = false
        }
        override fun onShowCustomView(view: View, cb: CustomViewCallback) { // fullscreen video
            customView = view; customCallback = cb
            content.addView(view, FrameLayout.LayoutParams(-1, -1))
            content.setPadding(0, 0, 0, 0)
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            keepScreenOn(true)
            if (on("brightness")) window.attributes = window.attributes.also { it.screenBrightness = 1f }
        }
        override fun onHideCustomView() { exitFullscreen() }
        override fun onPermissionRequest(request: android.webkit.PermissionRequest) { request.deny() } // no camera/mic for pages
        override fun onGeolocationPermissionsShowPrompt(origin: String, cb: android.webkit.GeolocationPermissions.Callback) { cb.invoke(origin, false, false) }
        override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            fileCallback?.onReceiveValue(null); fileCallback = cb
            return try { startActivityForResult(params.createIntent().apply { if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) }, FILE_REQ); true }
            catch (e: Exception) { fileCallback = null; false }
        }
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
            // Pop-ups: a tap-less pop-up is always an ad. Otherwise catch the first address in a hidden window and decide:
            // sign-in pages open as a real window, same-site links open here, ads and pop-unders to other sites are dropped.
            val strict = on("popups")
            if (strict && !isUserGesture) { popupsBlocked++; addLog("[AIB] blocked pop-up (no tap)"); return false }
            val pw = WebView(this@MainActivity)
            pw.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; userAgentString = web.settings.userAgentString; setSupportMultipleWindows(false) }
            CookieManager.getInstance().setAcceptThirdPartyCookies(pw, true)
            var decided = false
            pw.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    if (decided) return false
                    decided = true
                    val host = r.url.host
                    return when {
                        host in authHosts -> { showPopup(pw); false }
                        isAd(host) || isAdult(host) || (strict && site(host) != site(pageHost)) -> { popupsBlocked++; addLog("[AIB] blocked pop-up -> $host"); ui.post { v.destroy() }; true }
                        else -> { web.loadUrl(r.url.toString()); ui.post { v.destroy() }; true }
                    }
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = pw
            resultMsg.sendToTarget()
            return true
        }
        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
            val msg = m.message()
            val noise = msg.startsWith("Permissions policy violation") || msg.contains("VerifyEachNodeIsAssignedToAnEp") || msg.startsWith("ResizeObserver loop")
            if (!noise && (msg.startsWith("[AIB]") || m.messageLevel() == ConsoleMessage.MessageLevel.ERROR)) addLog("[${m.messageLevel()}] $msg (${m.sourceId().substringAfterLast('/')}:${m.lineNumber()})")
            return true
        }
    }

    /** A real pop-up window (sign-in with Google/Apple/...): shown as a sheet, closes itself when the page calls window.close(). */
    private fun showPopup(pw: WebView) {
        val dlg = Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.SHEET) }
        // full-screen window on Android 15 is edge-to-edge: keep the header below the status bar
        col.setOnApplyWindowInsetsListener { v, i -> val b = i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime()); v.setPadding(b.left, b.top, b.right, b.bottom); WindowInsets.CONSUMED }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPaddingRelative(dp(16), 0, dp(4), 0) }
        head.addView(Ui.text(this, "تسجيل الدخول", 17f, bold = true).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_START }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(Ui.text(this, "إغلاق", 17f, Ui.BLUE).apply { gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0); setOnClickListener { dlg.dismiss() } }, LinearLayout.LayoutParams(-2, dp(48)))
        col.addView(head, LinearLayout.LayoutParams(-1, dp(52)))
        (pw.parent as? ViewGroup)?.removeView(pw)
        col.addView(pw, LinearLayout.LayoutParams(-1, 0, 1f))
        pw.webChromeClient = object : WebChromeClient() { override fun onCloseWindow(w: WebView) { dlg.dismiss() } }
        dlg.setOnDismissListener { pw.destroy() }
        // back steps back inside the sign-in pages first, then closes
        dlg.setOnKeyListener { _, k, e -> if (k == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP && pw.canGoBack()) { pw.goBack(); true } else false }
        dlg.setContentView(col); dlg.show()
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val u = request.url
            if (!(adblockOn && isAd(u.host))) noteMedia(request)
            // anti-adblock libraries are often served from the site itself: answer them with a stand-in that says "no blocker"
            if (adblockOn && !request.isForMainFrame && antiAdblock.containsMatchIn(u.toString())) return fakeResponse(request)
            if (!request.isForMainFrame && ((adblockOn && isAd(u.host) && !isAd(pageHost)) || isAdult(u.host))) {
                blocked++
                return fakeResponse(request)
            }
            if (u.host == "fonts.gstatic.com" && request.method == "GET") return proxyFont(request)
            if (u.host != ASSET_HOST) return null
            val path = u.path.orEmpty().trimStart('/')
            val cors = mapOf("Access-Control-Allow-Origin" to "*")
            return try {
                val mime = when {
                    path.endsWith(".wasm") -> "application/wasm"
                    path.endsWith(".js") || path.endsWith(".mjs") -> "text/javascript"
                    else -> "application/octet-stream"
                }
                WebResourceResponse(mime, null, 200, "OK", cors, assets.open(path))
            } catch (e: FileNotFoundException) {
                WebResourceResponse("text/plain", "utf-8", 404, "Not Found", cors, "".byteInputStream())
            }
        }
        override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
            if (req.isForMainFrame || req.url.host == ASSET_HOST) addLog("[LOAD ERROR] ${req.url} -> ${err.description}")
            // the site has no working https: open its plain http page (old sites, some movie mirrors)
            val host = req.url.host
            if (req.isForMainFrame && req.url.scheme == "https" && host != null && host in upgraded) {
                upgraded.remove(host); noHttps.add(host)
                addLog("[AIB] $host has no https, using http")
                view.loadUrl(req.url.buildUpon().scheme("http").build().toString())
            }
        }
        override fun onReceivedHttpError(view: WebView, req: WebResourceRequest, resp: WebResourceResponse) {
            if (req.url.host == ASSET_HOST || req.isForMainFrame) addLog("[HTTP ${resp.statusCode}] ${req.url}")
        }
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            addLog("[CRASH] WebView renderer gone, didCrash=${detail.didCrash()}")
            // the page engine died (out of memory or a crash): reopen the same page in a fresh screen instead of staying blank
            // a page that kills the renderer again right after a restore is not reopened (no endless crash loop)
            val again = System.currentTimeMillis() - prefs.getLong("restoreAt", 0) < 15_000
            prefs.edit().putString("restoreUrl", if (again) HOME else (view.url ?: HOME)).putLong("restoreAt", System.currentTimeMillis()).apply()
            ui.post { recreate() }
            return true
        }
        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            val scheme = req.url.scheme ?: ""
            if (scheme != "http" && scheme != "https" && scheme != "about" && scheme != "data" && scheme != "blob" && scheme != "javascript") {
                // intent://, market:// ... : ads use these to throw you into Chrome or the Play Store
                if (on("popups") && (!req.hasGesture() || req.url.toString().contains("package=com.android.chrome"))) {
                    popupsBlocked++; addLog("[AIB] blocked app redirect $scheme://${req.url.host}"); return true
                }
                return try {
                    val it = Intent.parseUri(req.url.toString(), Intent.URI_INTENT_SCHEME)
                    it.addCategory(Intent.CATEGORY_BROWSABLE); it.component = null; it.selector = null
                    startActivity(it); true
                } catch (e: Exception) { true }
            }
            if (req.isForMainFrame && isAdult(req.url.host)) { blockedPage(req.url.host); return true }
            if (req.isForMainFrame && on("protect")) {
                val clean = cleanUrl(req.url)
                if (scheme == "http" && !req.isRedirect && req.url.host !in noHttps) { req.url.host?.let { upgraded.add(it) }; view.loadUrl((clean ?: req.url).buildUpon().scheme("https").build().toString()); return true }
                if (clean != null) { view.loadUrl(clean.toString()); return true }
            }
            // pop-under / redirect ads: a frame or the page jumping to an ad host is dropped
            if (adblockOn && isAd(req.url.host) && !isAd(pageHost)) { blocked++; addLog("[AIB] blocked ad redirect ${req.url.host}"); return true }
            // while a video page is open, a jump to another site that nobody tapped is an ad redirect
            if (on("popups") && req.isForMainFrame && !req.hasGesture() && !req.isRedirect && videoPlaying && site(req.url.host) != site(pageHost)) {
                popupsBlocked++; addLog("[AIB] blocked redirect -> ${req.url.host}"); return true
            }
            return false
        }
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            val host = Uri.parse(url).host
            if (site(host) != site(pageHost)) { videoPlaying = false; media.clear(); subs.clear(); keepScreenOn(false) }
            pageHost = host
            if (!url.startsWith("file:")) view.settings.allowFileAccess = false
            if (!urlEdit.hasFocus()) showAddress(url)
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) injectFallback()
        }
        override fun onPageFinished(view: WebView, url: String) {
            refresher.isRefreshing = false
            Uri.parse(url).host?.let { upgraded.remove(it) } // https worked: no http fallback needed for this site
            if (!urlEdit.hasFocus()) showAddress(url)
            recordHistory(url, view.title); pageTitle = view.title; pageUrl = url; seekRestored(url)
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) injectFallback()
        }
        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) { if (!urlEdit.hasFocus()) showAddress(url) }
    }

    /* ======================= stealth blocking: sites cannot tell that ads were blocked ======================= */
    /** Known ad / tracking libraries are swapped for silent stand-ins that "work" (assets/surrogates). */
    private val surrogates = listOf(
        Regex("adsbygoogle\\.js") to "adsbygoogle.js",
        Regex("/(gpt|pubads_impl[^/]*)\\.js|googletagservices\\.com/tag/js") to "gpt.js",
        Regex("/ima3(_dai|_debug)?\\.js") to "ima3.js",
        Regex("/(analytics|ga|gtag/js|gtm)(\\.js)?(\\?|$)") to "analytics.js",
        Regex("(fuckadblock|blockadblock|sniffadblock)[^/]*\\.js", RegexOption.IGNORE_CASE) to "adblock-detect.js")
    private val antiAdblock = Regex("(fuckadblock|blockadblock|sniffadblock)[^/]*\\.js", RegexOption.IGNORE_CASE)
    private val gif1x1 = android.util.Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", 0)
    /**
     * A blocked request gets a normal-looking "200 OK" of the right kind instead of an error: scripts load (as harmless stand-ins
     * or empty), images are a transparent pixel, frames are an empty page. Anti-adblock checks that watch for failed loads see nothing.
     */
    private fun fakeResponse(req: WebResourceRequest): WebResourceResponse {
        val u = req.url.toString(); val path = (req.url.path ?: "").lowercase()
        val accept = req.requestHeaders["Accept"] ?: ""
        val h = mapOf("Access-Control-Allow-Origin" to (req.requestHeaders["Origin"] ?: "*"), "Access-Control-Allow-Credentials" to "true", "Cache-Control" to "no-store")
        fun ok(mime: String, body: ByteArray) = WebResourceResponse(mime, "utf-8", 200, "OK", h, java.io.ByteArrayInputStream(body))
        val sur = surrogates.firstOrNull { it.first.containsMatchIn(u) }?.second
        if (sur != null) return try { ok("application/javascript", assets.open("surrogates/$sur").use { it.readBytes() }) } catch (e: Exception) { ok("application/javascript", ByteArray(0)) }
        return when {
            path.endsWith(".js") || accept.contains("javascript") -> ok("application/javascript", ByteArray(0))
            Regex("\\.(gif|png|jpe?g|webp|svg|ico|bmp)$").containsMatchIn(path) || accept.startsWith("image/") -> ok("image/gif", gif1x1)
            path.endsWith(".css") || accept.startsWith("text/css") -> ok("text/css", ByteArray(0))
            accept.contains("text/html") -> ok("text/html", "<!doctype html><html><head></head><body></body></html>".toByteArray())
            path.endsWith(".xml") || path.contains("vast") || path.contains("vmap") -> ok("text/xml", "<?xml version=\"1.0\"?><VAST version=\"3.0\"/>".toByteArray()) // "no ad" for video players
            accept.contains("json") || path.endsWith(".json") -> ok("application/json", "{}".toByteArray())
            else -> ok("text/plain", ByteArray(0))
        }
    }

    private fun blockedPage(host: String?) {
        val h = android.text.TextUtils.htmlEncode(host ?: "")
        // viewport meta: without it the page is laid out 980px wide and zoomed out (tiny text); the address bar shows the blocked host
        web.loadDataWithBaseURL(null, "<!doctype html><html dir='rtl' lang='ar'><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
            "<body style='margin:0;background:#000;color:#fff;font-family:sans-serif;display:flex;align-items:center;justify-content:center;min-height:90vh;text-align:center'>" +
            "<div><div style='font-size:48px'>🛡️</div><p style='font-size:18px;margin:16px 0 8px'>هذا الموقع محجوب</p><p dir='ltr' style='color:#98989d;margin:0'>$h</p></div></body></html>",
            "text/html", "utf-8", if (host != null) "https://$host/" else null)
    }

    /** Some Google font files are served without CORS headers, so pages cannot use them. Same download, plus the header. */
    private fun proxyFont(req: WebResourceRequest): WebResourceResponse? = try {
        val c = java.net.URL(req.url.toString()).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000
        req.requestHeaders["User-Agent"]?.let { c.setRequestProperty("User-Agent", it) }
        val code = c.responseCode
        val acao = c.getHeaderField("Access-Control-Allow-Origin")
        if (acao != null) { c.disconnect(); null } // already fine: let WebView load it normally
        else if (code != 200) { // font removed by Google (e.g. old /ea/ fonts): fail quietly, page falls back to the phone font
            c.disconnect()
            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf("Access-Control-Allow-Origin" to "*"), "".byteInputStream())
        } else WebResourceResponse(c.contentType?.substringBefore(';') ?: "font/woff2", null, 200, "OK",
            mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "public, max-age=31536000"), c.inputStream)
    } catch (e: Exception) { null }

    /* ======================= downloads & uploads ======================= */
    private fun download(url: String, ua: String, disposition: String?, mime: String?) {
        if (!url.startsWith("http")) { Toast.makeText(this, "لا يمكن تحميل هذا الملف", Toast.LENGTH_SHORT).show(); return }
        try {
            val name = android.webkit.URLUtil.guessFileName(url, disposition, mime)
            val r = android.app.DownloadManager.Request(Uri.parse(url))
                .addRequestHeader("User-Agent", ua).addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                .addRequestHeader("Referer", web.url ?: "")
                .setMimeType(mime).setTitle(name)
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, name)
            (getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager).enqueue(r)
            Toast.makeText(this, "بدأ التحميل: $name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { addLog("[AIB] download failed: $e"); Toast.makeText(this, "فشل التحميل", Toast.LENGTH_SHORT).show() }
    }
    @Deprecated("plain Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == PLAYER_REQ) {
            nativeOpen = false
            val failed = data?.getBooleanExtra("failed", false) == true
            val pos = (data?.getLongExtra("pos", 0L) ?: 0L) / 1000.0
            if (failed) {
                noNative.add(nativeHost)
                Toast.makeText(this, "المشغّل الأصلي لم يستطع تشغيل هذا الفيديو، أكملته هنا", Toast.LENGTH_LONG).show()
            }
            // the page video continues from where the native player stopped (it plays again only if the native player failed)
            web.evaluateJavascript("window.__aibResume&&window.__aibResume($pos,$failed)", null)
            return
        }
        if (requestCode == FILE_REQ) { fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data)); fileCallback = null; return }
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
    }

    /* ======================= AI badge ======================= */
    /** The ✨ turns purple while a video is being enhanced; checked every 2 s only while the browser is on screen. */
    private val badgeTick = Runnable { pollBadge() }
    private fun pollBadge() {
        web.evaluateJavascript("(function(){var s=window.__minEnhance&&window.__minEnhance.state.stats;return JSON.stringify({a:(s&&s.active)||0})})()") { raw ->
            val j = try { JSONObject(org.json.JSONTokener(raw).nextValue() as String) } catch (e: Exception) { JSONObject() }
            aiBtn.imageTintList = ColorStateList.valueOf(if (j.optInt("a") > 0) Ui.AI else Ui.BLUE)
        }
        ui.removeCallbacks(badgeTick); ui.postDelayed(badgeTick, 2000)
    }

    private fun go(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(t, "UTF-8")
        }
        web.loadUrl(url)
    }

    private fun exitFullscreen() {
        customView?.let { content.removeView(it) }
        customView = null
        customCallback?.onCustomViewHidden(); customCallback = null
        @Suppress("DEPRECATION") window.decorView.systemUiVisibility = 0
        window.attributes = window.attributes.also { it.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        keepScreenOn(videoPlaying)
        content.requestApplyInsets()
    }

    /* ======================= settings sheet ======================= */
    private fun showSettings() {
        Ui.sheet(this, "الإعدادات", full = true) { col, dlg ->
            fun set(k: String, v: Boolean) { prefs.edit().putBoolean(k, v).apply() }
            Ui.Group(this, col, "تحسين الفيديو والصور بالذكاء الاصطناعي", "يعمل على جهازك بدون إنترنت إضافي.").apply {
                toggle("تفعيل التحسين على كل المواقع", on("enabled")) { set("enabled", it); applyConfig() }
                toggle("رفع الدقة العصبي (Real-ESRGAN)", on("neural")) { set("neural", it); applyConfig() }
                toggle("HDR ديناميكي لكل مشهد (فكرة HDR10+)", on("tone")) { set("tone", it); applyConfig() }
                toggle("تحسين الصور", on("images")) { set("images", it); applyConfig() }
            }
            Ui.Group(this, col, "الدقة المستهدفة").segmented(targetLabels, targets.indexOf(prefs.getInt("target", 0)).coerceAtLeast(0)) { i -> prefs.edit().putInt("target", targets[i]).apply(); applyConfig() }
            Ui.Group(this, col, "إزالة الضوضاء وتكسّر الضغط").segmented(denoiseLabels, denoise.indexOfFirst { it == prefs.getFloat("denoise", 0.35f) }.let { if (it < 0) 2 else it }) { i -> prefs.edit().putFloat("denoise", denoise[i]).apply(); applyConfig() }
            Ui.Group(this, col, "المشغّل", "المشغّل الأصلي يرفع الدقة على المعالج العصبي في جوالك.").apply {
                toggle("فتح الأفلام تلقائياً في المشغّل الأصلي", on("autoNative")) { set("autoNative", it); applyConfig() }
                toggle("أدوات إضافية فوق مشغّل الموقع (سرعة، قفل، إيماءات)", on("gestures")) { set("gestures", it); applyConfig() }
                toggle("سطوع أعلى في ملء الشاشة", on("brightness")) { set("brightness", it) }
                link("أكمل المشاهدة", "${Resume.list(this@MainActivity).size}") { dlg.dismiss(); showContinue() }
            }
            Ui.Group(this, col, "الخصوصية والحماية").apply {
                toggle("حظر الإعلانات والمتتبعات", on("adblock")) { set("adblock", it); adblockOn = it }
                toggle("منع النوافذ المنبثقة والتحويلات", on("popups")) { set("popups", it) }
                toggle("حماية متقدمة: تصفح آمن، HTTPS، إزالة روابط التتبع", on("protect")) { set("protect", it) }
                toggle("حجب المحتوى غير اللائق", on("adult")) { set("adult", it); adultOn = it; if (it && adultHosts.isEmpty()) Thread { adultHosts = loadList("adulthosts.txt") }.start() }
            }
            Ui.Group(this, col, "بدون إنترنت").apply {
                link("حفظ هذه الصفحة") { dlg.dismiss(); savePage() }
                media.lastOrNull { !it.hls }?.let { m -> link("تحميل الفيديو الحالي") { dlg.dismiss(); download(m.url, web.settings.userAgentString, null, "video/mp4") } }
                link("التحميلات") { dlg.dismiss(); showDownloads() }
                link("الصفحات المحفوظة", "${savedDir.listFiles()?.size ?: 0}") { dlg.dismiss(); showSaved() }
            }
            Ui.Group(this, col, "حجم النص في المواقع").segmented(zoomLabels, zooms.indexOf(prefs.getInt("textZoom", 0)).coerceAtLeast(0)) { i ->
                prefs.edit().putInt("textZoom", zooms[i]).apply(); web.settings.textZoom = textZoomNow()
            }
            Ui.Group(this, col, "عام").apply {
                toggle("الوضع الداكن لكل المواقع", on("dark")) { set("dark", it); applyDark(); web.reload() }
                toggle("إخفاء نوافذ الكوكيز", on("cookies")) { set("cookies", it); registerScript(); web.reload() }
                toggle("لا تشغّل الفيديو تلقائياً (توفير البيانات)", on("noAutoplay")) { set("noAutoplay", it); web.settings.mediaPlaybackRequiresUserGesture = it }
                link("السجل") { dlg.dismiss(); showHistory() }
                link("سجل الأخطاء (للنسخ)") { dlg.dismiss(); showLog() }
            }
        }.show()
    }

    /** Dark mode for every site: sites with their own dark style use it, white sites are darkened by WebView. */
    private fun applyDark() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) WebSettingsCompat.setAlgorithmicDarkeningAllowed(web.settings, on("dark"))
    }

    /* ======================= downloads list ======================= */
    private fun showDownloads() {
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        var sheet: Ui.Sheet? = null
        val tick = Runnable { if (sheet?.dialog?.isShowing == true) sheet?.refresh() }
        sheet = Ui.sheet(this, "التحميلات") { col, dlg ->
            data class D(val id: Long, val name: String, val status: Int, val done: Long, val total: Long)
            val items = ArrayList<D>()
            try {
                dm.query(android.app.DownloadManager.Query()).use { c ->
                    val iId = c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_ID); val iT = c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_TITLE)
                    val iS = c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS); val iD = c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val iZ = c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    while (c.moveToNext()) items.add(D(c.getLong(iId), c.getString(iT) ?: "", c.getInt(iS), c.getLong(iD), c.getLong(iZ)))
                }
            } catch (e: Exception) { addLog("[AIB] downloads query failed: $e") }
            items.sortByDescending { it.id }
            if (items.isEmpty()) { Ui.empty(this, col, "ما تحمّله من المواقع يظهر هنا."); return@sheet }
            val g = Ui.Group(this, col, null, "اضغط لفتح الملف، ومطولاً لإلغائه أو حذفه من القائمة.")
            for (d in items) {
                val mb = { b: Long -> String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0) }
                val state = when (d.status) {
                    android.app.DownloadManager.STATUS_SUCCESSFUL -> mb(d.total)
                    android.app.DownloadManager.STATUS_FAILED -> "فشل"
                    android.app.DownloadManager.STATUS_PAUSED -> "متوقف مؤقتاً"
                    else -> if (d.total > 0) "${d.done * 100 / d.total}%" else mb(d.done)
                }
                g.link(d.name, state) {
                    if (d.status != android.app.DownloadManager.STATUS_SUCCESSFUL) return@link
                    val uri = dm.getUriForDownloadedFile(d.id) ?: return@link
                    try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, dm.getMimeTypeForDownloadedFile(d.id)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                    catch (e: Exception) { Toast.makeText(this, "لا يوجد تطبيق يفتح هذا الملف", Toast.LENGTH_SHORT).show() }
                }.setOnLongClickListener { dm.remove(d.id); sheet?.refresh(); true }
            }
            // live progress while the sheet is open: the rows are redrawn in place (no close/reopen flicker)
            ui.removeCallbacks(tick)
            if (items.any { it.status == android.app.DownloadManager.STATUS_RUNNING || it.status == android.app.DownloadManager.STATUS_PENDING }) ui.postDelayed(tick, 2000)
        }
        sheet.dialog.setOnDismissListener { ui.removeCallbacks(tick) }
        sheet.show()
    }

    /* ======================= offline pages (web archives on the phone) ======================= */
    private val savedDir by lazy { java.io.File(filesDir, "saved").apply { mkdirs() } }
    private fun savePage() {
        val url = web.url ?: return
        if (!url.startsWith("http")) return
        val name = (web.title ?: Uri.parse(url).host ?: "page").replace(Regex("[\\/:*?\"<>|\n]"), " ").trim().take(60) + "_" + System.currentTimeMillis() + ".mht"
        web.saveWebArchive(java.io.File(savedDir, name).absolutePath, false) { path ->
            Toast.makeText(this, if (path != null) "حُفظت الصفحة، تفتحها بدون إنترنت" else "تعذّر حفظ الصفحة", Toast.LENGTH_SHORT).show()
        }
    }
    private fun showSaved() {
        var sheet: Ui.Sheet? = null
        sheet = Ui.sheet(this, "الصفحات المحفوظة") { col, dlg ->
            val files = savedDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
            if (files.isEmpty()) { Ui.empty(this, col, "احفظ أي صفحة من ✨ ← حفظ هذه الصفحة، وتفتحها بعدها بدون إنترنت."); return@sheet }
            val g = Ui.Group(this, col, null, "اضغط مطولاً للحذف.")
            for (f in files) {
                g.link(f.name.substringBeforeLast('_')) {
                    dlg.dismiss()
                    web.settings.allowFileAccess = true // only for saved pages; switched off again on the next web page
                    web.loadUrl(Uri.fromFile(f).toString())
                }.setOnLongClickListener { f.delete(); sheet?.refresh(); true } // long press = delete
            }
        }
        sheet.show()
    }

    /* ======================= continue watching ======================= */
    private fun showContinue() {
        Ui.sheet(this, "أكمل المشاهدة") { col, dlg ->
            val items = Resume.list(this)
            if (items.isEmpty()) { Ui.empty(this, col, "الأفلام التي لم تكملها تظهر هنا، سواء شاهدتها في مشغّل الموقع أو في المشغّل الأصلي."); return@sheet }
            val g = Ui.Group(this, col, null, "افتح الفيلم وسيكمل من حيث توقفت.")
            for (it in items) {
                val left = (it.durMs - it.posMs) / 60000
                g.link(it.title.ifBlank { Uri.parse(it.page).host ?: it.page }, "باقي $left د") { dlg.dismiss(); pendingSeek = it.page to it.posMs / 1000.0; web.loadUrl(it.page) }
            }
        }.show()
    }

    /* ======================= browsing history (on the phone only) ======================= */
    private val histFile by lazy { java.io.File(filesDir, "history.json") }
    private val history: JSONArray by lazy { try { JSONArray(histFile.readText()) } catch (e: Exception) { JSONArray() } }
    private fun saveHistory() { val txt = history.toString(); Thread { try { histFile.writeText(txt) } catch (e: Exception) { } }.start() }
    private fun recordHistory(url: String, title: String?) {
        if (!url.startsWith("http") || isAd(Uri.parse(url).host)) return
        val n = history.length()
        val last = if (n > 0) history.getJSONObject(n - 1) else null
        if (last != null && last.optString("u") == url) { if (!title.isNullOrBlank()) last.put("t", title); saveHistory(); return }
        history.put(JSONObject().put("u", url).put("t", title ?: "").put("d", System.currentTimeMillis()))
        while (history.length() > 1500) history.remove(0)
        saveHistory()
    }
    private fun showHistory() {
        Ui.sheet(this, "السجل", full = true) { col, dlg ->
            val search = EditText(this).apply {
                hint = "بحث في السجل"; setHintTextColor(Ui.SECONDARY); setTextColor(Ui.LABEL); textSize = 16f; setSingleLine()
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG; imeOptions = EditorInfo.IME_ACTION_SEARCH
                background = Ui.round(this@MainActivity, Ui.CARD, 12); setPaddingRelative(dp(16), 0, dp(16), 0)
            }
            col.addView(search, LinearLayout.LayoutParams(-1, dp(44)))
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(list)
            val fmt = java.text.SimpleDateFormat("d/M  HH:mm", java.util.Locale.US)
            fun fill(q: String) {
                list.removeAllViews()
                val g = Ui.Group(this, list, null)
                var shown = 0
                for (i in history.length() - 1 downTo 0) {
                    val o = history.getJSONObject(i); val u = o.optString("u"); val t = o.optString("t").ifBlank { Uri.parse(u).host ?: u }
                    if (q.isNotEmpty() && !u.contains(q, true) && !t.contains(q, true)) continue
                    g.link(t, fmt.format(java.util.Date(o.optLong("d")))) { dlg.dismiss(); web.loadUrl(u) }
                    if (++shown >= 200) break
                }
                if (shown == 0) {
                    list.removeAllViews() // no empty card
                    Ui.empty(this, list, if (q.isEmpty()) "لم تزر أي صفحة بعد." else "لا نتائج لـ «$q».")
                }
                if (history.length() > 0) Ui.Group(this, list, null).action("مسح السجل", Ui.RED) {
                    // a one-tap wipe of 1,500 entries needs a confirmation
                    android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("مسح السجل؟").setMessage("ستُحذف كل الصفحات المحفوظة في السجل من هذا الجوال.")
                        .setNegativeButton("إلغاء", null)
                        .setPositiveButton("مسح") { _, _ -> while (history.length() > 0) history.remove(0); saveHistory(); fill("") }
                        .show()
                }
            }
            search.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { fill(e?.toString()?.trim() ?: "") }
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            })
            fill("")
        }.show()
    }

    private fun showLog() {
        Ui.sheet(this, "سجل الأخطاء", full = true) { col, _ ->
            val txt = Ui.text(this, logText(), 11f, 0xFFD0D0D0.toInt()).apply { setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE; textDirection = View.TEXT_DIRECTION_LTR }
            Ui.Group(this, col, null).apply {
                action("نسخ الكل") {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("AI Browser log", logText()))
                    Toast.makeText(this@MainActivity, "تم النسخ", Toast.LENGTH_SHORT).show()
                }
                action("مسح", Ui.RED) { synchronized(logLines) { logLines.clear() }; txt.text = logText() }
            }
            col.addView(txt, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        }.show()
    }

    /* ======================= back navigation ======================= */
    /**
     * One back = one page. Pages (and ads) leave duplicate or redirect entries in the history, and some
     * phones deliver back twice; so: ignore a second back within 450 ms, and skip entries that are the
     * same address as the current page or an ad host.
     */
    private var lastBack = 0L
    private fun goBackOnce(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastBack < 450) return true
        lastBack = now
        if (customView != null) { exitFullscreen(); return true }
        if (urlEdit.visibility == View.VISIBLE) { endEdit(); return true }
        val list = web.copyBackForwardList(); val cur = list.currentIndex
        if (cur <= 0) return false
        val here = list.currentItem?.url?.substringBefore('#')
        var i = cur - 1
        while (i > 0) {
            val u = list.getItemAtIndex(i).url
            if (u.substringBefore('#') != here && !isAd(Uri.parse(u).host)) break
            i--
        }
        web.goBackOrForward(i - cur)
        return true
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { if ((event?.repeatCount ?: 0) > 0) return true; if (goBackOnce()) return true }
        return super.onKeyDown(keyCode, event)
    }
    @Deprecated("handled through onKeyDown")
    override fun onBackPressed() { if (!goBackOnce()) @Suppress("DEPRECATION") super.onBackPressed() }

    /** The browser is fully hidden (native player on top, or the app in the background but not in PiP): page timers stop,
     *  so site players stop buffering and scripts stop using battery and data. */
    /* ======================= session: Android may close the browser in the background; reopening continues ======================= */
    private val sessFile by lazy { java.io.File(filesDir, "session.bin") }
    private fun saveSession() {
        try {
            val b = Bundle(); if (web.saveState(b) == null) return
            val p = android.os.Parcel.obtain(); try { b.writeToParcel(p, 0); sessFile.writeBytes(p.marshall()) } finally { p.recycle() }
            val e = prefs.edit().putString("sessUrl", web.url)
            if (sessPage != null && sessPage == web.url && sessPos > 0) e.putString("sessSeek", "${sessPos}|$sessPage") else e.remove("sessSeek")
            e.apply()
        } catch (x: Exception) { addLog("[AIB] session save failed: $x") }
    }
    private fun restoreSession(): Boolean {
        val seek = prefs.getString("sessSeek", null)?.split("|", limit = 2)
        if (seek != null && seek.size == 2) seek[0].toDoubleOrNull()?.let { pendingSeek = seek[1] to it }
        try {
            if (sessFile.exists()) {
                val bytes = sessFile.readBytes(); val p = android.os.Parcel.obtain()
                try {
                    p.unmarshall(bytes, 0, bytes.size); p.setDataPosition(0)
                    val b = Bundle.CREATOR.createFromParcel(p)
                    if (web.restoreState(b) != null && web.copyBackForwardList().size > 0) { addLog("[AIB] session restored: ${web.url}"); return true }
                } finally { p.recycle() }
            }
        } catch (x: Exception) { addLog("[AIB] session restore failed: $x") }
        val u = prefs.getString("sessUrl", null) ?: return false
        go(u); return true
    }
    /** once the restored page has loaded: put its film back where it was (paused; the page or the native player continues on play) */
    private fun seekRestored(url: String) {
        val (page, t) = pendingSeek ?: return
        if (url != page) return
        pendingSeek = null
        // the player may appear late (iframes, scripts): try for 30 s
        web.evaluateJavascript("(function(n){var f=function(){if(window.__aibResume){window.__aibResume($t,false,true)};if(--n>0)setTimeout(f,3000)};setTimeout(f,1500)})(10)", null)
        Toast.makeText(this, "أكمل من ${(t / 60).toInt()}:${"%02d".format((t % 60).toInt())}", Toast.LENGTH_SHORT).show()
    }

    override fun onStop() { saveSession(); super.onStop(); ui.removeCallbacks(badgeTick); if (!isInPictureInPictureMode && (nativeOpen || !videoPlaying)) { web.onPause(); web.pauseTimers() } }
    override fun onStart() { super.onStart(); web.onResume(); web.resumeTimers(); pollBadge() }
    override fun onDestroy() { ui.removeCallbacksAndMessages(null); try { (web.parent as? ViewGroup)?.removeView(web); web.destroy() } catch (e: Exception) { }; super.onDestroy() }
}
