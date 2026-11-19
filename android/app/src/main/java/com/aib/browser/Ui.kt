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
    const val FIELD = 0xFF2C2C2E.toInt()
    const val SEPARATOR = 0xFF38383A.toInt()
    const val LABEL = 0xFFFFFFFF.toInt()
    // text colours are checked against WCAG AA (4.5:1 for normal text) on the darkest and lightest surface they sit on
    const val SECONDARY = 0xFF98989D.toInt()     // 4.85:1 on CARD, 5.93:1 on SHEET
    const val CHEVRON = 0xFF8E8E93.toInt()       // 4.27:1 on CARD (graphics need 3:1)
    const val LINK = 0xFF409CFF.toInt()          // blue for text on cards: 4.92:1 on CARD (BLUE is only 3.82:1 there)
    const val BLUE = 0xFF0A84FF.toInt()
    const val GREEN = 0xFF30D158.toInt()
    const val RED = 0xFFFF6961.toInt()           // 4.94:1 on CARD (systemRed 0xFFFF453A was 4.09:1)
    const val RIPPLE = 0x26FFFFFF
    const val AI = 0xFFBF5AF2.toInt()            // systemPurple, used for the AI features
    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun dp(a: Activity, v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), a.resources.displayMetrics).toInt()
    fun round(a: Activity, color: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(a, r).toFloat(); if (stroke != 0) setStroke(1, stroke)
    }

    /** Icon (button when [onClick] is set). [desc] is what TalkBack reads and what a long press shows. */
    fun icon(a: Activity, res: Int, tint: Int = LABEL, desc: String? = null, onClick: (() -> Unit)? = null) = ImageView(a).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(tint); scaleType = ImageView.ScaleType.CENTER
        if (desc != null) { contentDescription = desc; if (Build.VERSION.SDK_INT >= 26) tooltipText = desc }
        else importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        if (onClick != null) {
            setOnClickListener { onClick() }
            background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(RIPPLE), null, round(a, Color.WHITE, 24))
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
        val card = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = round(a, CARD, 12) }
        private var rows = 0
        init {
            // header/footer: start-aligned (right in Arabic), relative padding so both directions get the same inset
            if (header != null) parent.addView(text(a, header, 13f, SECONDARY).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_START; setPaddingRelative(dp(a, 16), dp(a, 24), dp(a, 16), dp(a, 8)) })
            // a group without a header still keeps 16dp from whatever is above it (no cards touching)
            parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { if (header == null && parent.childCount > 0) topMargin = dp(a, 16) })
            if (footer != null) parent.addView(text(a, footer, 13f, SECONDARY).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_START; setPaddingRelative(dp(a, 16), dp(a, 8), dp(a, 16), 0) })
        }
        /** Every row is at least 48dp tall (minimum touch target) and keeps the iOS 16dp leading inset. */
        private fun row(): LinearLayout {
            if (rows++ > 0) card.addView(View(a).apply { setBackgroundColor(SEPARATOR) }, LinearLayout.LayoutParams(-1, 1).apply { marginStart = dp(a, 16) })
            val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(a, 48); setPaddingRelative(dp(a, 16), dp(a, 6), dp(a, 12), dp(a, 6)) }
            card.addView(r, LinearLayout.LayoutParams(-1, -2)); return r
        }
        private fun pressable(r: LinearLayout, onClick: () -> Unit) {
            r.setOnClickListener { onClick() }
            r.background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(RIPPLE), null, null)
        }
        /** The whole row toggles (not only the small switch), and TalkBack reads it as one switch with its title. */
        fun toggle(title: String, on: Boolean, onChange: (Boolean) -> Unit) {
            val r = row()
            r.addView(text(a, title, 16f).apply { maxLines = 2; textAlignment = View.TEXT_ALIGNMENT_VIEW_START }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(a, 12) })
            val sw = switch(a, on, onChange)
            sw.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            r.addView(sw)
            pressable(r) { sw.toggle() }
            r.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Switch::class.java.name; info.isCheckable = true; info.isChecked = sw.isChecked; info.text = title
                }
            }
        }
        /** Row that opens something: title, optional value, chevron (mirrored in Arabic). Returns the row (for long press). */
        fun link(title: String, value: String = "", color: Int = LABEL, onClick: () -> Unit): View {
            val r = row()
            r.addView(text(a, title, 16f, color).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; textAlignment = View.TEXT_ALIGNMENT_VIEW_START }, LinearLayout.LayoutParams(0, -2, 1f))
            if (value.isNotEmpty()) r.addView(text(a, value, 15f, SECONDARY).apply { setSingleLine(); setPaddingRelative(dp(a, 12), 0, dp(a, 4), 0) })
            r.addView(icon(a, R.drawable.ic_chevron, CHEVRON), LinearLayout.LayoutParams(dp(a, 16), dp(a, 16)))
            pressable(r, onClick)
            return r
        }
        /** Row that does something right away (copy, clear...): centred coloured title, no chevron, as in iOS. */
        fun action(title: String, color: Int = LINK, onClick: () -> Unit): View {
            val r = row()
            r.gravity = Gravity.CENTER
            r.addView(text(a, title, 16f, color).apply { gravity = Gravity.CENTER })
            pressable(r, onClick)
            return r
        }
        fun segmented(labels: Array<String>, selected: Int, onPick: (Int) -> Unit) {
            val r = row()
            r.addView(segmentedControl(a, labels, selected, onPick), LinearLayout.LayoutParams(-1, dp(a, 36)))
        }
    }

    /** Empty-list message, centred in the sheet (instead of an empty card). */
    fun empty(a: Activity, parent: LinearLayout, message: String) {
        parent.addView(text(a, message, 15f, SECONDARY).apply { gravity = Gravity.CENTER; setPadding(dp(a, 24), dp(a, 32), dp(a, 24), dp(a, 32)) }, LinearLayout.LayoutParams(-1, -2))
    }

    fun segmentedControl(a: Activity, labels: Array<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; background = round(a, SHEET, 9); setPadding(dp(a, 2), dp(a, 2), dp(a, 2), dp(a, 2)) }
        val views = ArrayList<TextView>()
        fun paint(sel: Int) = views.forEachIndexed { i, v ->
            v.background = if (i == sel) round(a, 0xFF636366.toInt(), 7) else null; v.typeface = if (i == sel) MEDIUM else Typeface.DEFAULT; v.isSelected = i == sel
        }
        labels.forEachIndexed { i, l ->
            val v = text(a, l, 13f).apply {
                gravity = Gravity.CENTER; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END
                setOnClickListener { if (!isSelected) { paint(i); onPick(i) } } // tapping the chosen option again does nothing
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info); info.className = android.widget.RadioButton::class.java.name; info.isCheckable = true; info.isChecked = host.isSelected
                    }
                }
            }
            views.add(v); bar.addView(v, LinearLayout.LayoutParams(0, -1, 1f))
        }
        paint(selected)
        return bar
    }

    /** ScrollView that grows with its content up to [max] px, so short sheets are short and long ones scroll. */
    private class MaxScroll(a: Activity, private val max: Int) : ScrollView(a) {
        override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST))
    }

    /**
     * Bottom sheet with a grabber, a title and a "Done" button; content is scrollable.
     * [full] keeps it at full height (lists that change while open, e.g. search); otherwise it fits its content.
     * [build] may be called again through the returned [Sheet.refresh] to redraw the content in place (no flicker).
     */
    class Sheet(val dialog: Dialog, private val col: LinearLayout, private val build: (LinearLayout, Dialog) -> Unit) {
        fun show() = apply { dialog.show() }
        fun refresh() { col.removeAllViews(); build(col, dialog) }
    }
    fun sheet(a: Activity, title: String, full: Boolean = false, build: (LinearLayout, Dialog) -> Unit): Sheet {
        val dlg = Dialog(a)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val outer = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(a, 16), dp(a, 8), dp(a, 16), dp(a, 24)) }
        outer.addView(View(a).apply { background = round(a, 0xFF636366.toInt(), 3); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(dp(a, 36), dp(a, 5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(a, 8) })
        // title stays centred: same-width boxes on both sides ("Done" on the end side, as in iOS)
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(View(a), LinearLayout.LayoutParams(dp(a, 64), 1))
        head.addView(text(a, title, 17f, bold = true).apply { gravity = Gravity.CENTER; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END; isFocusable = true
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(text(a, "تم", 17f, BLUE, bold = true).apply {
            gravity = Gravity.CENTER; setOnClickListener { dlg.dismiss() }
            background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(RIPPLE), null, round(a, Color.WHITE, 12))
        }, LinearLayout.LayoutParams(dp(a, 64), dp(a, 48)))
        outer.addView(head)
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        build(col, dlg)
        val maxH = (a.resources.displayMetrics.heightPixels * 0.86).toInt()
        // space above the scrolling part: grabber 5 + 8, header 48, top padding 8, bottom 24 (dp)
        outer.addView(MaxScroll(a, maxH - dp(a, 93)).apply { addView(col); isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER },
            LinearLayout.LayoutParams(-1, if (full) 0 else -2, if (full) 1f else 0f))
        dlg.setContentView(outer)
        dlg.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply { setColor(SHEET); cornerRadii = FloatArray(8) { i -> if (i < 4) dp(a, 14).toFloat() else 0f } })
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, if (full) maxH else ViewGroup.LayoutParams.WRAP_CONTENT)
            setWindowAnimations(android.R.style.Animation_InputMethod)
            setDimAmount(0.45f)
            if (Build.VERSION.SDK_INT >= 31) { addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND); attributes = attributes.also { it.blurBehindRadius = dp(a, 18) } }
        }
        return Sheet(dlg, col, build)
    }
}
