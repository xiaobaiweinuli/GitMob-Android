pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://mirrors.cloud.tencent.com/repository/maven/") }
        maven { url = uri("https://mirrors.aliyun.com/repository/public/") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://mirrors.cloud.tencent.com/repository/maven/") }
        maven { url = uri("https://mirrors.aliyun.com/repository/public/") }
    }
}
rootProject.name = "GitMob"
include(":app")
