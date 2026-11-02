package com.earalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/** 响铃期间的共享状态，给界面读取 */
object RingState {
    @Volatile
    var currentAlarm: Alarm? = null

    @Volatile
    var lastStopReason: String? = null
}

/**
 * 响铃前台服务。
 *
 * 三件事：
 *   1. 把通知做成全屏闹钟通知，锁屏上直接弹出停止按钮
 *   2. 播放系统闹钟铃声，并把音频显式路由到耳机设备
 *   3. 全程监听音频设备变化——耳机一断开立刻静音，绝不让声音漏到扬声器
 */
class RingService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var callbackRegistered = false

    private val autoStop = Runnable { stopRinging(null) }

    private val toneLoop = object : Runnable {
        override fun run() {
            try {
                tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 700)
            } catch (_: Throwable) {
            }
            handler.postDelayed(this, 1200)
        }
    }

    /** 监听音频设备增减：耳机消失立即静音 */
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            handler.post {
                if (HeadphoneDetector.hasHeadphone(this@RingService)) {
                    // 还连着另一副耳机，重新把声音指过去
                    HeadphoneDetector.preferredHeadphone(this@RingService)?.let { dev ->
                        try {
                            player?.setPreferredDevice(dev)
                        } catch (_: Throwable) {
                        }
                    }
                } else {
                    stopRinging("耳机已断开，闹钟已静音")
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val i = intent ?: return START_NOT_STICKY
        when (i.action) {
            ACTION_STOP -> {
                stopRinging(null)
                return START_NOT_STICKY
            }

            ACTION_START, ACTION_TEST -> {
                val alarm = if (i.action == ACTION_TEST) {
                    // 测试响铃：不落盘，直接按传进来的时间响
                    Alarm(
                        id = 0L,
                        hour = i.getIntExtra(EXTRA_HOUR, 0),
                        minute = i.getIntExtra(EXTRA_MINUTE, 0),
                        label = i.getStringExtra(EXTRA_LABEL) ?: "测试响铃",
                        enabled = true,
                        ringSeconds = 30
                    )
                } else {
                    AlarmStore(this).find(i.getLongExtra(AlarmScheduler.EXTRA_ID, -1L))
                }

                if (alarm == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startAsForeground(alarm)
                launchRingUi(alarm)
                beginRinging(alarm)
            }
        }
        return START_NOT_STICKY
    }

    /**
     * 主动把响铃界面拉到前台。
     *
     * 全屏通知（fullScreenIntent）在 MagicOS / MIUI 这类系统上经常被拦，
     * 只靠它会出现"响是响了，但找不到关闭按钮"的情况。
     * 这里再直接 startActivity 一次；持有悬浮窗权限时，后台启动 Activity 不受限制。
     */
    private fun launchRingUi(alarm: Alarm) {
        try {
            startActivity(
                Intent(this, RingActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    putExtra(AlarmScheduler.EXTRA_ID, alarm.id)
                    putExtra(EXTRA_HOUR, alarm.hour)
                    putExtra(EXTRA_MINUTE, alarm.minute)
                    putExtra(EXTRA_LABEL, alarm.label)
                }
            )
        } catch (_: Throwable) {
            // 被系统拦下时退回通知，用户仍可从通知的「停止响铃」按钮结束
        }
    }

    // ---------------------------------------------------------------- 前台通知

    private fun startAsForeground(alarm: Alarm) {
        ensureChannels()
        val n = buildRingNotification(alarm)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildRingNotification(alarm: Alarm): Notification {
        val openRing = PendingIntent.getActivity(
            this,
            1,
            Intent(this, RingActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(AlarmScheduler.EXTRA_ID, alarm.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopPI = PendingIntent.getService(
            this,
            2,
            Intent(this, RingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = alarm.label.ifBlank { "耳机闹钟" }
        return NotificationCompat.Builder(this, CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("${alarm.timeText()}  $text")
            .setContentText("正在通过耳机响铃 · 点开或下拉可停止")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setFullScreenIntent(openRing, true)
            .setContentIntent(openRing)
            .addAction(0, getString(R.string.stop), stopPI)
            .build()
    }

    private fun ensureChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_RING) == null) {
            val ring = NotificationChannel(
                CHANNEL_RING,
                "正在响铃",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "闹钟响铃时的常驻通知"
                setSound(null, null)   // 声音由 MediaPlayer 播放，通知本身静音，避免双响
                enableVibration(false)
                enableLights(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(ring)
        }
        SkipLog.ensureChannel(this)
    }

    // ------------------------------------------------------------------ 播放

    private fun beginRinging(alarm: Alarm) {
        val device = HeadphoneDetector.preferredHeadphone(this)
        if (device == null) {
            // 广播发出到服务启动之间的极短窗口里耳机被拔掉了
            stopRinging("耳机已断开，闹钟已静音")
            return
        }

        RingState.currentAlarm = alarm
        RingState.lastStopReason = null

        if (!callbackRegistered) {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.registerAudioDeviceCallback(deviceCallback, handler)
                callbackRegistered = true
            } catch (_: Throwable) {
            }
        }

        startPlayback(device)

        // 到设定时长自动停，避免无人理会时一直响
        handler.postDelayed(autoStop, alarm.ringSeconds * 1000L)
    }

    private fun startPlayback(device: AudioDeviceInfo) {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            if (uri == null) {
                startFallbackTone()
                return
            }

            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(this, uri)
            mp.isLooping = true
            mp.prepare()
            // 关键：把声音显式钉到耳机设备上
            try {
                mp.setPreferredDevice(device)
            } catch (_: Throwable) {
            }
            mp.start()
            player = mp
        } catch (t: Throwable) {
            startFallbackTone()
        }
    }

    private fun startFallbackTone() {
        try {
            tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            handler.post(toneLoop)
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ 停止

    private fun stopRinging(reason: String?) {
        handler.removeCallbacksAndMessages(null)

        if (callbackRegistered) {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.unregisterAudioDeviceCallback(deviceCallback)
            } catch (_: Throwable) {
            }
            callbackRegistered = false
        }

        try {
            player?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Throwable) {
        }
        player = null

        try {
            tone?.release()
        } catch (_: Throwable) {
        }
        tone = null

        RingState.lastStopReason = reason
        RingState.currentAlarm = null

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (callbackRegistered) {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.unregisterAudioDeviceCallback(deviceCallback)
            } catch (_: Throwable) {
            }
            callbackRegistered = false
        }
        try {
            player?.release()
        } catch (_: Throwable) {
        }
        player = null
        try {
            tone?.release()
        } catch (_: Throwable) {
        }
        tone = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.earalarm.action.RING_START"
        const val ACTION_STOP = "com.earalarm.action.RING_STOP"
        const val ACTION_TEST = "com.earalarm.action.RING_TEST"
        const val EXTRA_HOUR = "extra_hour"
        const val EXTRA_MINUTE = "extra_minute"
        const val EXTRA_LABEL = "extra_label"
        private const val CHANNEL_RING = "ringing"
        private const val NOTIF_ID = 88

        /** 立即开始一次测试响铃（不写进闹钟列表） */
        fun test(context: Context, hour: Int, minute: Int) {
            val i = Intent(context, RingService::class.java).apply {
                action = ACTION_TEST
                putExtra(EXTRA_HOUR, hour)
                putExtra(EXTRA_MINUTE, minute)
                putExtra(EXTRA_LABEL, "测试响铃")
            }
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        fun start(context: Context, alarm: Alarm) {
            val i = Intent(context, RingService::class.java).apply {
                action = ACTION_START
                putExtra(AlarmScheduler.EXTRA_ID, alarm.id)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            val i = Intent(context, RingService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(i)
            } catch (_: Throwable) {
            }
        }
    }
}
