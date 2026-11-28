package com.gitmob.app.ui.icons

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import com.gitmob.app.data.model.RepoEntryType
import org.junit.Assert.*
import org.junit.Test

class FileIconCatalogTest {
    @Test fun everyIconBuildsAndReusesItsVector() {
        assertTrue(GeneratedFileIcons.icons.isNotEmpty())
        for ((name, create) in GeneratedFileIcons.icons) {
            val vector = create()
            assertEquals(name, vector.name)
            assertSame(name, vector, create())
            assertTrue(name, vector.viewportWidth > 0f && vector.viewportHeight > 0f)
            assertTrue(name, vector.root.size > 0)
            assertGeometry(name, vector.root)
        }
    }

    @Test fun filenameWinsOverExtensionAndLanguage() {
        assertEquals("nodejs", resolve("project/package-lock.json", language = "ansible")?.name)
    }

    @Test fun compoundExtensionsAndCaseInsensitiveMatchingArePreserved() {
        assertEquals("typescript-def", resolve("src/types.d.ts")?.name)
        assertEquals("kotlin", resolve("src/Main.KT")?.name)
    }

    @Test fun repositoryEntryTypesUseTheirOwnIcons() {
        assertEquals("folder-git", resolve("lib.kt", RepoEntryType.SUBMODULE)?.name)
        assertEquals("folder-symlink", resolve("main.kt", RepoEntryType.SYMLINK)?.name)
        assertEquals("folder-src", resolve("project/src", RepoEntryType.DIRECTORY)?.name)
    }

    @Test fun languageFallbackAndUnknownEntriesArePreserved() {
        assertEquals("yaml", resolve("unknown.unknownextension", language = "ansible")?.name)
        assertNull(resolve("unknown.unknownextension"))
        assertNull(resolve("unknown-directory", RepoEntryType.DIRECTORY))
        assertNull(resolve("Main.kt", RepoEntryType.UNKNOWN))
    }

    @Test fun lightThemeSelectsTheVariantWithoutReplacingTheDarkVector() {
        val dark = resolve(".wakatime-project")
        assertEquals("wakatime", dark?.name)
        assertEquals("wakatime_light", resolve(".wakatime-project", light = true)?.name)
        assertSame(dark, resolve(".wakatime-project"))
    }

    @Test fun gradientColorsAndCoordinatesArePreserved() {
        val path = resolve("Main.kt")!!.root[0] as VectorPath
        assertEquals(
            Brush.linearGradient(
                0f to Color(0xFF7C4DFF), 0.5f to Color(0xFFD500F9), 1f to Color(0xFFEF5350),
                start = Offset(2.847f, 21.379f), end = Offset(21.123f, 2.899f),
            ),
            path.fill,
        )
    }

    @Test fun complexPathsTransparencyAndFillRulesArePreserved() {
        val mathematica = resolve("notebook.nb")!!
        assertEquals("mathematica", mathematica.name)
        assertTrue((mathematica.root[1] as VectorPath).pathData.size > 1_000)
        val webpack = GeneratedFileIcons.icons.getValue("webpack").invoke()
        assertTrue(webpack.root.any { it is VectorPath && it.fillAlpha == 0.785f })
        val advpl = GeneratedFileIcons.icons.getValue("advpl").invoke()
        assertTrue(advpl.root.any { it is VectorPath && it.pathFillType == PathFillType.EvenOdd })
    }

    @Test fun clippedPathsKeepTheirGroupAndClipGeometry() {
        for ((name, data) in mapOf(
            "concourse" to "M1.87,1.964l20.259,0l0,20.101L1.87,22.065z",
            "protractor" to "M2.342,3.856l78.463,-0L80.805,79.715L2.342,79.715z",
        )) {
            val vector = GeneratedFileIcons.icons.getValue(name).invoke()
            val outer = vector.root[0] as VectorGroup
            val clipped = outer[0] as VectorGroup
            assertEquals(name, PathParser().parsePathString(data).toNodes(), clipped.clipPathData)
            assertTrue(name, clipped.size > 0)
        }
    }

    @Test fun radialGradientIsPreserved() {
        val vector = GeneratedFileIcons.icons.getValue("vfl").invoke()
        val path = vector.root[1] as VectorPath
        assertEquals(
            Brush.radialGradient(
                0f to Color(0xFFFFD600), 0.35f to Color(0xFFF9A825), 1f to Color(0xFFF4511E),
                center = Offset(12.248f, 12.378f), radius = 10.267f,
            ),
            path.fill,
        )
    }

    private fun assertGeometry(name: String, group: VectorGroup) {
        for (node in group) {
            when (node) {
                is VectorGroup -> assertGeometry(name, node)
                is VectorPath -> assertTrue(name, node.pathData.isNotEmpty())
            }
        }
    }

    private fun resolve(
        path: String,
        type: RepoEntryType = RepoEntryType.FILE,
        language: String? = null,
        light: Boolean = false,
    ) = FileIconCatalog.resolve(path, type, language, light)
}
