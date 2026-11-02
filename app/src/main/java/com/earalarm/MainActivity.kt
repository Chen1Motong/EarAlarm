package com.earalarm

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.earalarm.databinding.ActivityMainBinding
import com.earalarm.databinding.DialogEditAlarmBinding
import com.earalarm.databinding.ItemAlarmBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: AlarmStore
    private var deviceCallbackRegistered = false

    private val audioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshHeadphone()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshHeadphone()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = AlarmStore(this)

        binding.fabAdd.setOnClickListener { showEditDialog(null) }
        binding.btnPermission.setOnClickListener { requestNextPermission() }
        binding.btnSetup.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        binding.btnTestRing.setOnClickListener { startTestRing() }

        // 开机后补排一次，防止系统清掉闹钟（幂等操作）
        AlarmScheduler.scheduleAll(this)
    }

    override fun onStart() {
        super.onStart()
        if (!deviceCallbackRegistered) {
            try {
                val am = getSystemService(AudioManager::class.java)
                am.registerAudioDeviceCallback(
                    audioCallback,
                    android.os.Handler(android.os.Looper.getMainLooper())
                )
                deviceCallbackRegistered = true
            } catch (_: Throwable) {
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (deviceCallbackRegistered) {
            try {
                getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(audioCallback)
            } catch (_: Throwable) {
            }
            deviceCallbackRegistered = false
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    private fun refreshAll() {
        refreshHeadphone()
        renderAlarms()
        refreshPermissionCard()
        refreshSkipLog()
    }

    // ------------------------------------------------------------ 耳机状态

    private fun refreshHeadphone() {
        val device = HeadphoneDetector.preferredHeadphone(this)
        val connected = device != null
        binding.textHeadphone.text = if (connected) {
            HeadphoneDetector.describe(device)
        } else {
            getString(R.string.headphone_disconnected)
        }
        val color = if (connected) 0xFF34C759.toInt() else 0xFF9AA3B2.toInt()
        binding.dotHeadphone.backgroundTintList = ColorStateList.valueOf(color)
    }

    // -------------------------------------------------------------- 列表

    private fun renderAlarms() {
        binding.alarmContainer.removeAllViews()
        val alarms = store.all().sortedBy { it.hour * 60 + it.minute }
        binding.textEmpty.visibility = if (alarms.isEmpty()) View.VISIBLE else View.GONE

        alarms.forEach { alarm ->
            val item = ItemAlarmBinding.inflate(layoutInflater, binding.alarmContainer, false)
            item.textTime.text = alarm.timeText()
            item.textInfo.text = buildString {
                append(alarm.repeatText())
                if (alarm.label.isNotBlank()) append("  ·  ").append(alarm.label)
            }
            item.switchEnabled.isChecked = alarm.enabled
            item.switchEnabled.setOnCheckedChangeListener { _, checked ->
                alarm.enabled = checked
                store.upsert(alarm)
                if (checked) AlarmScheduler.schedule(this, alarm)
                else AlarmScheduler.cancel(this, alarm)
                updateNextText()
            }
            item.root.setOnClickListener { showEditDialog(alarm) }
            item.root.setOnLongClickListener {
                confirmDelete(alarm)
                true
            }
            binding.alarmContainer.addView(item.root)
        }

        updateNextText()
    }

    private fun updateNextText() {
        val enabled = store.all().filter { it.enabled }
        if (enabled.isEmpty()) {
            binding.textNext.visibility = View.GONE
            return
        }
        val now = System.currentTimeMillis()
        val next = enabled.minByOrNull { AlarmScheduler.nextTriggerTime(it, now) } ?: return
        val at = AlarmScheduler.nextTriggerTime(next, now)
        val fmt = SimpleDateFormat("M月d日 EEEE HH:mm", Locale.CHINA)
        binding.textNext.text = "下次响铃：${fmt.format(Date(at))}"
        binding.textNext.visibility = View.VISIBLE
    }

    private fun refreshSkipLog() {
        val records = SkipLog.history(this)
        if (records.isEmpty()) {
            binding.textSkipLog.visibility = View.GONE
            return
        }
        val fmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
        val lines = records.take(3).joinToString("\n") {
            "· ${fmt.format(Date(it.timeMillis))}  ${it.alarmText}  未连接耳机，已跳过"
        }
        binding.textSkipLog.text = "最近跳过\n$lines"
        binding.textSkipLog.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------ 权限

    private fun missingPermissions(): List<String> {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add("通知")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(AlarmManager::class.java)
            if (!am.canScheduleExactAlarms()) missing.add("精确闹钟")
        }
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            missing.add("后台电池限制")
        }
        return missing
    }

    private fun refreshPermissionCard() {
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            binding.cardPermission.visibility = View.GONE
            return
        }
        binding.cardPermission.visibility = View.VISIBLE
        binding.textPermissionDetail.text = missing.joinToString("、") +
            "\n没有这几项，闹钟可能在锁屏或后台被系统掐掉。"
    }

    private fun requestNextPermission() {
        val missing = missingPermissions()
        if (missing.contains("通知") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            return
        }
        if (missing.contains("精确闹钟")) {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
                )
            } catch (_: Throwable) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
            return
        }
        if (missing.contains("后台电池限制")) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Throwable) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Throwable) {
                }
            }
            return
        }
        Toast.makeText(this, "权限都齐了", Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissionCard()
    }

    // ------------------------------------------------------------ 编辑

    private fun showEditDialog(existing: Alarm?) {
        val b = DialogEditAlarmBinding.inflate(layoutInflater)
        b.pickerHour.minValue = 0
        b.pickerHour.maxValue = 23
        b.pickerMinute.minValue = 0
        b.pickerMinute.maxValue = 59
        b.pickerHour.wrapSelectorWheel = true
        b.pickerMinute.wrapSelectorWheel = true
        b.pickerHour.value = existing?.hour ?: 7
        b.pickerMinute.value = existing?.minute ?: 0
        b.editLabel.setText(existing?.label.orEmpty())

        val days = listOf(b.cbMon, b.cbTue, b.cbWed, b.cbThu, b.cbFri, b.cbSat, b.cbSun)
        days.forEachIndexed { i, cb ->
            cb.isChecked = existing != null && (existing.repeatDays and (1 shl i)) != 0
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.add_alarm else R.string.edit_alarm)
            .setView(b.root)
            .setPositiveButton("保存") { _, _ ->
                var mask = 0
                days.forEachIndexed { i, cb -> if (cb.isChecked) mask = mask or (1 shl i) }
                val label = b.editLabel.text?.toString()?.trim().orEmpty()

                val alarm = if (existing == null) {
                    Alarm(
                        id = store.newId(),
                        hour = b.pickerHour.value,
                        minute = b.pickerMinute.value,
                        label = label,
                        enabled = true,
                        repeatDays = mask
                    )
                } else {
                    existing.copy(
                        hour = b.pickerHour.value,
                        minute = b.pickerMinute.value,
                        label = label,
                        repeatDays = mask,
                        enabled = true
                    )
                }

                store.upsert(alarm)
                AlarmScheduler.schedule(this, alarm)
                refreshAll()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 测试响铃

    private fun startTestRing() {
        if (!HeadphoneDetector.hasHeadphone(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("没有检测到耳机")
                .setMessage("测试响铃同样遵守「没耳机就不响」的规则。\n现在直接测试会被静默跳过，只在下面留一条记录。")
                .setPositiveButton("仍然测试") { _, _ -> fireTest() }
                .setNegativeButton("先去连耳机", null)
                .show()
            return
        }
        fireTest()
    }

    private fun fireTest() {
        val cal = Calendar.getInstance()
        RingService.test(this, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        Toast.makeText(this, "响铃界面应立刻出现，30 秒后自动停", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete(alarm: Alarm) {
        MaterialAlertDialogBuilder(this)
            .setMessage("删除 ${alarm.timeText()} 的闹钟？")
            .setPositiveButton("删除") { _, _ ->
                AlarmScheduler.cancel(this, alarm)
                store.delete(alarm.id)
                refreshAll()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    companion object {
        private const val REQ_NOTIFY = 100
    }
}
