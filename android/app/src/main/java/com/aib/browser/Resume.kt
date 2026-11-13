package com.aib.browser

import android.content.Context
import org.json.JSONObject

/** "Continue watching": last position of each movie, keyed by the page it was opened from. Stored on the phone only. */
object Resume {
    private fun prefs(c: Context) = c.getSharedPreferences("resume", Context.MODE_PRIVATE)
    private fun all(c: Context): JSONObject = try { JSONObject(prefs(c).getString("items", "{}")!!) } catch (e: Exception) { JSONObject() }

    fun save(c: Context, page: String, title: String, posMs: Long, durMs: Long) {
        if (page.isEmpty() || durMs < 5 * 60_000L) return // only real movies / episodes
        val o = all(c)
        if (posMs > durMs * 0.95 || posMs < 30_000L) o.remove(page) // finished, or barely started
        else o.put(page, JSONObject().put("p", posMs).put("d", durMs).put("t", title).put("ts", System.currentTimeMillis()))
        // keep the 40 most recent
        val keys = o.keys().asSequence().toList().sortedByDescending { o.getJSONObject(it).optLong("ts") }
        keys.drop(40).forEach { o.remove(it) }
        prefs(c).edit().putString("items", o.toString()).apply()
    }

    fun position(c: Context, page: String): Long = all(c).optJSONObject(page)?.optLong("p") ?: 0L

    data class Item(val page: String, val title: String, val posMs: Long, val durMs: Long, val ts: Long)
    fun list(c: Context): List<Item> {
        val o = all(c)
        return o.keys().asSequence().map { k -> val j = o.getJSONObject(k); Item(k, j.optString("t"), j.optLong("p"), j.optLong("d"), j.optLong("ts")) }
            .sortedByDescending { it.ts }.toList()
    }
    fun remove(c: Context, page: String) { val o = all(c); o.remove(page); prefs(c).edit().putString("items", o.toString()).apply() }
}
