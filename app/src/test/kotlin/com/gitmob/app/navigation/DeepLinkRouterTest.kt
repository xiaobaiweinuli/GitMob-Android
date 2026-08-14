package com.gitmob.app.navigation

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// android.net.Uri 是 Android 框架类，纯 JVM 单测跑不了真实实现，需要 Robolectric，
// 但依然不需要模拟器/真机/APK，仍在 ./gradlew test 范围内几秒跑完。
// Robolectric 4.14 官方仅支持 JUnit4 的 @RunWith 机制，本项目所有测试统一 JUnit4
// （SKILL.md 第七节红线：只用 JUnit4，不引入 JUnit5，不混两套框架）。
// 指定 sdk=[34] 是因为 compileSdk=37 在 Robolectric 4.14 还没对应 android-all 影子实现，
// 选 34 保证 Uri.parse 等 API 可被 shadow 正确执行；manifest=NONE 避免去解析无用 manifest。
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DeepLinkRouterTest {

    @Test
    fun `解析仓库blob路径为文件查看目的地`() {
        val uri = Uri.parse("https://github.com/owner/repo/blob/main/src/App.kt")
        assertEquals(
            DeepLinkDestination.FileView("owner", "repo", "main", "src/App.kt"),
            DeepLinkRouter.parse(uri),
        )
    }

    @Test
    fun `orgs前缀路径解析为Profile目的地`() {
        val uri = Uri.parse("https://github.com/orgs/github")
        assertEquals(DeepLinkDestination.Profile("github"), DeepLinkRouter.parse(uri))
    }

    @Test
    fun `单段路径解析为Profile目的地`() {
        val uri = Uri.parse("https://github.com/torvalds")
        assertEquals(DeepLinkDestination.Profile("torvalds"), DeepLinkRouter.parse(uri))
    }

    @Test
    fun `issues详情页解析出编号`() {
        val uri = Uri.parse("https://github.com/owner/repo/issues/42")
        assertEquals(DeepLinkDestination.IssueDetail("owner", "repo", 42), DeepLinkRouter.parse(uri))
    }

    @Test
    fun `issues列表页无编号`() {
        val uri = Uri.parse("https://github.com/owner/repo/issues")
        assertEquals(DeepLinkDestination.IssueList("owner", "repo"), DeepLinkRouter.parse(uri))
    }
}
