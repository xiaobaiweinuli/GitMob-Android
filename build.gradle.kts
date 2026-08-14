// 顶层构建脚本：只声明插件版本，不 apply（各模块按需 apply）
plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9+ 内置 Kotlin 支持，模块级不再 apply 这个插件（见 app/build.gradle.kts），
    // 但根目录这行 apply false 要保留——单纯放进 classpath，用来让 AGP 内置 Kotlin
    // 支持采用我们指定的 2.4.10，而不是 AGP 自己默认内置的版本（目前是 2.2.10）。
    // 参照 KernelSU manager 真实项目的做法核实过，不是遗留代码。
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
