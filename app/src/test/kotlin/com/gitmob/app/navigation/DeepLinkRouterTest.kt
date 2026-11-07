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
    fun `Gist链接按域名解析并兼容旧版短ID`() {
        val id = "c38cacab22bf0fffb94b247e09a4d948"
        listOf(
            "https://gist.github.com/std-microblock/$id",
            "http://gist.github.com/$id",
            "https://gist.github.com/std-microblock/$id/?ref=share#file-main-kt",
            "HTTPS://GIST.GITHUB.COM/$id",
        ).forEach { url ->
            assertEquals(url, DeepLinkDestination.GistDetail(id), DeepLinkRouter.parse(Uri.parse(url)))
        }
        assertEquals(DeepLinkDestination.GistDetail("AbC123"), DeepLinkRouter.parse(Uri.parse("https://gist.github.com/AbC123")))
    }

    @Test
    fun `Gist非详情路径和无效ID交给浏览器`() {
        listOf("", "std-microblock", "discover", "std-microblock/not-a-gist", "owner/abc/revision",
            "owner/abc/raw", "owner/abc/archive.zip", "owner/abc/download", "owner/abc/def123",
        ).forEach { path ->
            val url = "https://gist.github.com/$path"
            assertEquals(url, DeepLinkDestination.Unsupported, DeepLinkRouter.parse(Uri.parse(url)))
        }
    }

    @Test
    fun `只接受HTTP协议和精确GitHub域名`() {
        listOf("https://example.com/owner/repo", "https://github.com.example.com/owner/repo",
            "https://gist.github.com.example.com/owner/abc", "https://gist.githubusercontent.com/owner/abc/raw/file",
            "ftp://github.com/owner/repo", "intent://gist.github.com/owner/abc", "/owner/repo",
        ).forEach { url ->
            assertEquals(url, DeepLinkDestination.Unsupported, DeepLinkRouter.parse(Uri.parse(url)))
        }
        assertEquals(DeepLinkDestination.RepoOverview("owner", "repo"), DeepLinkRouter.parse(Uri.parse("http://github.com/owner/repo")))
    }


    @Test
    fun `解析仓库blob路径为文件查看目的地`() {
        val uri = Uri.parse("https://github.com/owner/repo/blob/main/src/App.kt")
        assertEquals(
            DeepLinkDestination.FileView("owner", "repo", "main", "src/App.kt"),
            DeepLinkRouter.parse(uri),
        )
    }

    @Test
    fun `解析仓库tree路径保留目录`() {
        assertEquals(
            DeepLinkDestination.DirView("owner", "repo", "main", "docs/api"),
            DeepLinkRouter.parse(Uri.parse("https://github.com/owner/repo/tree/main/docs/api")),
        )
    }

    @Test
    fun `提交版本的文件和目录深链接保留SHA和路径`() {
        val commit = "a".repeat(40)
        assertEquals(
            DeepLinkDestination.FileView("owner", "repo", commit, "docs/guide.md"),
            DeepLinkRouter.parse(Uri.parse("https://github.com/owner/repo/blob/$commit/docs/guide.md")),
        )
        assertEquals(
            DeepLinkDestination.DirView("owner", "repo", commit, "docs/api"),
            DeepLinkRouter.parse(Uri.parse("https://github.com/owner/repo/tree/$commit/docs/api")),
        )
        assertEquals(
            DeepLinkDestination.DirView("owner", "repo", commit, ""),
            DeepLinkRouter.parse(Uri.parse("https://github.com/owner/repo/tree/$commit")),
        )
    }

    @Test
    fun `解析提交和提交历史路径`() {
        assertEquals(
            DeepLinkDestination.CommitDetail("owner", "repo", "abcdef1"),
            DeepLinkRouter.parse(Uri.parse("https://github.com/owner/repo/commit/abcdef1")),
        )
        assertEquals(
            DeepLinkDestination.CommitList("owner", "repo", "main", "src"),
            DeepLinkRouter.parse(Uri.parse("https://github.com/owner/repo/commits/main/src")),
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

    @Test
    fun `pulls列表和pull详情分别解析`() {
        assertEquals(DeepLinkDestination.PullRequestList("owner", "repo"), DeepLinkRouter.parse(Uri.parse("https://github.com/owner/repo/pulls")))
        assertEquals(DeepLinkDestination.PullRequestDetail("owner", "repo", 7), DeepLinkRouter.parse(Uri.parse("https://github.com/owner/repo/pull/7")))
    }

    @Test
    fun `actions列表和运行详情分别解析`() {
        assertEquals(DeepLinkDestination.Actions("owner", "repo"), DeepLinkRouter.parse(Uri.parse("https://github.com/owner/repo/actions")))
        assertEquals(DeepLinkDestination.WorkflowRun("owner", "repo", 123L), DeepLinkRouter.parse(Uri.parse("https://github.com/owner/repo/actions/runs/123")))
    }

    @Test
    fun `发行版和贡献者链接解析`() {
        assertEquals(DeepLinkDestination.ReleaseList("owner", "repo"), DeepLinkRouter.parse(Uri.parse("https://github.com/owner/repo/releases")))
        assertEquals(DeepLinkDestination.ReleaseDetail("owner", "repo", "v1.0.0"), DeepLinkRouter.parse(Uri.parse("https://github.com/owner/repo/releases/tag/v1.0.0")))
        assertEquals(DeepLinkDestination.Contributors("owner", "repo"), DeepLinkRouter.parse(Uri.parse("https://github.com/owner/repo/graphs/contributors")))
    }
}
