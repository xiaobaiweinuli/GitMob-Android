package com.gitmob.app.core.markdown

import com.gitmob.app.data.model.RepoDocumentContext
import org.junit.Assert.*
import org.junit.Test

class RepoDocumentUrlTest {
    private val document = RepoDocumentContext("owner", "repo", "feature/docs", "docs/guide.md")

    @Test fun `relative paths keep repository and full ref across consecutive documents`() {
        val targets = mapOf(
            "images/example.png" to "docs/images/example.png",
            "../images/example.png" to "images/example.png",
            "/images/example.png" to "images/example.png",
            "翻译本页.md" to "docs/翻译本页.md",
            "%E7%BF%BB%E8%AF%91%E6%9C%AC%E9%A1%B5.md" to "docs/翻译本页.md",
            "space%20and+plus.md?raw=1#标题" to "docs/space and+plus.md",
            "100%25.md" to "docs/100%.md",
            "literal%2520.md" to "docs/literal%20.md",
        )
        targets.forEach { (href, expected) ->
            assertEquals(document.copy(filePath = expected), (document.resolveLink(href) as RepoDocumentLink.File).document)
            assertEquals(expected, document.resourcePath(href))
        }
        val next = (document.resolveLink("../other/start.md") as RepoDocumentLink.File).document
        assertEquals("other/images/example.png", next.resourcePath("images/example.png"))
        assertEquals("feature/docs", next.ref)
    }

    @Test fun `base URL encodes literal percent spaces and Chinese once`() {
        val special = document.copy(filePath = "docs/翻译 100%.md")
        assertEquals(special.filePath, special.baseUrl().pathSegments.joinToString("/"))
        assertTrue(special.baseUrl().toString().contains("%25"))
    }

    @Test fun `anchors external resources and unsafe schemes stay separate`() {
        assertEquals(RepoDocumentLink.Anchor, document.resolveLink("#section"))
        assertTrue(document.resolveLink("guide.md#section") is RepoDocumentLink.Anchor)
        assertTrue(document.resolveLink("https://github.com/owner/repo/issues/1") is RepoDocumentLink.External)
        assertTrue(document.resolveLink("//example.org/image.png") is RepoDocumentLink.External)
        assertNull(document.resourcePath("https://example.org/image.png"))
        listOf("file:///android_asset/docs/a.md", "javascript:alert(1)", "https://repo-docs.gitmob.invalid:444/a.png").forEach {
            assertEquals(RepoDocumentLink.Blocked, document.resolveLink(it))
        }
    }

    @Test fun `root README resolves the reported Chinese document link`() {
        val readme = document.copy(filePath = "README.md")
        assertEquals("docs/翻译本页.md", (readme.resolveLink("docs/翻译本页.md") as RepoDocumentLink.File).document.filePath)
    }

    @Test fun rawImagesMatchTheCompleteCurrentRefAndDecodeOnce() {
        assertEquals("images/图 100%.png", document.imageResourcePath("https://raw.githubusercontent.com/OWNER/Repo/feature/docs/images/%E5%9B%BE%20100%25.png"))
        assertEquals("images/literal%20.png", document.imageResourcePath("https://raw.githubusercontent.com/owner/repo/feature/docs/images/literal%2520.png"))
        assertEquals("docs/a.png", document.copy(ref = "main").imageResourcePath("https://raw.githubusercontent.com/owner/repo/main/docs/a.png?raw=1#image"))
    }

    @Test fun qualifiedBranchRawImagesMatchFullRefAndDecodeOnce() {
        val raw = "https://raw.githubusercontent.com/OWNER/Repo/refs/heads/feature/docs/images/literal%2520.png"
        assertEquals("images/literal%20.png", document.imageResourcePath(raw))
        assertEquals("docs/tips.png", document.copy(ref = "main").imageResourcePath(
            "https://raw.githubusercontent.com/owner/repo/refs/heads/main/docs/tips.png",
        ))
        assertEquals("images/literal%20.png", document.copy(ref = "refs/heads/feature/docs").imageResourcePath(raw))
        listOf(
            raw.replace("feature/docs", "feature/docs-other"),
            raw.replace("feature/docs", "feature/Docs"),
            raw.replace("OWNER/Repo", "other/Repo"),
            raw.replace("refs/heads", "refs/tags"),
            "https://raw.githubusercontent.com/owner/repo/refs/heads/feature/docs/",
        ).forEach { assertNull(it, document.imageResourcePath(it)) }
    }

    @Test fun directoryLinksWithoutTrailingSlashKeepTheirRepositoryPath() {
        val readme = document.copy(filePath = "README.md")
        assertEquals(readme.copy(filePath = "docs"), (readme.resolveLink("docs") as RepoDocumentLink.File).document)
        assertEquals(readme.copy(filePath = "examples"), (readme.resolveLink("./examples") as RepoDocumentLink.File).document)
    }

    @Test fun unrelatedOrMalformedRawUrlsAreNotReinterpreted() {
        listOf(
            "https://raw.githubusercontent.com/owner/repo/feature/docs-other/a.png",
            "https://raw.githubusercontent.com/owner/repo/feature/Docs/a.png",
            "https://raw.githubusercontent.com/other/repo/feature/docs/a.png",
            "https://raw.githubusercontent.com/owner/other/feature/docs/a.png",
            "https://raw.githubusercontent.com/owner/repo/feature/docs",
            "https://raw.githubusercontent.com/owner/repo/feature/docs/",
            "https://raw.githubusercontent.com/owner",
            "https://raw.githubusercontent.com:444/owner/repo/feature/docs/a.png",
            "https://user@raw.githubusercontent.com/owner/repo/feature/docs/a.png",
            "http://raw.githubusercontent.com/owner/repo/feature/docs/a.png",
            "https://raw.githubusercontent.com.evil.test/owner/repo/feature/docs/a.png",
            "https://raw.githubusercontent.com/owner/repo/feature/docs/a%00.png",
        ).forEach { assertNull(it, document.imageResourcePath(it)) }
    }

    @Test fun rawImageResolutionDoesNotChangeExternalLinkNavigation() {
        val url = "https://raw.githubusercontent.com/owner/repo/feature/docs/a.png"
        assertNull(document.resourcePath(url))
        assertEquals(RepoDocumentLink.External(url), document.resolveLink(url))
    }

}
