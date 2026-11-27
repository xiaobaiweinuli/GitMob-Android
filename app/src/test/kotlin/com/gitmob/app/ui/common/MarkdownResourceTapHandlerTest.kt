package com.gitmob.app.ui.common

import android.webkit.WebView.HitTestResult
import com.gitmob.app.data.model.DocumentResource
import org.junit.Assert.*
import org.junit.Test

class MarkdownResourceTapHandlerTest {
    @Test fun ordinaryImageOpensItsSource() {
        assertEquals(DocumentResource("https://example.test/picture.png"),
            imageFromHit(HitTestResult.IMAGE_TYPE, "https://example.test/picture.png", null, null))
    }

    @Test fun linkedImageUsesSrcAndPreservesDifferentDestination() {
        assertEquals(DocumentResource("https://example.test/badge.svg", "https://example.test/releases"),
            imageFromHit(HitTestResult.SRC_IMAGE_ANCHOR_TYPE, "https://example.test/releases",
                "https://example.test/badge.svg", "https://example.test/releases"))
    }

    @Test fun linkedImageWithoutSrcNeverTreatsDestinationAsImage() {
        assertNull(imageFromHit(HitTestResult.SRC_IMAGE_ANCHOR_TYPE, "https://example.test/releases", null, null))
    }

    @Test fun missingHrefFallsBackToHitDestination() {
        assertEquals("../README.md", imageFromHit(HitTestResult.SRC_IMAGE_ANCHOR_TYPE,
            "../README.md", "images/demo.png", null)?.originalLink)
    }

    @Test fun textLinksAndEmptyImagesKeepNormalBehavior() {
        assertNull(imageFromHit(HitTestResult.SRC_ANCHOR_TYPE, "https://example.test", null, null))
        assertNull(imageFromHit(HitTestResult.IMAGE_TYPE, "", null, null))
    }

    @Test fun galleryKeepsDocumentOrderAndResolvesEachImagesLink() {
        val images = resourcesFromHtml("""
            <img src="outside.png">
            <article class="markdown-body">
                <a href="../releases"><img src="one.png"></a>
                <picture><source srcset="two-large.png"><img src="two.png"></picture>
                <img src="">
            </article>
        """.trimIndent(), "https://example.test/docs/README.md")
        assertEquals(listOf(
            DocumentResource("https://example.test/docs/one.png", "https://example.test/releases"),
            DocumentResource("https://example.test/docs/two.png"),
        ), images)
        val gallery = resourceGallery(images, images[1])
        assertEquals(images, gallery.resources)
        assertEquals(1, gallery.initialIndex)
    }

    @Test fun sameImageWithDifferentLinksOpensTheSelectedOccurrence() {
        val images = listOf(DocumentResource("https://example.test/badge.svg", "https://example.test/one"),
            DocumentResource("https://example.test/badge.svg", "https://example.test/two"))
        assertEquals(1, resourceGallery(images, images[1]).initialIndex)
    }

    @Test fun ambiguousNativeHitDoesNotChooseAnUnrelatedOccurrence() {
        val image = DocumentResource("https://example.test/repeated.png")
        val gallery = resourceGallery(listOf(image, DocumentResource("https://example.test/other.png"), image), image)
        assertEquals(listOf(image), gallery.resources)
        assertEquals(0, gallery.initialIndex)
    }

    @Test fun responsiveSourceMissingFromStaticHtmlStillOpensTheTappedImage() {
        val displayed = DocumentResource("https://example.test/large.png")
        assertEquals(listOf(displayed), resourceGallery(listOf(DocumentResource("https://example.test/small.png")), displayed).resources)
    }
    @Test fun mixedGalleryPreservesOrderAndLeavesOrdinaryLinksAlone() {
        val resources = resourcesFromHtml("""
            <article class="markdown-body">
                <img src="one.png">
                <a href="clip.mp4">Video</a><a href="song.mp3">Audio</a>
                <a href="README.md">Document</a><a href="#clip.mp4">Anchor</a>
                <a href="https://github.com/o/r/blob/main/movie.mp4">File page</a>
                <a href="https://github.com/user-attachments/assets/id">Attachment</a>
                <a href="https://example.test/docs">Other site</a>
            </article>
        """, "https://example.test/docs/README.md")
        assertEquals(listOf("https://example.test/docs/one.png", "https://example.test/docs/clip.mp4",
            "https://example.test/docs/song.mp3", "https://github.com/user-attachments/assets/id"), resources.map { it.source })
        assertEquals(listOf(com.gitmob.app.data.model.DocumentResourceKind.IMAGE,
            com.gitmob.app.data.model.DocumentResourceKind.VIDEO,
            com.gitmob.app.data.model.DocumentResourceKind.AUDIO,
            com.gitmob.app.data.model.DocumentResourceKind.UNKNOWN), resources.map { it.kind })
    }

    @Test fun cleanedMediaKeepsRelativeSourceAndNeverCreatesDuplicateTranslationItems() {
        val prepared = com.gitmob.app.data.translation.TranslationDocuments.prepareHtml(
            com.gitmob.app.data.translation.TranslationTarget("test", """
                <video controls><source src="movie" type="video/mp4"></video><audio src="track"></audio>
            """, html = true))
        val resources = resourcesFromHtml("<article class='markdown-body'>${prepared.safeHtml}</article>",
            "https://example.test/docs/README.md")
        assertEquals(2, resources.size)
        assertEquals("https://example.test/docs/movie", resources[0].source)
        assertEquals("video/mp4", resources[0].mimeType)
        assertEquals(com.gitmob.app.data.model.DocumentResourceKind.AUDIO, resources[1].kind)
    }

}
