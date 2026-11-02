package com.earalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * 闹钟调度。
 *
 * 用 AlarmManager.setAlarmClock()：这是 Android 上优先级最高的闹钟 API，
 * Doze（打盹）状态下也会准时唤醒，系统还会在状态栏显示闹钟图标。
 * 这也是它能做到"锁屏、后台、非前台都照响"的根本原因——
 * 不依赖 App 进程活着，由系统到点拉起广播。
 */
object AlarmScheduler {

    const val EXTRA_ID = "alarm_id"

    fun schedule(context: Context, alarm: Alarm) {
        if (!alarm.enabled) {
            cancel(context, alarm)
            return
        }
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = nextTriggerTime(alarm)
        val fire = fireIntent(context, alarm)
        try {
            am.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAt, showIntent(context, alarm)),
                fire
            )
        } catch (t: Throwable) {
            // 极端情况下（没有精确闹钟权限）退化成普通闹钟，仍然尽力唤醒
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, fire)
            } catch (_: Throwable) {
            }
        }
    }

    fun cancel(context: Context, alarm: Alarm) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        try {
            am.cancel(fireIntent(context, alarm))
        } catch (_: Throwable) {
        }
    }

    fun scheduleAll(context: Context) {
        AlarmStore(context).all().forEach { alarm ->
            if (alarm.enabled) schedule(context, alarm) else cancel(context, alarm)
        }
    }

    /** 下一次响铃的时间戳 */
    fun nextTriggerTime(alarm: Alarm, from: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (alarm.repeatDays == 0) {
            if (cal.timeInMillis <= from) cal.add(Calendar.DAY_OF_YEAR, 1)
            return cal.timeInMillis
        }

        for (i in 0..7) {
            val isFuture = cal.timeInMillis > from
            val dayOn = alarm.repeatDays and (1 shl dayBit(cal.get(Calendar.DAY_OF_WEEK))) != 0
            if (isFuture && dayOn) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    /** Calendar 的 DAY_OF_WEEK（周日=1）转成我们的位序（周一=0） */
    private fun dayBit(calendarDayOfWeek: Int): Int = when (calendarDayOfWeek) {
        Calendar.MONDAY -> 0
        Calendar.TUESDAY -> 1
        Calendar.WEDNESDAY -> 2
        Calendar.THURSDAY -> 3
        Calendar.FRIDAY -> 4
        Calendar.SATURDAY -> 5
        else -> 6
    }

    private fun fireIntent(context: Context, alarm: Alarm): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.earalarm.FIRE.${alarm.id}"
            putExtra(EXTRA_ID, alarm.id)
        }
        return PendingIntent.getBroadcast(
            context,
            alarm.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun showIntent(context: Context, alarm: Alarm): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            (alarm.id.toInt() + 100000),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
