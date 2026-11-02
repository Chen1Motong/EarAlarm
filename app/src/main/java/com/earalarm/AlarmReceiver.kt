package com.earalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 闹钟触发入口。
 *
 * 这里是「只在戴耳机时响」的决策点：
 *   - 检测到耳机 → 启动前台服务响铃
 *   - 没有耳机   → 静默跳过，只留一条记录
 *
 * 无论 App 当时在不在前台、屏幕是否熄灭，系统都会把这条广播送到。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(AlarmScheduler.EXTRA_ID, -1L)
        if (id < 0) return

        val store = AlarmStore(context)
        val alarm = store.find(id) ?: return

        // 先安排好下一次，避免响铃过程被打断导致循环闹钟丢链
        if (alarm.isRepeating) {
            AlarmScheduler.schedule(context, alarm)
        } else {
            store.setEnabled(id, false)
            AlarmScheduler.cancel(context, alarm)
        }

        if (HeadphoneDetector.hasHeadphone(context)) {
            RingService.start(context, alarm)
        } else {
            SkipLog.record(context, alarm)
        }
    }
}
