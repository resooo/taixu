plugins {
    // 上游 v0.18.0 起改用 Convention Plugin 统一配置：
    // 该插件已自动提供 core:common / core:model / feature:components /
    // Compose UI / lifecycle-runtime-compose / koin-compose / coroutines。
    alias(libs.plugins.taixu.android.feature)
}

android {
    namespace = "top.wkbin.taixu.feature.adbautostart"
    resourcePrefix = "adb_autostart_"
}

dependencies {
    // 本模块额外需要的依赖（其余由 Convention Plugin 提供）
    implementation(project(":core:datastore"))
    implementation(project(":runtime"))
    implementation(libs.androidx.datastore.preferences)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
