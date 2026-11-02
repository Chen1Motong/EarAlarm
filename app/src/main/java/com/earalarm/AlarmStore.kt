package com.earalarm

import android.content.Context

/** 闹钟的本地持久化，用 SharedPreferences 存一个 JSON 数组，够用且没有额外依赖 */
class AlarmStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private val lock = Any()

    fun all(): MutableList<Alarm> = synchronized(lock) {
        Alarm.parseList(prefs.getString(KEY_ALARMS, null))
    }

    fun find(id: Long): Alarm? = all().firstOrNull { it.id == id }

    fun save(list: List<Alarm>) {
        synchronized(lock) {
            prefs.edit().putString(KEY_ALARMS, Alarm.toRaw(list)).apply()
        }
    }

    fun upsert(alarm: Alarm) {
        val list = all()
        val idx = list.indexOfFirst { it.id == alarm.id }
        if (idx >= 0) list[idx] = alarm else list.add(alarm)
        save(list)
    }

    fun delete(id: Long) {
        val list = all()
        list.removeAll { it.id == id }
        save(list)
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        val list = all()
        list.firstOrNull { it.id == id }?.enabled = enabled
        save(list)
    }

    fun newId(): Long {
        val max = all().maxOfOrNull { it.id } ?: 0L
        return max + 1
    }

    companion object {
        private const val PREF_NAME = "ear_alarm_v1"
        private const val KEY_ALARMS = "alarms"
    }
}
