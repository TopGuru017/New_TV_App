package com.example.new_tv_app.reminder

import android.content.Context
import org.json.JSONArray

object ReminderStore {

    private const val PREFS = "reminders"
    private const val KEY_LIST = "list"

    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
    }

    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getAll(): List<Reminder> {
        val json = prefs.getString(KEY_LIST, "[]") ?: "[]"
        val arr = JSONArray(json)
        val out = mutableListOf<Reminder>()
        for (i in 0 until arr.length()) {
            runCatching {
                out.add(Reminder.fromJson(arr.getJSONObject(i)))
            }
        }
        return out
    }

    fun add(reminder: Reminder) {
        val list = getAll().filter { it.id != reminder.id }.toMutableList()
        list.add(reminder)
        save(list)
    }

    fun remove(id: String) {
        save(getAll().filter { it.id != id })
    }

    fun contains(id: String): Boolean = getAll().any { it.id == id }

    private fun save(list: List<Reminder>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }
}
