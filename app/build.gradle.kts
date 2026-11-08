plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.apollo)
}

apollo {
    service("github") {
        packageName.set("com.gitmob.app.graphql")
        schemaFile.set(file("src/main/graphql/schema.graphqls"))
        // 只对 union/interface 注入 __typename（默认 ifFragments 会要求具体类型也返回 __typename）
        addTypename.set("ifAbstract")
        // GitHub schema 自定义标量先映射 String（DateTime 是否强类型在 B0 决策）
        mapScalar("URI", "kotlin.String")
        mapScalar("DateTime", "kotlin.String")
        mapScalar("GitObjectID", "kotlin.String")
        mapScalar("GitTimestamp", "kotlin.String")
        mapScalar("HTML", "kotlin.String")
        mapScalar("X509Certificate", "kotlin.String")
        mapScalar("GitSSHRemote", "kotlin.String")
        mapScalar("PreciseDateTime", "kotlin.String")
    }
}

android {
    namespace = "com.gitmob.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.gitmob.app"
        minSdk = 26
        targetSdk = 36
        // 所有构建共用版本来源；同一提交的 Debug、Release 和 CI 版本编号一致。
        versionCode = providers.exec {
            workingDir(rootProject.projectDir)
            commandLine("git", "rev-list", "--count", "HEAD")
        }.standardOutput.asText.get().trim().toInt()
        versionName = providers.gradleProperty("APP_VERSION_NAME").get().also {
            require(Regex("[0-9]+[.][0-9]+[.][0-9]+").matches(it)) {
                "APP_VERSION_NAME 必须为 major.minor.patch"
            }
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // CI 使用同一份发布密钥，确保后续版本可覆盖安装；本地未配置时沿用 Debug 签名。
    val releaseStorePath = providers.environmentVariable("RELEASE_KEYSTORE_PATH").orNull
    val releaseStorePassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").orNull
    val releaseKeyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
    val releaseKeyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
    val releaseSigningValues = listOf(releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    val hasReleaseSigning = releaseSigningValues.all { !it.isNullOrBlank() }
    require(releaseSigningValues.all { it == null } || hasReleaseSigning) {
        "发布签名需要完整设置 RELEASE_KEYSTORE_PATH、RELEASE_KEYSTORE_PASSWORD、RELEASE_KEY_ALIAS、RELEASE_KEY_PASSWORD"
    }
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStorePath))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true   // 用户要求：release 构建必须开启 R8 混淆
            isShrinkResources = true // 配合 minifyEnabled 删除无用资源
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (hasReleaseSigning) "release" else "debug")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    androidResources {
        // 扫描 values-* 自动生成 locales_config 并注入 manifest（Android 13+ 系统级应用语言入口）
        generateLocaleConfig = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
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
        jniLibs.keepDebugSymbols += setOf(
            "**/libandroidx.graphics.path.so",
            "**/libdatastore_shared_counter.so",
        )
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.jsoup)
    coreLibraryDesugaring(libs.desugar.nio)
    implementation(libs.apollo.runtime)
    implementation(libs.apollo.api)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
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
    implementation(libs.emoji.java)

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
    implementation(libs.kotaml)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.svg)
    implementation(libs.coil.gif)

    // 以下三个尚未在代码里接入，等"仓库详情->代码浏览"等对应功能开发时再实际使用，
    // 先按用户指定版本声明，见 libs.versions.toml 注释
    // implementation(libs.sora.editor)
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
    // Robolectric 下 createComposeRule() 需要 test-manifest 声明的测试 Activity
    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
