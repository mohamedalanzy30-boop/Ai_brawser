package androidx.swiperefreshlayout.widget
import android.content.Context
import android.view.View
import android.widget.FrameLayout
/** Test stand-in: same API surface the app uses. */
open class SwipeRefreshLayout(c: Context) : FrameLayout(c) {
    fun interface OnRefreshListener { fun onRefresh() }
    fun interface OnChildScrollUpCallback { fun canChildScrollUp(parent: SwipeRefreshLayout, child: View?): Boolean }
    var isRefreshing = false
    fun setColorSchemeColors(vararg c: Int) {}
    fun setProgressBackgroundColorSchemeColor(c: Int) {}
    fun setOnRefreshListener(l: OnRefreshListener?) {}
    fun setOnChildScrollUpCallback(l: OnChildScrollUpCallback?) {}
}
