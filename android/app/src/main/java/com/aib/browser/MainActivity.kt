package com.aib.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
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
    private val prefs by lazy { getSharedPreferences("enhance", Context.MODE_PRIVATE) }

    private val toggles = linkedMapOf(
        "enabled" to "تفعيل التحسين على كل المواقع",
        "neural" to "رفع الدقة العصبي (Real-ESRGAN)",
        "tone" to "تباين HDR تكيفي",
        "temporal" to "استقرار زمني (يقلل الوميض)",
        "interp" to "توليد الإطارات (ثقيل)",
        "images" to "تحسين الصور"
    )
    private val defaults = mapOf("enabled" to true, "neural" to true, "temporal" to true, "tone" to true, "interp" to false, "images" to true)
    private val targets = intArrayOf(720, 1080, 1440, 2160)
    private val targetLabels = arrayOf("720p", "1080p", "2K", "4K")
    private val denoise = floatArrayOf(0f, 0.2f, 0.35f, 0.6f)
    private val denoiseLabels = arrayOf("بدون", "خفيف", "متوسط", "قوي")

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private fun pill(color: Int, r: Int = 24) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun config(): JSONObject {
        val o = JSONObject()
        for ((k, d) in defaults) o.put(k, prefs.getBoolean(k, d))
        o.put("target", prefs.getInt("target", 1440))
        o.put("denoise", prefs.getFloat("denoise", 0.35f).toDouble())
        return o
    }

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
            }
            override fun onHideCustomView() { exitFullscreen() }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
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
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (!urlBar.hasFocus()) urlBar.setText(url)
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) injectFallback()
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) injectFallback()
            }
        }
        registerScript()
        pollBadge()
        go(intent?.dataString ?: HOME)
    }

    private fun injectFallback() { // main frame only, used if document-start scripts are unsupported
        web.evaluateJavascript("if(!window.__minEnhance){window.__minCfg0=${config()};$bundle\n}", null)
    }

    private fun iconButton(label: String, size: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = size.toFloat(); gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        setOnClickListener { onClick() }
    }

    private fun pollBadge() { // shows ✨ with a violet ring while a video is being enhanced
        web.evaluateJavascript("(window.__minEnhance&&window.__minEnhance.state.stats.active)||0") { v ->
            val on = (v?.toIntOrNull() ?: 0) > 0
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
                setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(k, c).apply(); applyConfig() }
            })
            col.addView(row)
        }
        col.addView(label("الدقة المستهدفة"))
        col.addView(chips(targetLabels, targets.indexOf(prefs.getInt("target", 1440)).coerceAtLeast(0)) { i -> prefs.edit().putInt("target", targets[i]).apply(); applyConfig() })
        col.addView(label("إزالة الضوضاء وتكسّر الضغط"))
        col.addView(chips(denoiseLabels, denoise.indexOfFirst { it == prefs.getFloat("denoise", 0.35f) }.coerceAtLeast(2)) { i -> prefs.edit().putFloat("denoise", denoise[i]).apply(); applyConfig() })
        dlg.setContentView(ScrollView(this).apply { addView(col) })
        dlg.window?.apply {
            setBackgroundDrawable(pill(BG, 24)); setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
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
