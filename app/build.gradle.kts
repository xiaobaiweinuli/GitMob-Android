import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.ide.common.vectordrawable.Svg2Vector
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.ByteArrayOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.apollo)
}

@CacheableTask
abstract class GenerateMaterialFileIcons : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val catalogFile: RegularFileProperty

    @get:OutputDirectory
    abstract val kotlinDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val catalog = catalogFile.get().asFile.reader(Charsets.UTF_8).use {
            JsonParser.parseReader(it).asJsonObject
        }
        val icons = catalog.getAsJsonObject("icons").entrySet().associate { it.key to it.value.asString }
        val symbols = icons.keys.associateWith { "file_icon_" + it.replace(Regex("[^a-z0-9_]"), "_") }
        require(symbols.values.toSet().size == symbols.size) { "File icon names collide" }
        require("folder-git" in icons && "folder-symlink" in icons) { "Missing repository entry icons" }

        fun quote(value: String): String = JsonPrimitive(value).toString().replace("$", "\\$")
        fun iconName(icon: String): String {
            require(icon == "file" || icon == "folder" || icon in icons) { "Missing file icon: $icon" }
            return quote(icon)
        }
        val source = StringBuilder("// Generated from material-file-icons.json. Do not edit.\n")
            .append("package com.gitmob.app.ui.icons\n\nimport androidx.compose.ui.graphics.vector.ImageVector\n\n")
            .append("internal object GeneratedFileIcons {\n")
        for (field in listOf("fileNames", "fileExtensions", "folderNames", "languageIds", "lightVariants", "icons")) {
            val entries = if (field == "icons") {
                symbols.toSortedMap().map { (name, symbol) -> quote(name) to "{ $symbol }" }
            } else {
                catalog.getAsJsonObject(field).entrySet().sortedBy { it.key }.map { (key, value) ->
                    (if (field == "lightVariants") iconName(key) else quote(key)) to iconName(value.asString)
                }
            }
            val valueType = if (field == "icons") "() -> ImageVector" else "String"
            val chunks = entries.chunked(200)
            source.append("    val $field: Map<String, $valueType> by lazy {\n        buildMap(${entries.size}) {\n")
            chunks.indices.forEach { source.append("            ${field}$it(this)\n") }
            source.append("        }\n    }\n")
            chunks.forEachIndexed { index, chunk ->
                source.append("\n    private fun ${field}$index(target: MutableMap<String, $valueType>) {\n")
                for ((key, value) in chunk) source.append("        target[$key] = $value\n")
                source.append("    }\n")
            }
        }
        source.append("}\n")

        val directory = kotlinDirectory.dir("com/gitmob/app/ui/icons").get().asFile.apply { mkdirs() }
        val expectedFiles = symbols.values.map { "$it.kt" }.toSet() + "GeneratedFileIcons.kt"
        directory.listFiles()?.filter { it.name !in expectedFiles }?.forEach {
            check(it.isFile && it.delete()) { "Unable to remove stale generated icon: $it" }
        }
        val svgDirectory = temporaryDir.resolve("svg").apply { mkdirs() }
        val parser = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        for ((name, svg) in icons.toSortedMap()) {
            val symbol = symbols.getValue(name)
            val input = svgDirectory.resolve("$symbol.svg").apply { writeText(svg) }
            val output = ByteArrayOutputStream()
            val diagnostic = Svg2Vector.parseSvgToXml(input.toPath(), output)
            check(diagnostic.isNullOrBlank() && output.size() > 0) {
                "Unable to convert file icon '$name': $diagnostic"
            }
            val vector = parser.parse(output.toByteArray().inputStream()).documentElement
            directory.resolve("$symbol.kt").writeText(imageVector(name, symbol, vector))
        }
        directory.resolve("GeneratedFileIcons.kt").writeText(source.toString())
        logger.lifecycle("Generated ${icons.size} Compose file icons and their lookup tables")
    }

    private fun Element.android(name: String, default: String = ""): String =
        getAttributeNS("http://schemas.android.com/apk/res/android", name).ifEmpty { default }

    private fun Element.children(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    private fun number(value: String): String = value.toFloat().also { require(it.isFinite()) }.toString() + "f"

    private fun color(value: String): String {
        val hex = value.removePrefix("#")
        val argb = when (hex.length) {
            3 -> "FF" + hex.flatMap { listOf(it, it) }.joinToString("")
            4 -> hex.flatMap { listOf(it, it) }.joinToString("")
            6 -> "FF$hex"
            8 -> hex
            else -> error("Unsupported icon color: $value")
        }
        return "Color(0x$argb)"
    }

    private fun brush(path: Element, attribute: String): String {
        val gradient = path.children().singleOrNull { it.getAttribute("name") == "android:$attribute" }
            ?.children()?.single()
        if (gradient == null) return path.android(attribute).takeIf { it.isNotEmpty() }
            ?.let { "SolidColor(${color(it)})" } ?: "null"
        check(gradient.tagName == "gradient") { "Unsupported icon brush: ${gradient.tagName}" }
        val stops = gradient.children().joinToString { "${number(it.android("offset"))} to ${color(it.android("color"))}" }
        fun offset(x: String, y: String) = "Offset(${number(gradient.android(x, "0"))}, ${number(gradient.android(y, "0"))})"
        return when (val type = gradient.android("type", "linear")) {
            "linear" -> "Brush.linearGradient($stops, start = ${offset("startX", "startY")}, end = ${offset("endX", "endY")})"
            "radial" -> "Brush.radialGradient($stops, center = ${offset("centerX", "centerY")}, radius = ${number(gradient.android("gradientRadius"))})"
            "sweep" -> "Brush.sweepGradient($stops, center = ${offset("centerX", "centerY")})"
            else -> error("Unsupported icon gradient: $type")
        }
    }

    private fun pathNodes(data: String): List<String> = buildList {
        val commands = Regex("([a-df-zA-DF-Z])([^a-df-zA-DF-Z]*)")
        val numbers = Regex("[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?")
        for (segment in commands.findAll(data)) {
            val commandChar = segment.groupValues[1].single()
            val parameters = segment.groupValues[2]
            require(numbers.replace(parameters, "").all { it.isWhitespace() || it == ',' }) { "Invalid icon path parameters" }
            val values = numbers.findAll(parameters).map { it.value.toFloat() }.toList()
            val type = commandChar.uppercaseChar()
            val (command, size) = when (type) {
                'M' -> "MoveTo" to 2
                'L' -> "LineTo" to 2
                'H' -> "HorizontalTo" to 1
                'V' -> "VerticalTo" to 1
                'C' -> "CurveTo" to 6
                'S' -> "ReflectiveCurveTo" to 4
                'Q' -> "QuadTo" to 4
                'T' -> "ReflectiveQuadTo" to 2
                'A' -> "ArcTo" to 7
                'Z' -> { add("PathNode.Close"); continue }
                else -> error("Unsupported icon path command: $commandChar")
            }
            require(values.size % size == 0) { "Invalid icon path command: $commandChar" }
            values.chunked(size).forEachIndexed { index, params ->
                val name = if (type == 'M' && index > 0) "LineTo" else command
                val prefix = if (commandChar.isLowerCase()) "Relative" else ""
                val arguments = params.mapIndexed { position, value ->
                    if (type == 'A' && position in 3..4) (value != 0f).toString() else number(value.toString())
                }.joinToString()
                add("PathNode.$prefix$name($arguments)")
            }
        }
    }

    private fun imageVector(name: String, symbol: String, vector: Element): String = buildString {
        val paths = mutableListOf<Element>()
        append("// Generated from material-file-icons.json. Do not edit.\n")
        append("package com.gitmob.app.ui.icons\n\n")
        append("import androidx.compose.ui.geometry.Offset\nimport androidx.compose.ui.graphics.*\n")
        append("import androidx.compose.ui.graphics.vector.ImageVector\nimport androidx.compose.ui.graphics.vector.PathNode\n")
        append("import androidx.compose.ui.unit.dp\n\n")
        append("internal val $symbol: ImageVector by lazy {\n    ImageVector.Builder(\n")
        append("        name = \"$name\",\n")
        append("        defaultWidth = ${vector.android("width").removeSuffix("dp")}.dp,\n")
        append("        defaultHeight = ${vector.android("height").removeSuffix("dp")}.dp,\n")
        append("        viewportWidth = ${number(vector.android("viewportWidth"))},\n")
        append("        viewportHeight = ${number(vector.android("viewportHeight"))},\n    ).apply {\n")
        fun pathReference(element: Element): String {
            paths.add(element)
            return "path${paths.lastIndex}()"
        }
        fun appendElements(parent: Element) {
            var clips = 0
            for (element in parent.children()) {
                when (element.tagName) {
                    "group" -> {
                        append("        addGroup(\n")
                        for ((attribute, argument) in listOf("rotation" to "rotate", "pivotX" to "pivotX", "pivotY" to "pivotY", "scaleX" to "scaleX", "scaleY" to "scaleY", "translateX" to "translationX", "translateY" to "translationY")) {
                            element.android(attribute).takeIf { it.isNotEmpty() }?.let { append("            $argument = ${number(it)},\n") }
                        }
                        append("        )\n")
                        appendElements(element)
                        append("        clearGroup()\n")
                    }
                    "clip-path" -> {
                        append("        addGroup(clipPathData = ${pathReference(element)})\n")
                        clips++
                    }
                    "path" -> {
                        append("        addPath(\n            pathData = ${pathReference(element)},\n")
                        append("            fill = ${brush(element, "fillColor")},\n            stroke = ${brush(element, "strokeColor")},\n")
                        for ((attribute, argument) in listOf("fillAlpha" to "fillAlpha", "strokeAlpha" to "strokeAlpha", "strokeWidth" to "strokeLineWidth", "strokeMiterLimit" to "strokeLineMiter")) {
                            element.android(attribute).takeIf { it.isNotEmpty() }?.let { append("            $argument = ${number(it)},\n") }
                        }
                        if (element.android("fillType") == "evenOdd") append("            pathFillType = PathFillType.EvenOdd,\n")
                        for ((attribute, argument, type) in listOf(Triple("strokeLineCap", "strokeLineCap", "StrokeCap"), Triple("strokeLineJoin", "strokeLineJoin", "StrokeJoin"))) {
                            element.android(attribute).takeIf { it.isNotEmpty() }?.let {
                                append("            $argument = $type.${it.replaceFirstChar(Char::titlecase)},\n")
                            }
                        }
                        append("        )\n")
                    }
                    else -> error("Unsupported vector element '${element.tagName}' in '$name'")
                }
            }
            repeat(clips) { append("        clearGroup()\n") }
        }
        appendElements(vector)
        append("    }.build()\n}\n")
        paths.forEachIndexed { index, path ->
            val nodes = pathNodes(path.android("pathData"))
            // Bound each method independently of the source path length.
            val chunks = nodes.chunked(200)
            append("\nprivate fun path$index(): List<PathNode> = buildList(${nodes.size}) {\n")
            chunks.indices.forEach { append("    path${index}Chunk$it(this)\n") }
            append("}\n")
            chunks.forEachIndexed { chunkIndex, chunk ->
                append("\nprivate fun path${index}Chunk$chunkIndex(target: MutableList<PathNode>) {\n")
                chunk.forEach { append("    target.add($it)\n") }
                append("}\n")
            }
        }
    }
}

val generateMaterialFileIcons = tasks.register<GenerateMaterialFileIcons>("generateMaterialFileIcons") {
    catalogFile.set(rootProject.layout.projectDirectory.file("tools/material-file-icons.json"))
    kotlinDirectory.set(layout.buildDirectory.dir("generated/materialFileIcons/kotlin"))
}

extensions.configure<ApplicationAndroidComponentsExtension> {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(generateMaterialFileIcons, GenerateMaterialFileIcons::kotlinDirectory)
    }
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
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

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
    jvmToolchain(21)
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

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.media3.ui.compose.material3)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.svg)
    implementation(libs.coil.gif)

    implementation(libs.sora.editor)

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
