import android.app.Dialog
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import com.aib.browser.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

/** Bridge limits (gestures, native player), the sign-in pop-up renderer crash, and the Android 8-9 download permission. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(shadows = [ShadowAppImageView::class], sdk = [34], packageName = "com.aib.browser", manifest = "../../android/app/src/main/AndroidManifest.xml")
class SafetyTest {
    private val sdk = Build.VERSION.SDK_INT
    @After fun restoreSdk() = ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", sdk)

    private fun start(): ActivityController<MainActivity> = Robolectric.buildActivity(MainActivity::class.java).also {
        it.get().setTheme(android.R.style.Theme_DeviceDefault_NoActionBar); it.setup()
    }
    private fun prefs(a: MainActivity) = a.getSharedPreferences("enhance", Context.MODE_PRIVATE)
    private fun am(a: MainActivity) = a.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private fun call(a: MainActivity, name: String, vararg args: Any?): Any? {
        val m = MainActivity::class.java.declaredMethods.first { it.name == name && it.parameterCount == args.size }
        m.isAccessible = true; return m.invoke(a, *args).also { ShadowLooper.idleMainLooper() }
    }

    @Test fun volumeAndBrightnessIgnoredWhenGesturesOff() {
        val a = start().get(); val b = a.Bridge(); val am = am(a)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        b.playing(true, 1920, 1080); ShadowLooper.idleMainLooper()
        val before = a.window.attributes.screenBrightness
        b.volume(1f); b.brightness(1f); ShadowLooper.idleMainLooper()
        assertEquals(5, am.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals(before, a.window.attributes.screenBrightness, 0f)
    }

    @Test fun volumeIgnoredWithoutVideoOrInBackground() {
        val ctl = start(); val a = ctl.get(); val b = a.Bridge(); val am = am(a)
        prefs(a).edit().putBoolean("gestures", true).commit()
        am.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        b.volume(1f) // no video playing
        assertEquals(5, am.getStreamVolume(AudioManager.STREAM_MUSIC))
        b.playing(true, 1920, 1080); ShadowLooper.idleMainLooper()
        ctl.pause()
        b.volume(1f) // not in front
        assertEquals(5, am.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test fun volumeStepClampedWhenAllowed() {
        val a = start().get(); val b = a.Bridge(); val am = am(a)
        prefs(a).edit().putBoolean("gestures", true).commit()
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        b.playing(true, 1920, 1080); ShadowLooper.idleMainLooper()
        b.volume(1f)
        val v = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        assertTrue("volume moved", v > 0)
        assertEquals(Math.round(0.15f * max), v)
        val cur = b.brightness(-1f); ShadowLooper.idleMainLooper() // one step down from the phone's brightness
        val next = b.brightness(1f); ShadowLooper.idleMainLooper()
        assertEquals(minOf(1f, cur + 0.15f), next, 1e-4f)
        assertEquals(next, a.window.attributes.screenBrightness, 1e-4f)
    }

    @Test fun openNativeOnlyWhenResumedAndOnce() {
        val ctl = start(); val a = ctl.get(); val b = a.Bridge()
        ctl.pause()
        b.openNative("https://example.com/film.mp4", 0.0); ShadowLooper.idleMainLooper()
        assertFalse(b.autoNative("https://example.com/film.mp4", 0.0))
        assertNull(shadowOf(a).nextStartedActivityForResult)
        ctl.resume()
        b.openNative("https://example.com/film.mp4", 0.0); ShadowLooper.idleMainLooper()
        assertNotNull(shadowOf(a).nextStartedActivityForResult)
        b.openNative("https://example.com/film.mp4", 0.0); ShadowLooper.idleMainLooper()
        assertFalse(b.autoNative("https://example.com/film.mp4", 0.0))
        assertNull("second call ignored while the native player is open", shadowOf(a).nextStartedActivityForResult)
    }

    @Test fun signInPopupRendererCrashClosesSheetOnly() {
        val a = start().get()
        val web = MainActivity::class.java.getDeclaredField("web").apply { isAccessible = true }.get(a) as WebView
        val chrome = shadowOf(web).webChromeClient!!
        val transport = web.WebViewTransport()
        val msg = Message.obtain(Handler(Looper.getMainLooper())).apply { obj = transport }
        assertTrue(chrome.onCreateWindow(web, false, true, msg))
        val pw = transport.webView!!
        call(a, "showPopup", pw)
        val dlg = pw.tag as Dialog
        assertTrue(dlg.isShowing)
        val detail = object : RenderProcessGoneDetail() { override fun didCrash() = true; override fun rendererPriorityAtExit() = 0 }
        assertTrue("handled, app keeps running", shadowOf(pw).webViewClient!!.onRenderProcessGone(pw, detail))
        assertFalse(dlg.isShowing)
        assertFalse(a.isFinishing)
    }

    private fun askedDownload(): Pair<MainActivity, org.robolectric.shadows.ShadowActivity.PermissionsRequest> {
        val a = start().get()
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 28)
        call(a, "download", "https://example.com/file.zip", "UA", null, "application/zip")
        val req = shadowOf(a).lastRequestedPermission
        assertEquals(43, req.requestCode)
        assertEquals(android.Manifest.permission.WRITE_EXTERNAL_STORAGE, req.requestedPermissions[0])
        return a to req
    }

    @Test fun downloadDeniedBeforeAndroid10ShowsToast() {
        val (a, req) = askedDownload()
        a.onRequestPermissionsResult(43, req.requestedPermissions, intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED))
        ShadowLooper.idleMainLooper()
        assertEquals("لا يمكن التحميل بدون إذن التخزين", ShadowToast.getTextOfLatestToast())
    }

    @Test fun downloadGrantedBeforeAndroid10Continues() {
        val (a, req) = askedDownload()
        assertNull("nothing started before the answer", ShadowToast.getTextOfLatestToast())
        shadowOf(a).grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        a.onRequestPermissionsResult(43, req.requestedPermissions, intArrayOf(android.content.pm.PackageManager.PERMISSION_GRANTED))
        ShadowLooper.idleMainLooper()
        assertTrue(ShadowToast.getTextOfLatestToast().startsWith("بدأ التحميل"))
    }

    @Test fun downloadNeedsNoPermissionOnAndroid10Plus() {
        val a = start().get()
        call(a, "download", "https://example.com/file.zip", "UA", null, "application/zip")
        assertNull(shadowOf(a).lastRequestedPermission)
        assertTrue(ShadowToast.getTextOfLatestToast().startsWith("بدأ التحميل"))
    }
}
