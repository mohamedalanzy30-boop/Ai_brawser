import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.KeyEvent
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import com.aib.browser.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** Back key, links from other apps, protection of app-started loads and tapped links in new windows, on the real MainActivity. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], packageName = "com.aib.browser", manifest = "../../android/app/src/main/AndroidManifest.xml")
class NavTests {
    private lateinit var ctl: ActivityController<MainActivity>
    private fun start(): MainActivity {
        ctl = Robolectric.buildActivity(MainActivity::class.java)
        ctl.get().setTheme(android.R.style.Theme_DeviceDefault_NoActionBar)
        ctl.setup()
        ShadowLooper.idleMainLooper(2000, TimeUnit.MILLISECONDS)
        return ctl.get()
    }
    private fun field(a: Any, n: String) = a.javaClass.getDeclaredField(n).apply { isAccessible = true }
    private fun web(a: MainActivity) = field(a, "web").get(a) as WebView
    private fun press(a: MainActivity, holdMs: Long) {
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        ShadowLooper.idleMainLooper(holdMs, TimeUnit.MILLISECONDS)
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        ShadowLooper.idleMainLooper()
    }
    private fun req(url: String, main: Boolean = true) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    @Test fun shortBackAtFirstPageCloses() {
        val a = start()
        assertTrue(web(a).copyBackForwardList().currentIndex <= 0)
        press(a, 100)
        assertTrue("a 100 ms back press at the first page closes the app", a.isFinishing)
    }
    @Test fun longBackAtFirstPageCloses() {
        val a = start(); press(a, 600)
        assertTrue(a.isFinishing)
    }
    @Test fun quickSecondBackIgnored() {
        val a = start()
        val m = a.javaClass.getDeclaredMethod("startEdit").apply { isAccessible = true }; m.invoke(a); ShadowLooper.idleMainLooper()
        val edit = field(a, "urlEdit").get(a) as View
        assertEquals(View.VISIBLE, edit.visibility)
        press(a, 100) // one press = one action: the address field closes, the app stays
        assertEquals(View.GONE, edit.visibility); assertFalse(a.isFinishing)
        press(a, 50) // the same back delivered again right away (some phones): ignored
        assertFalse("a second back within 450 ms is ignored", a.isFinishing)
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
        press(a, 100) // a real next press at the first page closes
        assertTrue(a.isFinishing)
    }
    @Test fun linkFromOtherAppOpensInSameActivityProtected() {
        val a = start()
        ctl.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com/a?utm_source=x&id=1")))
        ShadowLooper.idleMainLooper()
        assertEquals("http://example.com/a?utm_source=x&id=1", a.intent.dataString)
        assertEquals("https://example.com/a?id=1", shadowOf(web(a)).lastLoadedUrl)
        ctl.newIntent(Intent(Intent.ACTION_MAIN)); ShadowLooper.idleMainLooper() // launcher: no navigation
        assertEquals("https://example.com/a?id=1", shadowOf(web(a)).lastLoadedUrl)
    }
    @Test fun adultMainFrameBlockedForEveryLoad() {
        val a = start()
        field(a, "adultHosts").set(a, setOf("bad.example")); field(a, "adultOn").set(a, true)
        val client = shadowOf(web(a)).webViewClient
        val r = client.shouldInterceptRequest(web(a), req("https://www.bad.example/x"))!!
        assertEquals("text/html", r.mimeType)
        assertTrue(r.data.readBytes().toString(Charsets.UTF_8).contains("www.bad.example"))
        assertEquals(null, client.shouldInterceptRequest(web(a), req("https://good.example/")))
    }
    @Test fun tappedCrossSiteLinkOpensPopUnderBlocked() {
        val a = start()
        val w = web(a)
        field(a, "pageHost").set(a, "www.google.com")
        val bridge = shadowOf(w).getJavascriptInterface("AIBNative")!!
        fun newWindow(url: String): Boolean { // true if the window's address was loaded in the tab
            val t = w.WebViewTransport()
            val msg = Message.obtain(Handler(Looper.getMainLooper())).apply { obj = t }
            assertTrue(shadowOf(w).webChromeClient.onCreateWindow(w, false, true, msg))
            val pw = t.webView
            val before = shadowOf(w).lastLoadedUrl
            assertTrue(shadowOf(pw).webViewClient.shouldOverrideUrlLoading(pw, req(url)))
            ShadowLooper.idleMainLooper()
            return shadowOf(w).lastLoadedUrl != before && shadowOf(w).lastLoadedUrl == url
        }
        assertFalse("untapped window to another site is dropped", newWindow("https://other.example/page"))
        bridge.javaClass.getMethod("linkTap", String::class.java).invoke(bridge, "https://other.example/story#top")
        assertFalse("a script's window to a different site on the same tap is dropped", newWindow("https://pop.example/ad"))
        assertTrue("the tapped target=_blank link opens", newWindow("https://other.example/story"))
        assertFalse("a tap opens one window only", newWindow("https://other.example/story"))
        bridge.javaClass.getMethod("linkTap", String::class.java).invoke(bridge, "https://late.example/")
        ShadowLooper.idleMainLooper(2500, TimeUnit.MILLISECONDS)
        assertFalse("an old tap does not count", newWindow("https://late.example/"))
        assertNotEquals(0, field(a, "popupsBlocked").getInt(a))
    }
}
