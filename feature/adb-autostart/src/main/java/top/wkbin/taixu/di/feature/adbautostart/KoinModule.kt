package top.wkbin.taixu.di.feature.adbautostart

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import top.wkbin.taixu.feature.adbautostart.AdbAutostartPreferences
import top.wkbin.taixu.feature.adbautostart.AdbAutostartViewModel
import top.wkbin.taixu.feature.adbautostart.WirelessAdbAutoStarter
import top.wkbin.taixu.feature.adbautostart.WirelessAdbController

/** 无线调试自动化模块的依赖注册。 */
val featureAdbAutostartModule = module {
    single { WirelessAdbController(context = androidContext()) }
    single { AdbAutostartPreferences(context = androidContext()) }
    single { WirelessAdbAutoStarter(controller = get(), adbManager = get()) }
    viewModel<AdbAutostartViewModel> {
        AdbAutostartViewModel(
            controller = get(),
            preferences = get(),
            autoStarter = get(),
            adbManager = get(),
        )
    }
}
