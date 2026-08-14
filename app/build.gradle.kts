plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.gitmob.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.gitmob.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    /**
     * 签名方案 A（对齐 SKILL.md 第 9 节）：
     * 直接复用 Android Studio 首次构建 debug APK 时自动生成的 debug.keystore
     * （位置：~/.android/debug.keystore，跨平台 fallback 链见下方代码）。
     *   - 优点：不需要申请真实签名密钥、不需要设置环境变量、开箱即用；
     *           assembleRelease 产出的 APK 可直接 adb install 到任何真机 / 模拟器。
     *   - 注意：该 keystore 是公共默认值（密码 "android"，alias "androiddebugkey"），
     *           仅用于 CI / 测试构建，**不能用于 Google Play 分发**。
     *           如果后续要生成正式分发包，把 storeFile/storePassword/keyAlias/keyPassword
     *           改成从系统环境变量（RELEASE_KEYSTORE_*）读取即可，本文件不要硬编码真实密钥。
     */
    signingConfigs {
        create("release") {
            // 跨平台查找 ~/.android/debug.keystore（Windows 为 %USERPROFILE%\.android）
            val userHome = System.getProperty("user.home")
                ?: System.getenv("USERPROFILE")
                ?: System.getenv("HOME")
                ?: throw GradleException("找不到用户 home 目录，无法定位 debug.keystore")
            val debugKeystore = File(userHome, ".android${File.separator}debug.keystore")
            if (!debugKeystore.exists()) {
                throw GradleException(
                    "方案 A 签名文件不存在：$debugKeystore\n" +
                        "请先执行一次 ./gradlew assembleDebug 让 Android Gradle 插件自动生成，" +
                        "或在 Android Studio 里任意运行一次 debug 构建。"
                )
            }
            storeFile = debugKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true   // 用户要求：release 构建必须开启 R8 混淆
            isShrinkResources = true // 配合 minifyEnabled 删除无用资源
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3) // 显式版本，不完全依赖 BOM 托管，见 libs.versions.toml 注释
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.materialkolor)

    // Markdown 渲染，见 references/markdown-rendering.md
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.autolink)
    implementation(libs.commonmark.ext.task.list.items)

    // Navigation 3（不是 Navigation 2 / navigation-compose，API 完全不同）
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation3.lifecycle)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // 以下三个尚未在代码里接入，等"仓库详情->代码浏览"等对应功能开发时再实际使用，
    // 先按用户指定版本声明，见 libs.versions.toml 注释
    // implementation(libs.sora.editor)
    // implementation(libs.kotaml)
    // implementation(libs.appiconloader)
    // implementation(libs.parcelablelist)

    // ---- 测试：统一 JUnit4（Robolectric 官方仅支持 JUnit4 的 @RunWith 机制，
    // 小项目不混 JUnit5，见 references/testing.md），全部纯 JVM，不需要模拟器/真机 ----
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(platform(libs.androidx.compose.bom))
}
