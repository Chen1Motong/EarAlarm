package com.earalarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * 跳过记录。
 *
 * 到点时没有耳机 → 按设定静默跳过，不发声、不亮屏、不振动。
 * 但会留一条低优先级通知 + 一条本地记录，否则用户根本不知道闹钟是被跳过还是 App 坏了。
 */
object SkipLog {

    private const val CHANNEL_ID = "skip_log"
    private const val PREF = "ear_alarm_skip"
    private const val KEY = "records"
    private const val MAX_RECORDS = 20

    data class Record(val timeMillis: Long, val alarmText: String)

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "闹钟跳过记录",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "到点时没有连接耳机，闹钟被静默跳过"
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    fun record(context: Context, alarm: Alarm) {
        val records = history(context).toMutableList()
        records.add(0, Record(System.currentTimeMillis(), alarm.timeText() + " " + alarm.label))
        save(context, records.take(MAX_RECORDS))
        notifySkipped(context, alarm)
    }

    fun history(context: Context): List<Record> {
        val raw = context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 2) null
            else parts[0].toLongOrNull()?.let { Record(it, parts[1]) }
        }
    }

    private fun save(context: Context, records: List<Record>) {
        val raw = records.joinToString("\n") { "${it.timeMillis}|${it.alarmText}" }
        context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, raw).apply()
    }

    private fun notifySkipped(context: Context, alarm: Alarm) {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        val open = PendingIntent.getActivity(
            context,
            9000,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = "${alarm.timeText()} 的闹钟没有响：到点时未连接耳机"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("闹钟已跳过")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(true)
            .build()

        try {
            nm.notify(9100 + alarm.id.toInt(), notification)
        } catch (_: Throwable) {
        }
    }
}
