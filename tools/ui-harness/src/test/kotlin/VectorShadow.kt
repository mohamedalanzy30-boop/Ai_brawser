import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.widget.ImageView
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import java.io.File

/** The harness has no compiled app resources: app vector drawables are drawn straight from the res drawable xml files. */
class XmlVector(file: File, private val px: Int) : Drawable() {
    private class P(val path: Path, val fill: Int?, val stroke: Int?, val sw: Float, val alpha: Float)
    private val vw: Float; private val ps = ArrayList<P>(); private val mirror: Boolean
    private var tint: android.content.res.ColorStateList? = null
    init {
        val x = file.readText()
        mirror = x.contains("autoMirrored=\"true\"")
        vw = Regex("viewportWidth=\"([\\d.]+)\"").find(x)!!.groupValues[1].toFloat()
        for (m in Regex("<path([^>]*)/>", RegexOption.DOT_MATCHES_ALL).findAll(x)) {
            val a = m.groupValues[1]
            fun g(k: String) = Regex("android:$k=\"([^\"]*)\"").find(a)?.groupValues?.get(1)
            fun col(s: String?) = s?.let { android.graphics.Color.parseColor(it) }?.takeIf { (it ushr 24) != 0 }
            val path = android.util.PathParser.createPathFromPathData(g("pathData")!!)
            ps.add(P(path, col(g("fillColor")), col(g("strokeColor")), g("strokeWidth")?.toFloat() ?: 0f, g("fillAlpha")?.toFloat() ?: 1f))
        }
    }
    override fun setTintList(t: android.content.res.ColorStateList?) { tint = t; invalidateSelf() }
    override fun getIntrinsicWidth() = px
    override fun getIntrinsicHeight() = px
    override fun draw(c: Canvas) {
        val b = bounds; c.save(); c.translate(b.left.toFloat(), b.top.toFloat())
        if (mirror && layoutDirection == android.util.LayoutDirection.RTL) { c.translate(b.width().toFloat(), 0f); c.scale(-1f, 1f) }
        c.scale(b.width() / vw, b.height() / vw)
        val t = tint?.getColorForState(state, tint!!.defaultColor)
        for (p in ps) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            if (p.fill != null) { paint.style = Paint.Style.FILL; paint.color = t ?: p.fill; paint.alpha = (paint.alpha * p.alpha).toInt(); c.drawPath(p.path, paint) }
            if (p.stroke != null) { paint.style = Paint.Style.STROKE; paint.color = t ?: p.stroke; paint.strokeWidth = p.sw; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND; c.drawPath(p.path, paint) }
        }
        c.restore()
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(f: ColorFilter?) {}
    @Deprecated("") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun isStateful() = true
    override fun isAutoMirrored() = mirror
    override fun onStateChange(s: IntArray) = true

    companion object {
        fun forId(ctx: android.content.Context, id: Int): Drawable? {
            for (cls in listOf("drawable")) {
                val f = Class.forName("com.aib.browser.R\$$cls").fields.firstOrNull { it.getInt(null) == id } ?: continue
                val file = File(System.getProperty("repo") + "/android/app/src/main/res/drawable/${f.name}.xml")
                val dp = Regex("android:width=\"(\\d+)dp\"").find(file.readText())!!.groupValues[1].toInt()
                return XmlVector(file, (dp * ctx.resources.displayMetrics.density).toInt())
            }
            return null
        }
    }
}

@Implements(ImageView::class)
class ShadowAppImageView : org.robolectric.shadows.ShadowView() {
    @RealObject lateinit var real: ImageView
    @Implementation protected fun setImageResource(id: Int) { real.setImageDrawable(XmlVector.forId(real.context, id)) }
}
