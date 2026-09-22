package top.wkbin.taixu.feature.adbautostart

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 开机自动恢复无线调试。
 *
 * 借鉴 Shizuku 13.6.0 的 `BootCompleteReceiver`：Android 13+ 上系统会在重启后关闭无线调试，
 * 导致依赖 ADB 的能力（AI 执行 shell、抓日志、构建后安装 APK）全部失效。
 * 该 Receiver 在开机完成后静默恢复开关与连接，用户无需再次手动进入开发者选项。
 *
 * 生效前提（全部满足才动作）：
 * 1. 用户已在设置页开启「开机自动恢复」开关；
 * 2. 分布式偏好中已记录曾完成过配对（避免对从未使用的用户造成打扰）；
 * 3. 已持有 WRITE_SECURE_SETTINGS（已完成一次性 pm grant 点火）。
 */
class AdbBootReceiver : BroadcastReceiver(), KoinComponent {

    private val controller: WirelessAdbController by inject()
    private val preferences: AdbAutostartPreferences by inject()
    private val autoStarter: WirelessAdbAutoStarter by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (!preferences.restoreOnBoot.first()) {
                    Log.i(TAG, "开机恢复未启用，跳过")
                    return@launch
                }
                if (!preferences.pairedOnce.first()) {
                    Log.i(TAG, "从未完成配对，跳过开机恢复")
                    return@launch
                }
                if (!controller.hasSecureSettingsPermission()) {
                    Log.i(TAG, "缺少 WRITE_SECURE_SETTINGS，跳过开机恢复")
                    return@launch
                }

                // 静默开启无线调试并等待端口自动连接：
                // 用户解锁手机时无线 ADB 通常已就绪，后续 AI 操作无需再次授权。
                val result = autoStarter.ensureReady()
                Log.i(TAG, "开机自动恢复无线调试：${result.fold({ "成功" }, { it.message ?: "失败" })}")
            } catch (error: Throwable) {
                // 开机广播中任何异常都不应影响系统启动流程，仅记录。
                Log.w(TAG, "开机自动恢复无线调试异常", error)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "AdbBootReceiver"
    }
}
