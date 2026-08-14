package com.gitmob.app.core.event

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 纯 JVM 测试（./gradlew testDebugUnitTest 直接跑，不需要模拟器/真机，也不需要打包 APK）。
 * 见 SKILL.md 第七节「测试规范」：core/event 层的事件总线纯函数类单测，
 * 标准范式：JUnit4 + kotlinx-coroutines-test + Turbine 测 Flow。
 */
class RepoUpdateEventBusTest {

    @Test
    fun `emit的事件能被订阅者收到`() = runTest {
        val bus = RepoUpdateEventBus()
        val event = RepoUpdateEvent.BranchSwitched("owner", "repo", "develop")

        bus.events.test {
            bus.emit(event)
            assertEquals(event, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `不同事件类型的owner和name字段都能正确读取`() {
        val star = RepoUpdateEvent.StarChanged("a", "b", isStarred = true, stargazerCount = 5)
        assertEquals("a", star.owner)
        assertEquals("b", star.name)
        assertEquals(5, star.stargazerCount)
    }
}
