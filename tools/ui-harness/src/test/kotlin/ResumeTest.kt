import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.aib.browser.MainActivity
import com.aib.browser.Resume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** "Continue watching": Resume rules, and films played in the site's own player (Bridge.position) */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], packageName = "com.aib.browser", manifest = "../../android/app/src/main/AndroidManifest.xml")
class ResumeTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val min = 60_000L

    @Test fun resumeRules() {
        Resume.save(app, "https://s.com/clip", "clip", 2 * min, 4 * min)           // shorter than 5 min: ignored
        assertEquals(0L, Resume.position(app, "https://s.com/clip"))
        Resume.save(app, "https://s.com/film#t=1", "film", 40 * min, 100 * min)    // #fragment is not part of the key
        assertEquals(40 * min, Resume.position(app, "https://s.com/film"))
        assertEquals(40 * min, Resume.position(app, "https://s.com/film#player"))
        assertEquals(listOf("https://s.com/film"), Resume.list(app).map { it.page })
        Resume.save(app, "https://s.com/film", "film", 96 * min, 100 * min)        // finished: removed
        assertEquals(0L, Resume.position(app, "https://s.com/film"))
        Resume.save(app, "https://s.com/film", "film", 40 * min, 100 * min)
        Resume.save(app, "https://s.com/film", "film", 10_000L, 100 * min)         // restarted (<30 s): removed
        assertEquals(0L, Resume.position(app, "https://s.com/film"))
        Resume.save(app, "https://s.com/film", "film", 40 * min, 100 * min)
        Resume.remove(app, "https://s.com/film#x")
        assertTrue(Resume.list(app).isEmpty())
    }

    private fun field(a: MainActivity, name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun set(a: MainActivity, name: String, v: Any?) = field(a, name).set(a, v)
    private fun get(a: MainActivity, name: String): Any? = field(a, name).get(a)

    @Test fun pagePlayer() {
        val ctl = Robolectric.buildActivity(MainActivity::class.java)
        ctl.get().setTheme(android.R.style.Theme_DeviceDefault_NoActionBar)
        ctl.setup()
        val a = ctl.get()
        val page = "https://films.example/watch/42"
        set(a, "pageUrl", page); set(a, "pageTitle", "فيلم تجريبي")
        val bc = Class.forName("com.aib.browser.MainActivity\$Bridge")
        val bridge = bc.getDeclaredConstructor(MainActivity::class.java).apply { isAccessible = true }.newInstance(a)
        val position = bc.getMethod("position", Double::class.java, Double::class.java)

        position.invoke(bridge, 600.0, 3600.0)
        assertEquals(600_000L, Resume.position(app, page))
        assertEquals("فيلم تجريبي", Resume.list(app).single().title)
        assertEquals(600.0, get(a, "sessPos")); assertEquals(page, get(a, "sessPage"))
        position.invoke(bridge, 605.0, 3600.0)                                       // throttled: at most every 10 s
        assertEquals(600_000L, Resume.position(app, page))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(11))
        position.invoke(bridge, 620.0, 3600.0)
        assertEquals(620_000L, Resume.position(app, page))

        // the continue-watching row reloads the page and arms the seek to the saved position
        a.javaClass.getDeclaredMethod("showContinue").apply { isAccessible = true }.invoke(a); ShadowLooper.idleMainLooper()
        val row = find(ShadowDialog.getLatestDialog().window!!.decorView) { it is TextView && it.text.toString() == "فيلم تجريبي" }!!
        var v: View = row; while (!v.isClickable) v = v.parent as View
        v.performClick(); ShadowLooper.idleMainLooper()
        assertEquals(page to 620.0, get(a, "pendingSeek"))

        // finished (last 20 s): no session seek, and it leaves "continue watching"
        position.invoke(bridge, 3590.0, 3600.0)
        assertEquals(0.0, get(a, "sessPos")); assertNull(get(a, "sessPage"))
        assertTrue(Resume.list(app).isEmpty())
        // a live stream (infinite duration) changes nothing
        position.invoke(bridge, 900.0, Double.POSITIVE_INFINITY)
        assertTrue(Resume.list(app).isEmpty()); assertNull(get(a, "sessPage"))
    }

    private fun find(v: View, p: (View) -> Boolean): View? {
        if (p(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), p)?.let { return it }
        return null
    }
}
