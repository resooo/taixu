package top.wkbin.taixu.feature.adbautostart

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.adbAutostartStore: DataStore<Preferences> by preferencesDataStore(
    name = "adb_autostart_settings",
)

/**
 * 无线调试自动化的偏好项。
 *
 * 使用独立 DataStore（`adb_autostart_settings`），与上游的 `settings_data_store` 完全隔离：
 * 上游更新时本模块既不需要改动其偏好定义，也不会因键名冲突互相覆盖。
 */
class AdbAutostartPreferences(
    private val context: Context,
) {

    /** 开机后是否自动恢复无线调试。 */
    val restoreOnBoot: Flow<Boolean> =
        context.adbAutostartStore.data.map { it[KEY_RESTORE_ON_BOOT] ?: false }

    /** 是否曾成功完成过无线 ADB 配对（避免对未使用该功能的用户触发开机恢复）。 */
    val pairedOnce: Flow<Boolean> =
        context.adbAutostartStore.data.map { it[KEY_PAIRED_ONCE] ?: false }

    /** 是否在检测到需要 ADB 时自动开启无线调试（默认开启）。 */
    val autoEnableOnDemand: Flow<Boolean> =
        context.adbAutostartStore.data.map { it[KEY_AUTO_ENABLE_ON_DEMAND] ?: true }

    suspend fun setRestoreOnBoot(enabled: Boolean) {
        context.adbAutostartStore.edit { it[KEY_RESTORE_ON_BOOT] = enabled }
    }

    suspend fun setPairedOnce(value: Boolean) {
        context.adbAutostartStore.edit { it[KEY_PAIRED_ONCE] = value }
    }

    suspend fun setAutoEnableOnDemand(enabled: Boolean) {
        context.adbAutostartStore.edit { it[KEY_AUTO_ENABLE_ON_DEMAND] = enabled }
    }

    /** 一次性读取全部开关，供无协程上下文（如 Receiver）使用。 */
    suspend fun snapshot(): Snapshot = Snapshot(
        restoreOnBoot = restoreOnBoot.first(),
        pairedOnce = pairedOnce.first(),
        autoEnableOnDemand = autoEnableOnDemand.first(),
    )

    data class Snapshot(
        val restoreOnBoot: Boolean,
        val pairedOnce: Boolean,
        val autoEnableOnDemand: Boolean,
    )

    private companion object {
        val KEY_RESTORE_ON_BOOT = booleanPreferencesKey("restore_on_boot")
        val KEY_PAIRED_ONCE = booleanPreferencesKey("paired_once")
        val KEY_AUTO_ENABLE_ON_DEMAND = booleanPreferencesKey("auto_enable_on_demand")
    }
}
