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
}
