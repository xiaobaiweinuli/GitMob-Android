pluginManagement {
    repositories {
        // GitHub Actions 使用官方源；本地使用腾讯云镜像加速。
        if (providers.environmentVariable("GITHUB_ACTIONS").orNull != "true") {
            maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (providers.environmentVariable("GITHUB_ACTIONS").orNull != "true") {
            maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        }
        google()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "gitmob"
include("app")
