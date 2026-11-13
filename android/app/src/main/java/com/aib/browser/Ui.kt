package com.aib.browser

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Small iOS-style UI kit (dark appearance): grouped lists, segmented controls, glass bars, sheets. */
object Ui {
    const val BG = 0xFF000000.toInt()            // systemBackground (dark)
    const val SHEET = 0xFF1C1C1E.toInt()         // secondarySystemBackground
    const val CARD = 0xFF2C2C2E.toInt()          // tertiarySystemBackground
    const val BAR = 0xF21C1C1E.toInt()           // translucent material
    const val FIELD = 0xFF2C2C2E.toInt()
    const val SEPARATOR = 0xFF38383A.toInt()
    const val LABEL = 0xFFFFFFFF.toInt()
    const val SECONDARY = 0xFF8E8E93.toInt()
    const val BLUE = 0xFF0A84FF.toInt()
    const val GREEN = 0xFF30D158.toInt()
    const val RED = 0xFFFF453A.toInt()
    const val AI = 0xFFBF5AF2.toInt()            // systemPurple, used for the AI features
    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun dp(a: Activity, v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), a.resources.displayMetrics).toInt()
    fun round(a: Activity, color: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(a, r).toFloat(); if (stroke != 0) setStroke(1, stroke)
    }

    fun icon(a: Activity, res: Int, tint: Int = LABEL, onClick: (() -> Unit)? = null) = ImageView(a).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(tint); scaleType = ImageView.ScaleType.CENTER
        if (onClick != null) {
            setOnClickListener { onClick() }
            background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, round(a, Color.WHITE, 22))
        }
    }

    fun text(a: Activity, t: String, size: Float = 17f, color: Int = LABEL, bold: Boolean = false) = TextView(a).apply {
        text = t; textSize = size; setTextColor(color); if (bold) typeface = MEDIUM
    }

    /** iOS switch look: white thumb, green track when on. */
    fun switch(a: Activity, on: Boolean, onChange: (Boolean) -> Unit) = Switch(a).apply {
        isChecked = on
        thumbTintList = ColorStateList.valueOf(Color.WHITE)
        trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(GREEN, 0xFF39393D.toInt()))
        trackTintMode = android.graphics.PorterDuff.Mode.SRC
        setOnCheckedChangeListener { _, c -> onChange(c) }
    }

    /** Grouped inset list ("Settings" style). */
    class Group(private val a: Activity, parent: LinearLayout, header: String?, footer: String? = null) {
        private val card = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = round(a, CARD, 12) }
        private var rows = 0
        init {
            if (header != null) parent.addView(text(a, header, 13f, SECONDARY).apply { setPadding(dp(a, 16), dp(a, 22), dp(a, 16), dp(a, 7)) })
            parent.addView(card, LinearLayout.LayoutParams(-1, -2))
            if (footer != null) parent.addView(text(a, footer, 13f, SECONDARY).apply { setPadding(dp(a, 16), dp(a, 7), dp(a, 16), 0) })
        }
        private fun row(): LinearLayout {
            if (rows++ > 0) card.addView(View(a).apply { setBackgroundColor(SEPARATOR) }, LinearLayout.LayoutParams(-1, 1).apply { marginStart = dp(a, 16) })
            val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(a, 48); setPadding(dp(a, 16), dp(a, 6), dp(a, 12), dp(a, 6)) }
            card.addView(r, LinearLayout.LayoutParams(-1, -2)); return r
        }
        fun toggle(title: String, on: Boolean, onChange: (Boolean) -> Unit) {
            val r = row()
            r.addView(text(a, title, 16f).apply { maxLines = 2 }, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(switch(a, on, onChange))
        }
        fun link(title: String, value: String = "", color: Int = LABEL, onClick: () -> Unit) {
            val r = row()
            r.addView(text(a, title, 16f, color), LinearLayout.LayoutParams(0, -2, 1f))
            if (value.isNotEmpty()) r.addView(text(a, value, 16f, SECONDARY).apply { setPadding(0, 0, dp(a, 6), 0) })
            r.addView(icon(a, R.drawable.ic_chevron, 0xFF5A5A5F.toInt()), LinearLayout.LayoutParams(dp(a, 18), dp(a, 18)))
            r.setOnClickListener { onClick() }
            r.background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, null)
        }
        fun segmented(labels: Array<String>, selected: Int, onPick: (Int) -> Unit) {
            val r = row()
            r.addView(segmentedControl(a, labels, selected, onPick), LinearLayout.LayoutParams(-1, dp(a, 34)))
        }
    }

    fun segmentedControl(a: Activity, labels: Array<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; background = round(a, 0xFF1C1C1E.toInt(), 9); setPadding(dp(a, 2), dp(a, 2), dp(a, 2), dp(a, 2)) }
        val views = ArrayList<TextView>()
        fun paint(sel: Int) = views.forEachIndexed { i, v -> v.background = if (i == sel) round(a, 0xFF636366.toInt(), 7) else null; v.typeface = if (i == sel) MEDIUM else Typeface.DEFAULT }
        labels.forEachIndexed { i, l ->
            val v = text(a, l, 13f).apply { gravity = Gravity.CENTER; setOnClickListener { paint(i); onPick(i) } }
            views.add(v); bar.addView(v, LinearLayout.LayoutParams(0, -1, 1f))
        }
        paint(selected)
        return bar
    }

    /** Bottom sheet with a grabber, a title and a "Done" button; content is scrollable. */
    fun sheet(a: Activity, title: String, build: (LinearLayout, Dialog) -> Unit): Dialog {
        val dlg = Dialog(a)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val outer = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(a, 16), dp(a, 8), dp(a, 16), dp(a, 24)) }
        outer.addView(View(a).apply { background = round(a, 0xFF5A5A5F.toInt(), 3) }, LinearLayout.LayoutParams(dp(a, 36), dp(a, 5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(a, 10) })
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(View(a), LinearLayout.LayoutParams(dp(a, 56), 1))
        head.addView(text(a, title, 17f, bold = true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(text(a, "تم", 17f, BLUE, bold = true).apply { gravity = Gravity.CENTER; setOnClickListener { dlg.dismiss() } }, LinearLayout.LayoutParams(dp(a, 56), dp(a, 40)))
        outer.addView(head)
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        build(col, dlg)
        outer.addView(ScrollView(a).apply { addView(col); isVerticalScrollBarEnabled = false }, LinearLayout.LayoutParams(-1, -2))
        dlg.setContentView(outer)
        dlg.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply { setColor(SHEET); cornerRadii = FloatArray(8) { i -> if (i < 4) dp(a, 14).toFloat() else 0f } })
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (a.resources.displayMetrics.heightPixels * 0.86).toInt())
            setWindowAnimations(android.R.style.Animation_InputMethod)
            setDimAmount(0.45f)
            if (Build.VERSION.SDK_INT >= 31) { addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND); attributes = attributes.also { it.blurBehindRadius = dp(a, 18) } }
        }
        return dlg
    }
}
