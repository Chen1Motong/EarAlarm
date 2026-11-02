package com.earalarm

import android.os.Build

/**
 * 机型识别与厂商设置路径。
 *
 * 能自动查的：通知、精确闹钟、电池优化、悬浮窗。
 * 查不到的：自启动 / 后台活动 / 开机启动 —— 系统没有给第三方 App 任何查询接口，
 * 只能按机型给出准确路径，让用户手动确认一次。
 */
object DeviceProfile {

    data class ManualStep(
        val key: String,
        val title: String,
        val why: String,
        val path: String
    )

    private fun mfr(): String = (Build.MANUFACTURER ?: "").lowercase()
    private fun brand(): String = (Build.BRAND ?: "").lowercase()

    fun isHonor(): Boolean = mfr().contains("honor") || brand().contains("honor")
    fun isHuawei(): Boolean = mfr().contains("huawei") || brand().contains("huawei")
    fun isXiaomi(): Boolean =
        mfr().contains("xiaomi") || brand().contains("redmi") || mfr().contains("poco") ||
            brand().contains("xiaomi")

    fun isOppo(): Boolean =
        mfr().contains("oppo") || mfr().contains("realme") || mfr().contains("oneplus") ||
            brand().contains("realme") || brand().contains("oneplus")

    fun isVivo(): Boolean = mfr().contains("vivo") || brand().contains("iqoo") || brand().contains("vivo")
    fun isSamsung(): Boolean = mfr().contains("samsung")

    fun romName(): String = when {
        isHonor() -> "MagicOS"
        isHuawei() -> "HarmonyOS"
        isXiaomi() -> "HyperOS / MIUI"
        isOppo() -> "ColorOS"
        isVivo() -> "OriginOS"
        isSamsung() -> "One UI"
        else -> "Android ${Build.VERSION.RELEASE}"
    }

    fun brandName(): String = when {
        isHonor() -> "荣耀"
        isHuawei() -> "华为"
        isXiaomi() -> "小米"
        isOppo() -> "OPPO"
        isVivo() -> "vivo"
        isSamsung() -> "三星"
        else -> Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
    }

    fun deviceLabel(): String = "${brandName()} ${Build.MODEL} · ${romName()}"

    /** 这台机器需要手动开的项 */
    fun manualSteps(): List<ManualStep> = when {
        isHonor() || isHuawei() -> listOf(
            ManualStep(
                "autostart",
                "允许自启动与后台活动",
                "最关键的一项。不开的话到点会被系统直接掐掉。",
                "设置 → 搜索「应用启动管理」→ 关闭「自动管理」→ 在弹框里打开「允许自启动」「允许关联启动」「允许后台活动」"
            ),
            ManualStep(
                "boot",
                "允许开机启动",
                "手机重启后，闹钟要靠它重新注册，否则重启后全部消失。",
                "设置 → 应用 → 应用管理 → 耳机闹钟 → 权限 → 查看所有权限 → 打开「开机启动」"
            ),
            ManualStep(
                "flip",
                "关闭翻转静音",
                "手机一翻面闹钟就哑，很难排查，建议关掉。",
                "设置 → 搜索「翻转」→ 关闭「计时器和闹钟静音」"
            )
        )

        isXiaomi() -> listOf(
            ManualStep(
                "autostart",
                "允许自启动",
                "MIUI/HyperOS 的自启动管理会拦第三方闹钟。",
                "设置 → 应用设置 → 授权管理 → 自启动管理 → 打开「耳机闹钟」"
            ),
            ManualStep(
                "battery",
                "省电策略设为「无限制」",
                "默认的智能省电会在后台冻结应用。",
                "设置 → 应用设置 → 应用管理 → 耳机闹钟 → 省电策略 → 无限制"
            ),
            ManualStep(
                "lockscreen",
                "允许锁屏显示",
                "锁屏时才能弹出响铃界面。",
                "设置 → 应用设置 → 应用管理 → 耳机闹钟 → 其他权限 → 锁屏显示"
            )
        )

        isOppo() -> listOf(
            ManualStep(
                "autostart",
                "允许自启动",
                "ColorOS 会限制第三方应用后台唤醒。",
                "设置 → 应用 → 自启动管理 → 打开「耳机闹钟」"
            ),
            ManualStep(
                "battery",
                "允许完全后台行为",
                "不开的话息屏几分钟后就会被清理。",
                "设置 → 电池 → 应用耗电管理 → 耳机闹钟 → 允许完全后台行为"
            )
        )

        isVivo() -> listOf(
            ManualStep(
                "autostart",
                "允许自启动",
                "OriginOS 的后台管控比较严格。",
                "设置 → 应用与权限 → 权限管理 → 自启动 → 打开「耳机闹钟」"
            ),
            ManualStep(
                "battery",
                "允许后台高耗电",
                "否则息屏后进程会被冻结。",
                "设置 → 电池 → 后台耗电管理 → 耳机闹钟 → 允许后台高耗电"
            )
        )

        isSamsung() -> listOf(
            ManualStep(
                "sleeping",
                "加入「从不休眠的应用」",
                "One UI 默认会把长期不用的应用放入休眠列表。",
                "设置 → 电池 → 后台使用限制 → 从不休眠的应用 → 添加「耳机闹钟」"
            )
        )

        else -> listOf(
            ManualStep(
                "battery",
                "确认电池优化已关闭",
                "部分系统会限制后台唤醒。",
                "设置 → 搜索「电池优化」→ 找到「耳机闹钟」→ 设为「不优化」"
            )
        )
    }
}
