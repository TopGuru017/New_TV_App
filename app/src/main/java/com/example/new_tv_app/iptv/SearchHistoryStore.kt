package com.example.new_tv_app.iptv

import android.content.Context
import org.json.JSONArray

/** Persists recent search queries (order preserved, max [MAX_ITEMS]). */
object SearchHistoryStore {

    private const val PREFS = "iptv_search_history"
    private const val KEY_QUERIES = "queries_json"
    const val MAX_ITEMS = 24

    fun readQueries(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_QUERIES, null)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i).trim()
                    if (s.isNotEmpty()) add(s)
                }
            }
        }.getOrElse { emptyList() }
    }

    fun addQuery(context: Context, query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = readQueries(context).toMutableList()
        current.removeAll { it.equals(q, ignoreCase = true) }
        current.add(0, q)
        while (current.size > MAX_ITEMS) current.removeAt(current.lastIndex)
        val arr = JSONArray()
        for (s in current) arr.put(s)
        prefs.edit().putString(KEY_QUERIES, arr.toString()).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_QUERIES)
            .apply()
    }
}
