package top.wkbin.taixu.feature.adbautostart

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 无线调试自持控制器。
 *
 * 独立于 `runtime` 模块，承载「一次性 pm grant 点火 → 自行开关无线调试」的全部逻辑，
 * 便于在上游更新时保持零冲突（本模块为新增模块，上游不存在同名路径）。
 *
 * 原理（借鉴 Shizuku 13.6.0 的 BootCompleteReceiver）：
 * `WRITE_SECURE_SETTINGS` 属 signature|privileged|development 级权限，应用无法自行申请，
 * 但 development 权限支持 `pm grant`。用户首次完成无线 ADB 配对连接后，经该通道执行一次
 * `pm grant <包名> android.permission.WRITE_SECURE_SETTINGS` 即可点火，
 * 此后本应用可直接写 Settings.Global，自行开关无线调试，无需 Shizuku/Root。
 */
class WirelessAdbController(
    private val context: Context,
) {

    /** 是否已持有 WRITE_SECURE_SETTINGS（决定能否直写 Settings.Global）。 */
    fun hasSecureSettingsPermission(): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            true
        } else {
            context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** 当前平台是否支持无线调试（Android 11+）。 */
    fun isPlatformSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * 一次性点火：把 WRITE_SECURE_SETTINGS 授予本应用自身。
     *
     * @param executor 由调用方注入的 ADB 执行器（返回 true 表示命令执行成功），
     *                 避免本模块反向依赖具体的 ADB 实现。
     */
    suspend fun grantSecureSettings(executor: suspend (String) -> Boolean): WirelessAdbGrantResult =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return@withContext WirelessAdbGrantResult(false, false, "系统版本过低，不支持该授权方式。")
            }
            if (hasSecureSettingsPermission()) {
                return@withContext WirelessAdbGrantResult(true, true, "WRITE_SECURE_SETTINGS 已授权，无需重复操作。")
            }

            val command = "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
            val executed = runCatching { executor(command) }.getOrDefault(false)
            val granted = hasSecureSettingsPermission()

            WirelessAdbGrantResult(
                success = granted,
                granted = granted,
                message = when {
                    granted -> "授权成功：已获得系统设置写入权限，此后可自动开关无线调试。"
                    !executed -> "授权命令执行失败，请确认 ADB 已连接后重试。"
                    else -> "授权命令已执行，但权限尚未生效，请稍后重试或重启应用。"
                },
            )
        }

    /**
     * 自持开启无线调试：直接写三把 Global 开关。
     *
     * ① adb_wifi_enabled=1             打开无线调试
     * ② adb_enabled=1                  打开 ADB 总开关
     * ③ adb_allowed_connection_time=0  已授权主机连接永不过期（防止系统自动关闭无线调试）
     *
     * @param shellFallback 无自持权限时的可选回退执行器（如 Shizuku/Root shell）。
     */
    suspend fun enableWirelessAdb(
        shellFallback: (suspend (String) -> Boolean)? = null,
    ): WirelessAdbStateResult = withContext(Dispatchers.IO) {
        if (!isPlatformSupported()) {
            return@withContext WirelessAdbStateResult(false, false, false, "Android 11 以下不支持无线调试。")
        }

        // 路径一：自持权限直写（首选，零外部依赖）
        if (hasSecureSettingsPermission()) {
            val wrote = runCatching {
                val cr = context.contentResolver
                Settings.Global.putInt(cr, KEY_ADB_WIFI_ENABLED, 1)
                Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
                Settings.Global.putLong(cr, KEY_ADB_ALLOWED_CONNECTION_TIME, 0L)
            }.isSuccess

            val current = readStateDirect()
            return@withContext WirelessAdbStateResult(
                success = current.wirelessEnabled,
                wirelessEnabled = current.wirelessEnabled,
                adbEnabled = current.adbEnabled,
                message = when {
                    current.wirelessEnabled -> "已开启无线调试（自持权限）。"
                    !wrote -> "写入系统设置失败，权限可能未完全生效。"
                    else -> "开关已写入，系统尚未生效，请稍候重试。"
                },
            )
        }

        // 路径二：外部 shell 回退
        if (shellFallback != null) {
            runCatching { shellFallback(WIRELESS_ADB_ENABLE_COMMAND) }
            val after = readStateDirect()
            return@withContext WirelessAdbStateResult(
                success = after.wirelessEnabled,
                wirelessEnabled = after.wirelessEnabled,
                adbEnabled = after.adbEnabled,
                message = if (after.wirelessEnabled) "已通过备用通道开启无线调试。" else "开启失败，请检查权限。",
            )
        }

        WirelessAdbStateResult(
            success = false,
            wirelessEnabled = false,
            adbEnabled = false,
            message = "尚未开启，请先点击「启用」完成一次性授权。",
        )
    }

    /** 只读查询无线调试开关状态（ContentResolver 直读，无需任何权限）。 */
    suspend fun readState(): WirelessAdbStateResult = withContext(Dispatchers.IO) {
        if (!isPlatformSupported()) {
            return@withContext WirelessAdbStateResult(false, false, false, "Android 11 以下不支持无线调试。")
        }
        val direct = readStateDirect()
        WirelessAdbStateResult(
            success = direct.wirelessEnabled,
            wirelessEnabled = direct.wirelessEnabled,
            adbEnabled = direct.adbEnabled,
            message = when {
                direct.wirelessEnabled -> "无线调试已开启。"
                hasSecureSettingsPermission() -> "无线调试未开启，可点击下方按钮自动开启。"
                else -> "无线调试未开启，请先点击「启用」完成一次性授权。"
            },
        )
    }

    private fun readStateDirect(): WirelessAdbStateResult = runCatching {
        val cr = context.contentResolver
        val wifi = Settings.Global.getInt(cr, KEY_ADB_WIFI_ENABLED, 0) == 1
        val adb = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1
        WirelessAdbStateResult(wifi, wifi, adb, "")
    }.getOrElse { WirelessAdbStateResult(false, false, false, "") }

    companion object {
        /** 系统「无线调试」开关所在的 Global 键（与 Shizuku 13.6.0 使用的键一致）。 */
        const val KEY_ADB_WIFI_ENABLED = "adb_wifi_enabled"
        const val KEY_ADB_ALLOWED_CONNECTION_TIME = "adb_allowed_connection_time"

        /** 备用通道命令：经 shell/Root 写入三把开关。 */
        private const val WIRELESS_ADB_ENABLE_COMMAND =
            "/system/bin/settings put global $KEY_ADB_WIFI_ENABLED 1; " +
                "/system/bin/settings put global adb_enabled 1; " +
                "/system/bin/settings put global $KEY_ADB_ALLOWED_CONNECTION_TIME 0"
    }
}

/** `pm grant WRITE_SECURE_SETTINGS` 点火结果。 */
data class WirelessAdbGrantResult(
    val success: Boolean,
    val granted: Boolean,
    val message: String,
)

/** 无线调试开关状态。 */
data class WirelessAdbStateResult(
    val success: Boolean,
    val wirelessEnabled: Boolean,
    val adbEnabled: Boolean,
    val message: String,
)
