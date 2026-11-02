package com.earalarm

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.earalarm.databinding.ActivityRingBinding

/**
 * 响铃全屏界面。
 * 锁屏时也能直接显示（showWhenLocked + 全屏通知），给一个足够大的停止按钮。
 */
class RingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRingBinding
    private val handler = Handler(Looper.getMainLooper())

    /** 服务停止（超时或被拔耳机）后自动收尾 */
    private val watcher = object : Runnable {
        override fun run() {
            if (RingState.currentAlarm == null) {
                val reason = RingState.lastStopReason
                if (!reason.isNullOrBlank()) {
                    binding.textRoute.text = reason
                    binding.textTip.text = "闹钟已停止"
                }
                handler.postDelayed({ finishAndRemoveTask() }, 1400)
                return
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupLockScreenDisplay()

        binding = ActivityRingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getLongExtra(AlarmScheduler.EXTRA_ID, -1L)
        val stored = AlarmStore(this).find(id)
        // 测试响铃不写进列表，时间直接从 intent 里取
        val hour = stored?.hour ?: intent.getIntExtra(RingService.EXTRA_HOUR, -1)
        val minute = stored?.minute ?: intent.getIntExtra(RingService.EXTRA_MINUTE, 0)
        val label = stored?.label?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra(RingService.EXTRA_LABEL).orEmpty()

        binding.textNow.text = if (hour >= 0) "%02d:%02d".format(hour, minute) else "--:--"
        binding.textLabel.text = label.ifBlank { "耳机闹钟" }

        updateRoute()

        binding.btnStop.setOnClickListener {
            RingService.stop(this)
            finishAndRemoveTask()
        }
    }

    override fun onResume() {
        super.onResume()
        updateRoute()
        handler.removeCallbacks(watcher)
        handler.postDelayed(watcher, 600)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(watcher)
    }

    private fun updateRoute() {
        val device = HeadphoneDetector.preferredHeadphone(this)
        binding.textRoute.text = if (device != null) {
            "声音输出：" + HeadphoneDetector.describe(device)
        } else {
            "耳机已断开"
        }
    }

    private fun setupLockScreenDisplay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        // 界面被销毁（用户退出）时确保声音停下；配置变更导致的重建除外
        if (!isChangingConfigurations) {
            RingService.stop(this)
        }
        super.onDestroy()
    }
}
