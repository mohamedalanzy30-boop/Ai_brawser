import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.aib.browser.MainActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.io.File

@RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [ShadowAppImageView::class], sdk = [34], qualifiers = "w412dp-h892dp-port-xxhdpi", packageName = "com.aib.browser", manifest = "../../android/app/src/main/AndroidManifest.xml")
class Shots {
    private val out = File(System.getProperty("shots")).apply { mkdirs() }
    private val tag = System.getenv("TAG") ?: "after"

    private fun call(a: Activity, name: String, vararg args: Any?) {
        val m = a.javaClass.declaredMethods.first { it.name == name && it.parameterCount == args.size }
        m.isAccessible = true; m.invoke(a, *args)
        ShadowLooper.idleMainLooper()
    }

    private fun render(v: View, w: Int, h: Int): Bitmap {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b)); return b
    }

    private fun save(b: Bitmap, name: String) {
        File(out, "${tag}_$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SHOT ${tag}_$name")
    }

    private fun shot(a: Activity, name: String, dlg: Dialog? = null) {
        val dm = a.resources.displayMetrics
        val w = dm.widthPixels; val h = dm.heightPixels
        val base = render(a.window.decorView, w, h)
        if (dlg != null) {
            val c = Canvas(base)
            c.drawColor((0.45f * 255).toInt() shl 24)
            val lp = dlg.window!!.attributes
            val dv = dlg.window!!.decorView
            val dh = if (lp.height > 0) lp.height else { dv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST)); dv.measuredHeight }
            val d = render(dv, w, dh)
            c.drawBitmap(d, 0f, (h - dh).toFloat(), null)
        }
        save(base, name)
    }

    private fun seed() {
        val app = RuntimeEnvironment.getApplication()
        if (System.getenv("SEED") == null) return
        val now = System.currentTimeMillis()
        val h = org.json.JSONArray()
        listOf("https://en.wikipedia.org/wiki/Video_super-resolution" to "Video super-resolution - Wikipedia",
               "https://www.youtube.com/watch?v=abc" to "مراجعة جالكسي S25 الترا: هل يستحق الترقية؟ تجربة كاملة بعد شهر من الاستخدام اليومي",
               "https://www.google.com/search?q=x" to "", "https://github.com/" to "GitHub").forEachIndexed { i, (u, t) -> h.put(org.json.JSONObject().put("u", u).put("t", t).put("d", now - i * 3_600_000L)) }
        java.io.File(app.filesDir, "history.json").writeText(h.toString())
        com.aib.browser.Resume.save(app, "https://example.com/movie1", "فيلم الرحلة الطويلة", 40 * 60_000L, 120 * 60_000L)
        com.aib.browser.Resume.save(app, "https://example.com/ep2", "", 10 * 60_000L, 45 * 60_000L)
        java.io.File(app.filesDir, "saved").mkdirs(); java.io.File(app.filesDir, "saved/مقالة محفوظة عن رفع الدقة_1700000000000.mht").writeText("x")
    }
    private fun run(lang: String) {
        seed()
        if (lang == "ar") RuntimeEnvironment.setQualifiers("+ar-rSA-ldrtl")
        val ctl = Robolectric.buildActivity(MainActivity::class.java)
        ctl.get().setTheme(android.R.style.Theme_DeviceDefault_NoActionBar)
        ctl.get().applicationInfo.flags = ctl.get().applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
        ctl.setup()
        println("LAYOUTDIR $lang " + ctl.get().resources.configuration.layoutDirection)
        val a = ctl.get()
        call(a, "showAddress", "https://en.wikipedia.org/wiki/Video_super-resolution")
        shot(a, "${lang}_01_page")
        call(a, "startEdit")
        shot(a, "${lang}_02_edit")
        call(a, "endEdit")
        for ((i, s) in listOf("showSettings", "showHistory", "showDownloads", "showSaved", "showContinue", "showLog").withIndex()) {
            call(a, s)
            val d = ShadowDialog.getLatestDialog()
            shot(a, "${lang}_0${i + 3}_$s", d)
            // the whole sheet content, unclipped (to read every row)
            val sv = findScroll(d.window!!.decorView)
            if (sv != null) { val col = sv.getChildAt(0); val w = a.resources.displayMetrics.widthPixels - sv.paddingLeft
                col.measure(View.MeasureSpec.makeMeasureSpec(sv.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                val full = Bitmap.createBitmap(sv.width.coerceAtLeast(1), col.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                col.layout(0, 0, col.measuredWidth, col.measuredHeight); val c = Canvas(full); c.drawColor(0xFF1C1C1E.toInt()); col.draw(c)
                save(full, "${lang}_0${i + 3}_${s}_full") }
            d.dismiss(); ShadowLooper.idleMainLooper()
        }
    }
    private fun findScroll(v: View): android.widget.ScrollView? {
        if (v is android.widget.ScrollView) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findScroll(v.getChildAt(i))?.let { return it }
        return null
    }

    @Test fun en() = run("en")
    @Test fun ar() = run("ar")
}
