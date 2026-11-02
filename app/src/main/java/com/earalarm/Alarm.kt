package com.earalarm

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条闹钟。
 *
 * repeatDays 用位掩码表示周几：bit0=周一 … bit6=周日，0 表示只响一次。
 */
data class Alarm(
    val id: Long,
    var hour: Int,
    var minute: Int,
    var label: String = "",
    var enabled: Boolean = true,
    var repeatDays: Int = 0,
    var ringSeconds: Int = 180
) {

    val isRepeating: Boolean get() = repeatDays != 0

    fun timeText(): String = "%02d:%02d".format(hour, minute)

    /** 重复描述，"每天" / "周一、三、五" / "仅一次" */
    fun repeatText(): String {
        if (repeatDays == 0) return "仅一次"
        if (repeatDays == 0b1111111) return "每天"
        val names = arrayOf("一", "二", "三", "四", "五", "六", "日")
        val on = (0..6).filter { repeatDays and (1 shl it) != 0 }.joinToString("、") { names[it] }
        return "周$on"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("hour", hour)
        put("minute", minute)
        put("label", label)
        put("enabled", enabled)
        put("repeatDays", repeatDays)
        put("ringSeconds", ringSeconds)
    }

    companion object {
        fun fromJson(o: JSONObject): Alarm = Alarm(
            id = o.optLong("id"),
            hour = o.optInt("hour", 7),
            minute = o.optInt("minute", 0),
            label = o.optString("label", ""),
            enabled = o.optBoolean("enabled", true),
            repeatDays = o.optInt("repeatDays", 0),
            ringSeconds = o.optInt("ringSeconds", 180)
        )

        fun parseList(raw: String?): MutableList<Alarm> {
            if (raw.isNullOrBlank()) return mutableListOf()
            return try {
                val arr = JSONArray(raw)
                MutableList(arr.length()) { fromJson(arr.getJSONObject(it)) }
            } catch (t: Throwable) {
                mutableListOf()
            }
        }

        fun toRaw(list: List<Alarm>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }
    }
}
