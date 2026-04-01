pluginManagement {
    repositories {
        maven { url = uri("https://mirrors.cloud.tencent.com/repository/maven/") }
        maven { url = uri("https://mirrors.aliyun.com/repository/public/") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://mirrors.cloud.tencent.com/repository/maven/") }
        maven { url = uri("https://mirrors.aliyun.com/repository/public/") }
        google()
        mavenCentral()
    }
}
rootProject.name = "GitMob"
include(":app")
