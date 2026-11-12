package com.aib.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.FileNotFoundException

private const val ASSET_HOST = "appassets.min.local"
private const val HOME = "https://www.google.com"
private const val BG = 0xFF0F0F0F.toInt()
private const val SURFACE = 0xFF1F1F1F.toInt()
private const val ACCENT = 0xFF7C5CFF.toInt()

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var badge: TextView
    private lateinit var content: FrameLayout
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var scriptHandler: ScriptHandler? = null
    private val ui = Handler(Looper.getMainLooper())
    private val logLines = ArrayDeque<String>()
    private val timeFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    private fun addLog(line: String) {
        synchronized(logLines) {
            val last = logLines.lastOrNull()
            if (last != null && last.substringAfter(' ').replace(Regex(" ×\\d+$"), "") == line) {
                val n = Regex(" ×(\\d+)$").find(last)?.groupValues?.get(1)?.toInt() ?: 1
                logLines.removeLast(); logLines.addLast("${timeFmt.format(java.util.Date())} $line ×${n + 1}")
                return
            }
            logLines.addLast("${timeFmt.format(java.util.Date())} $line")
            while (logLines.size > 400) logLines.removeFirst()
        }
    }

    private fun logText(): String {
        val wv = WebViewCompat.getCurrentWebViewPackage(this)
        val d = windowManager.defaultDisplay
        val hdr = d.hdrCapabilities?.supportedHdrTypes?.joinToString { when (it) { 1 -> "DolbyVision"; 2 -> "HDR10"; 3 -> "HLG"; 4 -> "HDR10+"; else -> "$it" } } ?: "none"
        val rates = d.supportedModes.map { Math.round(it.refreshRate) }.distinct().sorted().joinToString("/")
        val m = android.util.DisplayMetrics().also { d.getRealMetrics(it) }
        val disp = "display=${m.widthPixels}x${m.heightPixels} dpi=${m.densityDpi} refresh=${Math.round(d.refreshRate)}Hz modes=$rates hdr=$hdr wideGamut=${d.isWideColorGamut}"
        val head = "$disp\nAI Browser 0.1 | ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} | Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) | WebView ${wv?.versionName}\nconfig=${config()}\nadblock=${if (adblockOn) "on" else "off"} hosts=${adHosts.size} blocked=$blocked\nurl=${web.url}\n"
        return head + synchronized(logLines) { logLines.joinToString("\n") }
    }
    private val prefs by lazy { getSharedPreferences("enhance", Context.MODE_PRIVATE) }

    private val toggles = linkedMapOf(
        "enabled" to "تفعيل التحسين على كل المواقع",
        "neural" to "رفع الدقة العصبي (Real-ESRGAN)",
        "tone" to "تباين HDR تكيفي",
        "temporal" to "استقرار زمني (يقلل الوميض)",
        "interp" to "توليد الإطارات (ثقيل)",
        "images" to "تحسين الصور",
        "adblock" to "حظر الإعلانات والمتتبعات (أسرع وأخف)",
        "refresh" to "مطابقة معدل تحديث الشاشة للفيديو (حتى 120Hz)",
        "brightness" to "سطوع أعلى في ملء الشاشة"
    )
    private val defaults = mapOf("enabled" to true, "neural" to true, "temporal" to true, "tone" to true, "interp" to false, "images" to true, "adblock" to true, "refresh" to true, "brightness" to false)
    private val targets = intArrayOf(0, 720, 1080, 1440, 2160)
    private val targetLabels = arrayOf("تلقائي", "720p", "1080p", "2K", "4K")
    private val denoise = floatArrayOf(0f, 0.2f, 0.35f, 0.6f)
    private val denoiseLabels = arrayOf("بدون", "خفيف", "متوسط", "قوي")

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private fun pill(color: Int, r: Int = 24) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun config(): JSONObject {
        val o = JSONObject()
        for ((k, d) in defaults) o.put(k, prefs.getBoolean(k, d))
        o.put("target", prefs.getInt("target", 0))
        o.put("denoise", prefs.getFloat("denoise", 0.35f).toDouble())
        return o
    }

    /* ---------- ad / tracker blocking: bundled host list, no network needed ---------- */
    @Volatile private var adHosts: Set<String> = emptySet()
    @Volatile private var blocked = 0
    private fun loadAdHosts() = Thread {
        adHosts = try { assets.open("adhosts.txt").bufferedReader().useLines { l -> l.filter { it.isNotBlank() }.toHashSet() } } catch (e: Exception) { emptySet() }
        addLog("[AIB] ad blocker: ${adHosts.size} hosts loaded")
    }.start()
    private fun isAd(host: String?): Boolean {
        if (host == null || adHosts.isEmpty()) return false
        var h: String = host.lowercase()
        while (true) { // also matches subdomains (x.ads.example.com -> ads.example.com)
            if (h in adHosts) return true
            val i = h.indexOf('.'); if (i < 0 || h.indexOf('.', i + 1) < 0) return false
            h = h.substring(i + 1)
        }
    }
    @Volatile private var adblockOn = true
    private var pageHost: String? = null

    private fun asset(path: String): String = assets.open(path).bufferedReader().use { it.readText() }
    private val bundle by lazy { asset("ort/ort.webgpu.min.js") + "\n;" + asset("engine.js") }

    /** Engine + ONNX Runtime injected at document start into EVERY frame (also embedded players in iframes). */
    private fun registerScript() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        scriptHandler?.remove()
        scriptHandler = WebViewCompat.addDocumentStartJavaScript(
            web, "window.__minCfg0=${config()};\n$bundle", setOf("*")
        )
    }

    private fun applyConfig() {
        registerScript()
        web.evaluateJavascript("window.__minEnhanceSet&&window.__minEnhanceSet(${config()})", null)
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.decorView.systemUiVisibility = 0

        content = FrameLayout(this).apply { setBackgroundColor(BG) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE
            progressDrawable.setTint(ACCENT)
        }
        web = WebView(this).apply { setBackgroundColor(BG) }

        // bottom bar (thumb friendly): back | address | enhance
        val back = iconButton("‹", 30) { if (web.canGoBack()) web.goBack() }
        urlBar = EditText(this).apply {
            setSingleLine(); hint = "ابحث أو اكتب عنوان"; setHintTextColor(0xFF8A8A8A.toInt()); setTextColor(Color.WHITE)
            textSize = 15f; background = pill(SURFACE, 22); setPadding(dp(16), 0, dp(16), 0)
            imeOptions = EditorInfo.IME_ACTION_GO; textDirection = View.TEXT_DIRECTION_LTR
            setOnEditorActionListener { _, _, _ -> go(text.toString()); clearFocus(); true }
            setOnFocusChangeListener { _, f -> if (f) post { selectAll() } }
        }
        badge = TextView(this).apply {
            text = "✨"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = pill(SURFACE, 22); setOnClickListener { showSettings() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BG); setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        bar.addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(urlBar, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(6), 0, dp(6), 0) })
        bar.addView(badge, LinearLayout.LayoutParams(dp(56), dp(44)))

        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(content)

        // Android 15 draws edge-to-edge: keep content clear of the system bars
        content.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsets.CONSUMED
        }

        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
            useWideViewPort = true; loadWithOverviewMode = true
            // use the phone's system font (e.g. Samsung One UI font) for pages that do not ship their own
            standardFontFamily = "sans-serif"; sansSerifFontFamily = "sans-serif"; fixedFontFamily = "monospace"; serifFontFamily = "serif"
            textZoom = 100
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                progress.progress = p; progress.visibility = if (p in 1..99) View.VISIBLE else View.GONE
            }
            override fun onShowCustomView(view: View, cb: CustomViewCallback) { // fullscreen video
                customView = view; customCallback = cb
                content.addView(view, FrameLayout.LayoutParams(-1, -1))
                content.setPadding(0, 0, 0, 0)
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                if (prefs.getBoolean("brightness", false)) { val lp = window.attributes; lp.screenBrightness = 1f; window.attributes = lp }
            }
            override fun onHideCustomView() { exitFullscreen() }
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                val noise = m.message().startsWith("Permissions policy violation")
                val interesting = !noise && (m.message().startsWith("[AIB]") || m.messageLevel() == ConsoleMessage.MessageLevel.ERROR)
                if (interesting) addLog("[${m.messageLevel()}] ${m.message()} (${m.sourceId().substringAfterLast('/')}:${m.lineNumber()})")
                return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                if (adblockOn && !request.isForMainFrame && isAd(u.host) && !isAd(pageHost)) {
                    blocked++
                    return WebResourceResponse("text/plain", "utf-8", 204, "No Content", mapOf("Access-Control-Allow-Origin" to "*"), "".byteInputStream())
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
            }
            override fun onReceivedHttpError(view: WebView, req: WebResourceRequest, resp: WebResourceResponse) {
                if (req.url.host == ASSET_HOST || req.isForMainFrame) addLog("[HTTP ${resp.statusCode}] ${req.url}")
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                addLog("[CRASH] WebView renderer gone, didCrash=${detail.didCrash()}")
                return true
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                // pop-under / redirect ads: a frame or the page jumping to an ad host is dropped
                if (adblockOn && isAd(req.url.host) && !isAd(pageHost)) { blocked++; addLog("[AIB] blocked ad redirect ${req.url.host}"); return true }
                return false
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                pageHost = android.net.Uri.parse(url).host
                if (!urlBar.hasFocus()) urlBar.setText(url)
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) injectFallback()
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) injectFallback()
            }
        }
        // keep the page's renderer (video decoding + audio) at high priority so playback does not crackle
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        adblockOn = prefs.getBoolean("adblock", true)
        loadAdHosts()
        registerScript()
        pollBadge()
        go(intent?.dataString ?: HOME)
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

    private fun injectFallback() { // main frame only, used if document-start scripts are unsupported
        web.evaluateJavascript("if(!window.__minEnhance){window.__minCfg0=${config()};$bundle\n}", null)
    }

    private fun iconButton(label: String, size: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = size.toFloat(); gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        setOnClickListener { onClick() }
    }

    /** Picks the lowest supported refresh rate that is an integer multiple of the video fps (24->48, 30->60, 60->60/120). */
    private fun matchRefresh(fps: Int) {
        val lp = window.attributes
        val rate = if (fps <= 0 || !prefs.getBoolean("refresh", true)) 0f else {
            val rates = windowManager.defaultDisplay.supportedModes.map { it.refreshRate }.distinct().sorted()
            rates.firstOrNull { r -> r >= fps - 1 && Math.abs(r / fps - Math.round(r / fps)) < 0.04f } ?: (rates.maxOrNull() ?: 0f)
        }
        if (lp.preferredRefreshRate != rate) { lp.preferredRefreshRate = rate; window.attributes = lp }
    }

    private fun pollBadge() { // shows ✨ with a violet ring while a video is being enhanced
        web.evaluateJavascript("(function(){var s=window.__minEnhance&&window.__minEnhance.state.stats;return JSON.stringify({a:(s&&s.active)||0,f:(s&&s.fps)||0})})()") { raw ->
            val j = try { JSONObject(org.json.JSONTokener(raw).nextValue() as String) } catch (e: Exception) { JSONObject() }
            val on = j.optInt("a") > 0
            matchRefresh(if (on) j.optInt("f") else 0)
            badge.background = pill(if (on) ACCENT else SURFACE, 22)
            badge.text = if (on) "✨ ON" else "✨"
            badge.textSize = if (on) 13f else 20f
        }
        ui.postDelayed({ pollBadge() }, 2000)
    }

    private fun go(input: String) {
        val t = input.trim()
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
        window.decorView.systemUiVisibility = 0
        window.attributes = window.attributes.also { it.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        content.requestApplyInsets()
    }

    private fun chips(labels: Array<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val views = ArrayList<TextView>()
        fun paint(sel: Int) = views.forEachIndexed { i, v ->
            v.background = pill(if (i == sel) ACCENT else SURFACE, 18); v.setTextColor(Color.WHITE)
        }
        labels.forEachIndexed { i, l ->
            val v = TextView(this).apply {
                text = l; textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                setOnClickListener { paint(i); onPick(i) }
            }
            views.add(v)
            row.addView(v, LinearLayout.LayoutParams(0, dp(38), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        paint(selected)
        return row
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(0xFF9A9A9A.toInt()); setPadding(dp(4), dp(16), dp(4), dp(6))
    }

    private fun showSettings() {
        val dlg = Dialog(this)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(18)); background = pill(BG, 24) }
        col.addView(TextView(this).apply { text = "✨ تحسين الفيديو والصور"; textSize = 19f; setTextColor(Color.WHITE) })
        col.addView(TextView(this).apply { text = "يعمل على جهازك بدون إنترنت إضافي"; textSize = 12f; setTextColor(0xFF9A9A9A.toInt()); setPadding(0, dp(2), 0, dp(8)) })
        for ((k, title) in toggles) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
            row.addView(TextView(this).apply { text = title; textSize = 15f; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Switch(this).apply {
                isChecked = prefs.getBoolean(k, defaults[k] == true)
                setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(k, c).apply(); adblockOn = prefs.getBoolean("adblock", true); applyConfig() }
            })
            col.addView(row)
        }
        col.addView(label("الدقة المستهدفة"))
        col.addView(chips(targetLabels, targets.indexOf(prefs.getInt("target", 0)).coerceAtLeast(0)) { i -> prefs.edit().putInt("target", targets[i]).apply(); applyConfig() })
        col.addView(label("إزالة الضوضاء وتكسّر الضغط"))
        col.addView(chips(denoiseLabels, denoise.indexOfFirst { it == prefs.getFloat("denoise", 0.35f) }.coerceAtLeast(2)) { i -> prefs.edit().putFloat("denoise", denoise[i]).apply(); applyConfig() })
        col.addView(label("التشخيص"))
        col.addView(TextView(this).apply {
            text = "📋 سجل الأخطاء (للنسخ)"; textSize = 15f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = pill(SURFACE, 18); setOnClickListener { dlg.dismiss(); showLog() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        dlg.setContentView(ScrollView(this).apply { addView(col) })
        dlg.window?.apply {
            setBackgroundDrawable(pill(BG, 24)); setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dlg.show()
    }

    private fun showLog() {
        val dlg = Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG); setPadding(dp(12), dp(12), dp(12), dp(12)) }
        val txt = TextView(this).apply { text = logText(); textSize = 11f; setTextColor(0xFFD0D0D0.toInt()); setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE }
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, f: () -> Unit) = TextView(this).apply {
            text = t; textSize = 15f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = pill(ACCENT, 18); setOnClickListener { f() }
        }
        btns.addView(btn("نسخ الكل") {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("AI Browser log", logText()))
            android.widget.Toast.makeText(this, "تم النسخ", android.widget.Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(0, 0, dp(6), 0) })
        btns.addView(btn("مسح") { synchronized(logLines) { logLines.clear() }; txt.text = logText() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(6), 0, dp(6), 0) })
        btns.addView(btn("إغلاق") { dlg.dismiss() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(6), 0, 0, 0) })
        col.addView(btns)
        col.addView(ScrollView(this).apply { addView(txt) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })
        dlg.setContentView(col)
        dlg.show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (customView != null) { exitFullscreen(); return true }
            if (web.canGoBack()) { web.goBack(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() { ui.removeCallbacksAndMessages(null); super.onDestroy() }
}
