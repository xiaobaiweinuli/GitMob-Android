package com.gitmob.app.ui.common

import android.webkit.WebView.HitTestResult
import com.gitmob.app.data.model.DocumentImage
import org.junit.Assert.*
import org.junit.Test

class MarkdownImageTapHandlerTest {
    @Test fun ordinaryImageOpensItsSource() {
        assertEquals(DocumentImage("https://example.test/picture.png"),
            imageFromHit(HitTestResult.IMAGE_TYPE, "https://example.test/picture.png", null, null))
    }

    @Test fun linkedImageUsesSrcAndPreservesDifferentDestination() {
        assertEquals(DocumentImage("https://example.test/badge.svg", "https://example.test/releases"),
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
        val images = imagesFromHtml("""
            <img src="outside.png">
            <article class="markdown-body">
                <a href="../releases"><img src="one.png"></a>
                <picture><source srcset="two-large.png"><img src="two.png"></picture>
                <img src="">
            </article>
        """.trimIndent(), "https://example.test/docs/README.md")
        assertEquals(listOf(
            DocumentImage("https://example.test/docs/one.png", "https://example.test/releases"),
            DocumentImage("https://example.test/docs/two.png"),
        ), images)
        val gallery = imageGallery(images, images[1])
        assertEquals(images, gallery.images)
        assertEquals(1, gallery.initialIndex)
    }

    @Test fun sameImageWithDifferentLinksOpensTheSelectedOccurrence() {
        val images = listOf(DocumentImage("https://example.test/badge.svg", "https://example.test/one"),
            DocumentImage("https://example.test/badge.svg", "https://example.test/two"))
        assertEquals(1, imageGallery(images, images[1]).initialIndex)
    }

    @Test fun ambiguousNativeHitDoesNotChooseAnUnrelatedOccurrence() {
        val image = DocumentImage("https://example.test/repeated.png")
        val gallery = imageGallery(listOf(image, DocumentImage("https://example.test/other.png"), image), image)
        assertEquals(listOf(image), gallery.images)
        assertEquals(0, gallery.initialIndex)
    }

    @Test fun responsiveSourceMissingFromStaticHtmlStillOpensTheTappedImage() {
        val displayed = DocumentImage("https://example.test/large.png")
        assertEquals(listOf(displayed), imageGallery(listOf(DocumentImage("https://example.test/small.png")), displayed).images)
    }
}
