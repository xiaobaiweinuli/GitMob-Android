plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.gitmob.android"
    compileSdk = 36
    ndkVersion = "29.0.14206865"
    ndkPath = "D:/15268/Desktop/android-ndk-r29"

    defaultConfig {
        applicationId = "com.gitmob.android"
        minSdk = 26
        targetSdk = 36
        // versionName 由 CI 通过 VERSION_NAME 环境变量注入（tag 触发时 = tag，如 1.2.3）
        // versionCode 由 CI 通过 VERSION_CODE 环境变量注入（= github.run_number，自动递增）
        // 本地开发回退默认值
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"
        // OAuth 凭据：CI 通过环境变量注入，本地开发时回退占位符
        val oauthClientId  = System.getenv("OAUTH_CLIENT_ID")  ?: "Ov23liP9mC2HXALHsFpk"
        val oauthCallback  = System.getenv("OAUTH_CALLBACK_URL") ?: "https://gitmob.16618888.xyz"
        buildConfigField("String", "GITHUB_CLIENT_ID",  "\"$oauthClientId\"")
        buildConfigField("String", "OAUTH_REDIRECT_URI", "\"$oauthCallback/callback\"")
        manifestPlaceholders["appScheme"] = "gitmob"
        manifestPlaceholders["appHost"] = "oauth"
    }
    signingConfigs {
        create("release") {
            storeFile = System.getenv("KEYSTORE_PATH")?.let { file(it) }
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: ""
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // lifecycle 2.8.x 内置的 NonNullableMutableLiveDataDetector 与 Kotlin 2.2.0
        // Analysis API 不兼容（KaCallableMemberCall class→interface 变化），导致 lintVital 崩溃
        disable += "NullSafeMutableLiveData"
        // R8 metadata 警告：Kotlin 版本 > R8 内置版本，属于已知警告，不影响运行时正确性
        abortOnError = false
    }
    // JGit 和 Flexmark 带有 META-INF 签名文件，需要排除以避免打包冲突
    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.md",
                "META-INF/NOTICE.txt",
                "META-INF/DEPENDENCIES",
                "META-INF/*.SF",
                "META-INF/*.RSA",
                "META-INF/*.DSA",
                "META-INF/services/org.eclipse.jgit.*",
                "META-INF/LICENSE-LGPL-2.1.txt",
                "META-INF/LICENSE-LGPL-3.txt",
                "META-INF/LICENSE-W3C-TEST",
                "mozilla/public-suffix-list.txt",
                "OSGI-INF/l10n/plugin.properties",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation(libs.androidx.browser)
    implementation(libs.androidx.splashscreen)
    implementation(libs.material)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.svg)
    implementation(libs.kotlinx.coroutines.android)
    // JGit：纯 Java Git 实现，无需外部 git 可执行文件
    implementation(libs.jgit)
    implementation(libs.jgit.apache.http)
    // Jackson YAML：用于解析 GitHub Actions workflow YAML 文件
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.jackson.module.kotlin)
    // Markdown 渲染：Flexmark + WebView + github-markdown-css
    implementation("com.vladsch.flexmark:flexmark-all:0.64.8")
    debugImplementation(libs.androidx.ui.tooling)
}
