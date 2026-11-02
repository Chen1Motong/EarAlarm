package com.earalarm

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * 耳机检测。
 *
 * Android 没有"戴在耳朵上"的通用 API，能拿到的最准信号是「当前是否存在耳机类输出设备」。
 * 有线耳机拔掉、蓝牙耳机断连时，对应的 AudioDeviceInfo 会立刻从列表里消失，
 * 所以这个判断在插拔与断连场景下是可靠的。
 */
object HeadphoneDetector {

    /** 所有算作"耳机"的输出设备类型 */
    private val HEADSET_TYPES = intArrayOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,      // 3  带麦克风的有线耳麦
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,   // 4  有线耳机
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,     // 8  蓝牙音频（A2DP，蓝牙耳机的主要形态）
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,      // 7  蓝牙通话通道
        AudioDeviceInfo.TYPE_USB_HEADSET,        // 22 USB / Type-C 耳机
        AudioDeviceInfo.TYPE_HEARING_AID,        // 23 助听设备（API 28+）
        AudioDeviceInfo.TYPE_BLE_HEADSET         // 26 BLE 耳机（API 31+）
    )

    private fun audioManager(context: Context): AudioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 当前所有耳机类输出设备 */
    fun headphoneDevices(context: Context): List<AudioDeviceInfo> {
        return try {
            audioManager(context)
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.type in HEADSET_TYPES }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** 是否有耳机连着 */
    fun hasHeadphone(context: Context): Boolean = headphoneDevices(context).isNotEmpty()

    /** 优先用于播放的耳机设备，返回 null 表示没有耳机 */
    fun preferredHeadphone(context: Context): AudioDeviceInfo? {
        val devices = headphoneDevices(context)
        if (devices.isEmpty()) return null
        // 有线优先，其次蓝牙，避免同时连着两个设备时路由到奇怪的地方
        val order = listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_HEARING_AID
        )
        for (type in order) {
            devices.firstOrNull { it.type == type }?.let { return it }
        }
        return devices.first()
    }

    /** 给界面用的可读描述 */
    fun describe(device: AudioDeviceInfo?): String {
        if (device == null) return "未检测到耳机"
        val kind = when (device.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙耳机"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳机"
            AudioDeviceInfo.TYPE_HEARING_AID -> "助听设备"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "蓝牙耳机"
            else -> "耳机"
        }
        val name = device.productName?.toString()?.trim().orEmpty()
        return if (name.isEmpty() || name.equals("null", true)) kind else "$kind · $name"
    }
}
