package com.earalarm

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.earalarm.databinding.ActivitySetupBinding
import com.earalarm.databinding.ItemSetupBinding

/**
 * 设置检查页。
 *
 * 自动检测部分直接查系统接口；手动部分按机型给出路径，由用户自己确认。
 * 手动项的勾选状态存在本地，换手机或重置系统才需要重新走一遍。
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private val prefs by lazy { getSharedPreferences("ear_setup", MODE_PRIVATE) }

    private data class AutoItem(
        val title: String,
        val ok: Boolean,
        val detail: String,
        val action: () -> Unit
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.textDevice.text = DeviceProfile.deviceLabel()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val auto = autoItems()
        val manual = DeviceProfile.manualSteps()

        binding.autoContainer.removeAllViews()
        auto.forEach { item ->
            val b = ItemSetupBinding.inflate(layoutInflater, binding.autoContainer, false)
            b.textTitle.text = item.title
            b.textWhy.text = item.detail
            b.textPath.visibility = View.GONE
            b.textStatus.visibility = View.VISIBLE
            b.textStatus.text = if (item.ok) "已开启" else "未开启"
            b.textStatus.setTextColor(if (item.ok) 0xFF1B8F4B.toInt() else 0xFFD97706.toInt())
            b.btnAction.visibility = if (item.ok) View.GONE else View.VISIBLE
            b.btnAction.setOnClickListener { item.action() }
            b.cbDone.visibility = View.GONE
            binding.autoContainer.addView(b.root)
        }

        binding.manualContainer.removeAllViews()
        manual.forEach { step ->
            val b = ItemSetupBinding.inflate(layoutInflater, binding.manualContainer, false)
            b.textTitle.text = step.title
            b.textWhy.text = step.why
            b.textPath.text = step.path
            b.textPath.visibility = View.VISIBLE
            b.textStatus.visibility = View.GONE
            b.btnAction.visibility = View.GONE
            b.cbDone.visibility = View.VISIBLE
            b.cbDone.isChecked = prefs.getBoolean(step.key, false)
            b.cbDone.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(step.key, checked).apply()
                updateSummary()
            }
            binding.manualContainer.addView(b.root)
        }

        updateSummary()
    }

    private fun updateSummary() {
        val autoBad = autoItems().count { !it.ok }
        val manualBad = DeviceProfile.manualSteps().count { !prefs.getBoolean(it.key, false) }
        val total = autoBad + manualBad

        binding.textSummary.text = if (total == 0) {
            "全部就绪。插上耳机、锁屏，设个闹钟验证一次。"
        } else {
            "还有 $total 项待处理，其中 $autoBad 项可以点按钮直接跳转。"
        }
    }

    private fun autoItems(): List<AutoItem> {
        val list = mutableListOf<AutoItem>()

        val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
        list += AutoItem(
            "通知权限",
            notifOk,
            "响铃提示和「闹钟已跳过」的提醒都需要它。"
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            }
        }

        val exactOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        } else true
        list += AutoItem(
            "精确闹钟",
            exactOk,
            "没有它，系统可能延迟几分钟才响，或者干脆不响。"
        ) {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
                )
            } catch (_: Throwable) {
                safeStart(Intent(Settings.ACTION_SETTINGS))
            }
        }

        val batteryOk = getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(packageName)
        list += AutoItem(
            "忽略电池优化",
            batteryOk,
            "避免息屏后进程被冻结，闹钟就不会触发。"
        ) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Throwable) {
                safeStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        val overlayOk = Settings.canDrawOverlays(this)
        list += AutoItem(
            "悬浮窗权限",
            overlayOk,
            "锁屏时把「停止响铃」界面弹到最前面。缺它可能出现只响铃、不弹界面。"
        ) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (_: Throwable) {
            }
        }

        return list
    }

    private fun safeStart(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: Throwable) {
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    companion object {
        private const val REQ_NOTIFY = 200
    }
}
