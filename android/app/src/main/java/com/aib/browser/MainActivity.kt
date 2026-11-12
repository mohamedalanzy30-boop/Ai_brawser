package com.aib.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONObject
import java.io.FileNotFoundException

private const val ASSET_HOST = "appassets.min.local"

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var urlBar: EditText
    private val prefs by lazy { getSharedPreferences("enhance", Context.MODE_PRIVATE) }

    private val toggles = linkedMapOf(
        "enabled" to "تفعيل التحسين / Enable",
        "neural" to "رفع الدقة العصبي / Neural SR",
        "temporal" to "استقرار زمني / Temporal stabilise",
        "tone" to "تباين HDR تكيفي / Adaptive tone",
        "interp" to "توليد الإطارات / Frame interpolation (heavy)",
        "images" to "الصور / Images"
    )
    private val defaults = mapOf("enabled" to true, "neural" to true, "temporal" to true, "tone" to true, "interp" to false, "images" to true)
    private val targets = intArrayOf(720, 1080, 1440, 2160)
    private val denoiseLevels = floatArrayOf(0f, 0.2f, 0.35f, 0.6f)

    private fun config(): JSONObject {
        val o = JSONObject()
        for ((k, d) in defaults) o.put(k, prefs.getBoolean(k, d))
        o.put("target", prefs.getInt("target", 1440))
        o.put("denoise", prefs.getFloat("denoise", 0.35f).toDouble())
        return o
    }

    private fun asset(path: String): String = assets.open(path).bufferedReader().use { it.readText() }

    private val engineJs by lazy { asset("engine.js") }
    private val ortJs by lazy { asset("ort/ort.webgpu.min.js") }

    private fun inject(v: WebView) {
        v.evaluateJavascript("if(!window.ort){$ortJs\n}", null)
        v.evaluateJavascript(engineJs, null)
        v.evaluateJavascript("window.__minEnhanceSet(${config()})", null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        urlBar = EditText(this).apply {
            setSingleLine(); hint = "URL"; imeOptions = EditorInfo.IME_ACTION_GO
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, _, _ -> go(text.toString()); true }
        }
        val back = Button(this).apply { text = "◀"; setOnClickListener { if (web.canGoBack()) web.goBack() } }
        val enhance = Button(this).apply { text = "✨"; setOnClickListener { showSettings() } }
        bar.addView(back); bar.addView(urlBar); bar.addView(enhance)
        web = WebView(this)
        root.addView(bar, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(web, ViewGroup.LayoutParams.MATCH_PARENT, 0)
        (web.layoutParams as LinearLayout.LayoutParams).weight = 1f
        setContentView(root)

        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                if (u.host != ASSET_HOST) return null
                val path = u.path.orEmpty().trimStart('/')
                return try {
                    val mime = when {
                        path.endsWith(".wasm") -> "application/wasm"
                        path.endsWith(".js") || path.endsWith(".mjs") -> "text/javascript"
                        else -> "application/octet-stream"
                    }
                    WebResourceResponse(mime, null, 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"), assets.open(path))
                } catch (e: FileNotFoundException) {
                    WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf("Access-Control-Allow-Origin" to "*"), "".byteInputStream())
                }
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { urlBar.setText(url); inject(view) }
            override fun onPageFinished(view: WebView, url: String) { inject(view) }
        }
        go(intent?.dataString ?: "https://www.youtube.com")
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

    private fun showSettings() {
        val keys = toggles.keys.toList()
        val checked = BooleanArray(keys.size) { prefs.getBoolean(keys[it], defaults[keys[it]] == true) }
        AlertDialog.Builder(this)
            .setTitle("رفع الدقة والتحسين العصبي")
            .setMultiChoiceItems(keys.map { toggles[it] }.toTypedArray(), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("الدقة / Resolution") { _, _ ->
                keys.forEachIndexed { i, k -> prefs.edit().putBoolean(k, checked[i]).apply() }
                showTarget()
            }
            .setNegativeButton("حفظ / Save") { _, _ ->
                keys.forEachIndexed { i, k -> prefs.edit().putBoolean(k, checked[i]).apply() }
                web.evaluateJavascript("window.__minEnhanceSet(${config()})", null)
            }.show()
    }

    private fun showTarget() {
        val labels = arrayOf("720p", "1080p", "1440p (2K)", "2160p (4K)")
        val cur = targets.indexOf(prefs.getInt("target", 1440)).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("الدقة المستهدفة")
            .setSingleChoiceItems(labels, cur) { d, i ->
                prefs.edit().putInt("target", targets[i]).apply(); d.dismiss(); showDenoise()
            }.show()
    }

    private fun showDenoise() {
        val labels = arrayOf("بدون", "خفيف", "متوسط", "قوي")
        val cur = denoiseLevels.indexOfFirst { it == prefs.getFloat("denoise", 0.35f) }.coerceAtLeast(2)
        AlertDialog.Builder(this).setTitle("إزالة الضوضاء وتكسّر الضغط")
            .setSingleChoiceItems(labels, cur) { d, i ->
                prefs.edit().putFloat("denoise", denoiseLevels[i]).apply(); d.dismiss()
                web.evaluateJavascript("window.__minEnhanceSet(${config()})", null)
            }.show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && web.canGoBack()) { web.goBack(); return true }
        return super.onKeyDown(keyCode, event)
    }
}
