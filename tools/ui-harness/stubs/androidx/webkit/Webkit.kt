package androidx.webkit
import android.content.Context
import android.content.pm.PackageInfo
import android.webkit.WebSettings
import android.webkit.WebView
interface ScriptHandler { fun remove() }
object WebViewFeature {
    const val DOCUMENT_START_SCRIPT = "DOCUMENT_START_SCRIPT"
    const val SAFE_BROWSING_ENABLE = "SAFE_BROWSING_ENABLE"
    const val ALGORITHMIC_DARKENING = "ALGORITHMIC_DARKENING"
    @JvmStatic fun isFeatureSupported(f: String) = false
}
object WebViewCompat {
    @JvmStatic fun addDocumentStartJavaScript(w: WebView, s: String, o: Set<String>): ScriptHandler = object : ScriptHandler { override fun remove() {} }
    @JvmStatic fun getCurrentWebViewPackage(c: Context): PackageInfo? = null
}
object WebSettingsCompat {
    @JvmStatic fun setSafeBrowsingEnabled(s: WebSettings, b: Boolean) {}
    @JvmStatic fun setAlgorithmicDarkeningAllowed(s: WebSettings, b: Boolean) {}
}
