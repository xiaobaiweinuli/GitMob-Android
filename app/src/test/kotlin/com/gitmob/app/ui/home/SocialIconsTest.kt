package com.gitmob.app.ui.home

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SocialIconsTest {
    @Test
    fun `known provider uses brand icon`() {
        assertNotNull(socialProviderIconRes("TWITTER"))
    }

    @Test
    fun `generic provider falls back to Material link icon`() {
        assertNull(socialProviderIconRes("GENERIC"))
        assertNull(socialProviderIconRes("UNKNOWN"))
    }
}
