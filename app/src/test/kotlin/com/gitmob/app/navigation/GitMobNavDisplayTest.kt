package com.gitmob.app.navigation

import android.view.KeyEvent
import android.view.View
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.gitmob.app.R
import com.gitmob.app.ui.settings.SelectionRow
import com.gitmob.app.ui.settings.SettingsDropdownRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GitMobNavDisplayTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val input = BackInput()
    private val enabled = mutableStateOf(false)
    private val interceptPageBack = mutableStateOf(false)
    private val models = mutableMapOf<Route, PageViewModel>()
    private val pageCoordinates = mutableMapOf<Route, LayoutCoordinates>()
    private val testPageScale = mutableStateOf(1f)
    private lateinit var navState: GitMobNavState
    private lateinit var navigator: GitMobNavigator
    private lateinit var activity: ComponentActivity
    private var popupRoot: View? = null
    private var navigationCount = 0
    private var confirmationCount = 0
    private var fallbackCount = 0

    @Test
    fun `bounds measurement detects graphics layer scaling`() {
        setNavigationContent()
        val original = bounds(AboutRoute)
        composeRule.runOnIdle { testPageScale.value = 0.8f }
        val scaled = bounds(AboutRoute)
        assertEquals(original.width * 0.8f, scaled.width, 1f)
        assertEquals(original.height * 0.8f, scaled.height, 1f)
    }

    @Test
    fun `disabled drag and reverse never preview or move the page`() {
        setNavigationContent()
        val original = bounds(AboutRoute)

        startGesture()
        progress(0.7f)
        assertBounds(original, bounds(AboutRoute))
        composeRule.onNodeWithTag(pageTag(HomeRoute)).assertDoesNotExist()
        progress(0.2f)
        assertBounds(original, bounds(AboutRoute))
        composeRule.onNodeWithTag(pageTag(HomeRoute)).assertDoesNotExist()
        assertEquals(0, navigationCount)
        cancelGesture()
        assertEquals(listOf(HomeRoute, AboutRoute), homeStack().toList())
        assertBounds(original, bounds(AboutRoute))
    }

    @Test
    fun `disabled completion pops once and still plays ordinary fade`() {
        setNavigationContent()
        val original = bounds(AboutRoute)
        startGesture()
        progress(0.6f)
        composeRule.mainClock.autoAdvance = false

        completeGesture()
        assertEquals(1, navigationCount)
        advanceIntoOrdinaryPop()
        composeRule.onNodeWithTag(pageTag(HomeRoute)).assertExists()
        // Outgoing content is retained during the fade, without predictive scaling or translation.
        assertBounds(original, bounds(AboutRoute))
        assertEquals(1, navigationCount)
        assertEquals(listOf(HomeRoute), homeStack().toList())
        composeRule.mainClock.advanceTimeBy(1000)
        composeRule.onNodeWithTag(pageTag(AboutRoute)).assertDoesNotExist()
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun `enabled preview uses horizontal parallax without scaling`() {
        setNavigationContent(predictive = true)
        val original = bounds(AboutRoute)

        startGesture()
        progress(0.6f)

        assertHorizontalPreview(original, bounds(AboutRoute))
        assertEquals(original.left + original.width * 0.6f, bounds(AboutRoute).left, 1f)
        assertEquals(original.left - original.width * 0.4f / 4f, bounds(HomeRoute).left, 1f)
        composeRule.onNodeWithTag(pageTag(HomeRoute)).assertExists()
        assertEquals(0, navigationCount)
        cancelGesture()
    }

    @Test
    fun `enabled reverse and cancellation restore the page without popping`() {
        setNavigationContent(predictive = true)
        val original = bounds(AboutRoute)
        startGesture()
        progress(0.65f)
        val furtherRight = bounds(AboutRoute)
        progress(0.1f)
        assertHorizontalPreview(original, bounds(AboutRoute))
        assertTrue(bounds(AboutRoute).left < furtherRight.left)

        cancelGesture()

        assertBounds(original, bounds(AboutRoute))
        composeRule.onNodeWithTag(pageTag(HomeRoute)).assertDoesNotExist()
        assertEquals(0, navigationCount)
        assertEquals(listOf(HomeRoute, AboutRoute), homeStack().toList())
    }

    @Test
    fun `enabled completion commits exactly one pop`() {
        setNavigationContent(predictive = true)
        startGesture()
        progress(0.5f)

        completeGesture()

        assertEquals(1, navigationCount)
        assertEquals(listOf(HomeRoute), homeStack().toList())
        composeRule.onNodeWithTag(pageTag(HomeRoute)).assertIsDisplayed()
        composeRule.onNodeWithTag(pageTag(AboutRoute)).assertDoesNotExist()
    }

    @Test
    fun `right edge preview uses the same rightward exit without scaling`() {
        setNavigationContent(predictive = true)
        val original = bounds(AboutRoute)
        composeRule.runOnIdle { input.start(NavigationEvent.EDGE_RIGHT) }
        composeRule.runOnIdle { input.progress(0.6f, NavigationEvent.EDGE_RIGHT) }

        assertHorizontalPreview(original, bounds(AboutRoute))
        assertEquals(original.left + original.width * 0.6f, bounds(AboutRoute).left, 1f)
        cancelGesture()
        assertBounds(original, bounds(AboutRoute))
        assertEquals(0, navigationCount)
    }

    @Test
    fun `predictive commit continues its horizontal exit instead of switching to ordinary fade`() {
        setNavigationContent(predictive = true)
        val original = bounds(AboutRoute)
        startGesture()
        progress(0.4f)
        val preview = bounds(AboutRoute)
        composeRule.mainClock.autoAdvance = false

        completeGesture()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        val settledStart = bounds(AboutRoute)
        assertEquals(preview.left, settledStart.left, 1f)
        composeRule.mainClock.advanceTimeBy(64)
        composeRule.waitForIdle()
        assertHorizontalPreview(original, bounds(AboutRoute))
        assertTrue(bounds(AboutRoute).left > settledStart.left)
        assertEquals(1, navigationCount)

        composeRule.mainClock.advanceTimeBy(1000)
        composeRule.onNodeWithTag(pageTag(AboutRoute)).assertDoesNotExist()
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun `predictive cancel returns horizontally without scaling or popping`() {
        setNavigationContent(predictive = true)
        val original = bounds(AboutRoute)
        startGesture()
        progress(0.6f)
        val preview = bounds(AboutRoute)
        composeRule.mainClock.autoAdvance = false

        cancelGesture()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        assertEquals(preview.left, bounds(AboutRoute).left, 1f)
        composeRule.mainClock.advanceTimeBy(64)
        composeRule.waitForIdle()
        assertHorizontalPreview(original, bounds(AboutRoute))
        assertTrue(bounds(AboutRoute).left < preview.left)
        assertEquals(0, navigationCount)

        composeRule.mainClock.advanceTimeBy(1000)
        assertBounds(original, bounds(AboutRoute))
        composeRule.onNodeWithTag(pageTag(HomeRoute)).assertDoesNotExist()
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun `toolbar back remains ordinary with predictive setting off`() {
        assertToolbarBackIsOrdinary(predictive = false)
    }

    @Test
    fun `toolbar back remains ordinary with predictive setting on`() {
        assertToolbarBackIsOrdinary(predictive = true)
    }

    @Test
    fun `discrete system back remains ordinary with predictive setting off`() {
        assertDiscreteBackIsOrdinary(predictive = false)
    }

    @Test
    fun `discrete system back remains ordinary with predictive setting on`() {
        assertDiscreteBackIsOrdinary(predictive = true)
    }

    @Test
    fun `activity gesture bridge does not preview when disabled`() {
        assertActivityGesturePreview(predictive = false)
    }

    @Test
    fun `activity gesture bridge uses horizontal preview when enabled`() {
        assertActivityGesturePreview(predictive = true)
    }

    @Test
    fun `enabling during disabled gesture only affects the next gesture`() {
        setNavigationContent()
        val original = bounds(AboutRoute)
        startGesture()
        progress(0.4f)
        composeRule.runOnIdle { enabled.value = true }
        progress(0.7f)
        assertBounds(original, bounds(AboutRoute))
        composeRule.onNodeWithTag(pageTag(HomeRoute)).assertDoesNotExist()

        cancelGesture()
        startGesture()
        progress(0.5f)
        assertHorizontalPreview(original, bounds(AboutRoute))
        cancelGesture()
    }

    @Test
    fun `disabling during enabled gesture keeps its preview until completion`() {
        setNavigationContent(predictive = true)
        val original = bounds(AboutRoute)
        startGesture()
        progress(0.3f)
        composeRule.runOnIdle { enabled.value = false }
        progress(0.6f)
        assertHorizontalPreview(original, bounds(AboutRoute))

        completeGesture()

        assertEquals(1, navigationCount)
        assertEquals(listOf(HomeRoute), homeStack().toList())
    }

    @Test
    fun `disabling during enabled gesture takes effect after cancellation`() {
        setNavigationContent(predictive = true)
        val original = bounds(AboutRoute)
        startGesture()
        progress(0.4f)
        composeRule.runOnIdle { enabled.value = false }
        progress(0.6f)
        assertHorizontalPreview(original, bounds(AboutRoute))

        cancelGesture()
        startGesture()
        progress(0.6f)

        assertBounds(original, bounds(AboutRoute))
        composeRule.onNodeWithTag(pageTag(HomeRoute)).assertDoesNotExist()
        cancelGesture()
        assertEquals(0, navigationCount)
    }

    @Test
    fun `cancel and restart in one frame picks up the new setting`() {
        setNavigationContent()
        val original = bounds(AboutRoute)
        startGesture()
        progress(0.4f)
        composeRule.runOnIdle { enabled.value = true }

        composeRule.runOnIdle {
            input.cancel()
            input.start()
            input.progress(0.6f)
        }

        assertHorizontalPreview(original, bounds(AboutRoute))
        assertEquals(0, navigationCount)
        cancelGesture()
    }

    @Test
    fun `setting change and gesture start in one frame do not switch modes mid gesture`() {
        setNavigationContent()
        val original = bounds(AboutRoute)
        composeRule.runOnIdle {
            input.start()
            enabled.value = true
            input.progress(0.6f)
        }

        assertBounds(original, bounds(AboutRoute))
        composeRule.onNodeWithTag(pageTag(HomeRoute)).assertDoesNotExist()
        cancelGesture()
        startGesture()
        progress(0.6f)
        assertHorizontalPreview(original, bounds(AboutRoute))
        cancelGesture()
    }

    @Test
    fun `consecutive completed gestures pop distinct entries without duplicate callbacks`() {
        setNavigationContent(homeRoutes = listOf(HomeRoute, AboutRoute, AppearanceRoute))
        startGesture()
        progress(0.5f)
        completeGesture()
        assertEquals(listOf(HomeRoute, AboutRoute), homeStack().toList())
        startGesture()
        progress(0.5f)
        completeGesture()
        assertEquals(listOf(HomeRoute), homeStack().toList())
        assertEquals(2, navigationCount)
    }

    @Test
    fun `page confirmation handler takes priority over navigation preview`() {
        setNavigationContent(predictive = true)
        interceptPageBack.value = true
        composeRule.waitForIdle()
        val original = bounds(AboutRoute)
        startGesture()
        progress(0.6f)
        assertBounds(original, bounds(AboutRoute))
        composeRule.onNodeWithTag(pageTag(HomeRoute)).assertDoesNotExist()

        completeGesture()

        assertEquals(1, confirmationCount)
        assertEquals(0, navigationCount)
        assertEquals(listOf(HomeRoute, AboutRoute), homeStack().toList())
    }

    @Test
    fun `actual settings popup dismisses before page navigation`() {
        setNavigationContent(predictive = true)
        composeRule.onNodeWithText("Selection").performClick()

        composeRule.runOnIdle {
            val root = requireNotNull(popupRoot)
            root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }

        composeRule.onNodeWithText("Option").assertDoesNotExist()
        composeRule.onNodeWithTag(pageTag(AboutRoute)).assertIsDisplayed()
        assertEquals(0, navigationCount)
    }

    @Test
    fun `home root falls through instead of intercepting system exit`() {
        setNavigationContent(predictive = true, homeRoutes = listOf(HomeRoute))
        startGesture()
        progress(0.5f)
        completeGesture()

        assertEquals(1, fallbackCount)
        assertEquals(0, navigationCount)
        assertEquals(listOf(HomeRoute), homeStack().toList())
    }

    @Test
    fun `disabled home root also leaves system exit handling alone`() {
        setNavigationContent(homeRoutes = listOf(HomeRoute))
        composeRule.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }

        assertEquals(1, fallbackCount)
        assertEquals(0, navigationCount)
        assertEquals(listOf(HomeRoute), homeStack().toList())
    }

    @Test
    fun `non home root returns to home and preserves tab histories`() {
        setNavigationContent(
            predictive = true,
            selectedTab = SettingsRoute,
            homeRoutes = listOf(HomeRoute, AboutRoute),
        )
        startGesture()
        progress(0.4f)
        cancelGesture()
        assertEquals(SettingsRoute, navState.topLevelRoute)
        startGesture()
        progress(0.4f)
        completeGesture()

        assertEquals(HomeRoute, navState.topLevelRoute)
        assertEquals(listOf(HomeRoute, AboutRoute), homeStack().toList())
        assertEquals(listOf(SettingsRoute), navState.backStacks.getValue(SettingsRoute).toList())
        assertEquals(1, navigationCount)
        composeRule.onNodeWithTag(pageTag(AboutRoute)).assertIsDisplayed()
    }

    @Test
    fun `tab switching retains saveable state and per entry view models`() {
        setNavigationContent(predictive = true)
        composeRule.onNodeWithTag("increment").performClick()
        val aboutModel = models.getValue(AboutRoute)

        composeRule.runOnIdle { navigator.navigate(SettingsRoute) }
        composeRule.onNodeWithTag(pageTag(SettingsRoute)).assertIsDisplayed()
        val settingsModel = models.getValue(SettingsRoute)
        assertNotSame(aboutModel, settingsModel)
        composeRule.runOnIdle { navigator.navigate(HomeRoute) }

        composeRule.onNodeWithText("count_1").assertExists()
        assertSame(aboutModel, models.getValue(AboutRoute))
        assertEquals(listOf(HomeRoute, AboutRoute), homeStack().toList())
    }

    @Test
    fun `toggling setting does not recreate current page or view model`() {
        setNavigationContent()
        composeRule.onNodeWithTag("increment").performClick()
        val model = models.getValue(AboutRoute)

        composeRule.runOnIdle { enabled.value = true }
        composeRule.runOnIdle { enabled.value = false }

        composeRule.onNodeWithText("count_1").assertExists()
        assertSame(model, models.getValue(AboutRoute))
        assertEquals(0, navigationCount)
    }

    private fun assertToolbarBackIsOrdinary(predictive: Boolean) {
        setNavigationContent(predictive = predictive)
        val original = bounds(AboutRoute)
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("toolbar_back").performClick()
        assertEquals(1, navigationCount)
        advanceIntoOrdinaryPop()
        assertBounds(original, bounds(AboutRoute))
        composeRule.onNodeWithTag(pageTag(HomeRoute)).assertExists()
        assertEquals(1, navigationCount)
        composeRule.mainClock.advanceTimeBy(1000)
        composeRule.onNodeWithTag(pageTag(AboutRoute)).assertDoesNotExist()
        composeRule.mainClock.autoAdvance = true
    }

    private fun assertDiscreteBackIsOrdinary(predictive: Boolean) {
        setNavigationContent(predictive = predictive)
        val original = bounds(AboutRoute)
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, navigationCount)
        advanceIntoOrdinaryPop()
        assertBounds(original, bounds(AboutRoute))
        composeRule.onNodeWithTag(pageTag(HomeRoute)).assertExists()
        assertEquals(1, navigationCount)
        composeRule.mainClock.advanceTimeBy(1000)
        composeRule.onNodeWithTag(pageTag(AboutRoute)).assertDoesNotExist()
        composeRule.mainClock.autoAdvance = true
    }

    private fun advanceIntoOrdinaryPop() {
        // runOnIdle waits before dispatch, not after. Deliver the stack snapshot and the launched
        // transition before advancing its clock; otherwise time advances before the pop starts.
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(96)
        composeRule.waitForIdle()
    }

    private fun assertActivityGesturePreview(predictive: Boolean) {
        setNavigationContent(predictive = predictive)
        val original = bounds(AboutRoute)
        composeRule.runOnIdle {
            activity.onBackPressedDispatcher.dispatchOnBackStarted(
                BackEventCompat(0f, 200f, 0f, BackEventCompat.EDGE_LEFT),
            )
        }
        composeRule.runOnIdle {
            activity.onBackPressedDispatcher.dispatchOnBackProgressed(
                BackEventCompat(120f, 200f, 0.6f, BackEventCompat.EDGE_LEFT),
            )
        }
        if (predictive) {
            assertHorizontalPreview(original, bounds(AboutRoute))
            composeRule.onNodeWithTag(pageTag(HomeRoute)).assertExists()
        } else {
            assertBounds(original, bounds(AboutRoute))
            composeRule.onNodeWithTag(pageTag(HomeRoute)).assertDoesNotExist()
        }
        composeRule.runOnIdle { activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        assertBounds(original, bounds(AboutRoute))
        assertEquals(0, navigationCount)
    }

    private fun setNavigationContent(
        predictive: Boolean = false,
        selectedTab: Route = HomeRoute,
        homeRoutes: List<Route> = listOf(HomeRoute, AboutRoute),
    ) {
        enabled.value = predictive
        navState = GitMobNavState(
            startRoute = HomeRoute,
            topLevelRoute = mutableStateOf(selectedTab),
            backStacks = listOf(HomeRoute, ReposRoute, StarsRoute, GistRoute, SettingsRoute)
                .associateWith { route ->
                    NavBackStack<NavKey>().apply {
                        addAll(if (route == HomeRoute) homeRoutes else listOf(route))
                    }
                },
        )
        navigator = GitMobNavigator(navState)
        composeRule.setContent {
            MaterialTheme {
                val owner = requireNotNull(LocalNavigationEventDispatcherOwner.current)
                val currentActivity = LocalActivity.current as ComponentActivity
                SideEffect { activity = currentActivity }
                DisposableEffect(owner) {
                    owner.navigationEventDispatcher.addInput(input)
                    onDispose { owner.navigationEventDispatcher.removeInput(input) }
                }
                // A lower-priority stand-in for system exit: the app container must leave it alone
                // at Home root. No Activity is finished by these JVM tests.
                val fallback = rememberNavigationEventState(NavigationEventInfo.None)
                NavigationBackHandler(state = fallback, onBackCompleted = { fallbackCount++ })
                val provider = remember {
                    { key: NavKey -> NavEntry(key) { Page(key as Route) } }
                }
                GitMobNavDisplay(
                    entries = navState.toDecoratedEntries(provider),
                    enablePredictiveBack = enabled.value,
                    onBack = ::navigateBack,
                    transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(260)) },
                    popTransitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(260)) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Composable
    private fun Page(route: Route) {
        val pageModel: PageViewModel = viewModel()
        var count by rememberSaveable { mutableIntStateOf(0) }
        SideEffect { models[route] = pageModel }
        BackHandler(enabled = interceptPageBack.value) { confirmationCount++ }
        Box(
            Modifier.fillMaxSize().testTag(pageTag(route))
                .graphicsLayer {
                    scaleX = testPageScale.value
                    scaleY = testPageScale.value
                }
                .onGloballyPositioned { pageCoordinates[route] = it }
                .background(if (route == HomeRoute) Color.Blue else Color.Red),
        ) {
            Column {
                Button(onClick = ::navigateBack, modifier = Modifier.testTag("toolbar_back")) {
                    Text(stringResource(R.string.common_back))
                }
                Button(onClick = { count++ }, modifier = Modifier.testTag("increment")) {
                    Text("count_$count", Modifier.testTag("count_$count"))
                }
                SettingsDropdownRow(title = "Selection", currentValue = "Current") { dismiss ->
                    val root = LocalView.current.rootView
                    SideEffect { popupRoot = root }
                    SelectionRow("Option", "Description", selected = true, onClick = dismiss)
                }
            }
        }
    }

    private fun navigateBack() {
        navigationCount++
        navigator.goBack()
    }

    private fun homeStack() = navState.backStacks.getValue(HomeRoute)
    private fun pageTag(route: Route) = "page_${route::class.simpleName}"
    private fun bounds(route: Route): Rect {
        composeRule.onNodeWithTag(pageTag(route)).assertExists()
        // Map all four corners: clipped bounds mistake translation for shrinking, while
        // getUnclippedBoundsInRoot combines transformed position with untransformed size.
        // localToRoot includes ancestor and page graphics-layer transforms, without clipping.
        val coordinates = pageCoordinates.getValue(route)
        val width = coordinates.size.width.toFloat()
        val height = coordinates.size.height.toFloat()
        val corners = listOf(
            Offset.Zero, Offset(width, 0f), Offset(0f, height), Offset(width, height),
        ).map(coordinates::localToRoot)
        return Rect(
            corners.minOf { it.x }, corners.minOf { it.y },
            corners.maxOf { it.x }, corners.maxOf { it.y },
        )
    }
    private fun startGesture() = composeRule.runOnIdle { input.start() }
    private fun progress(value: Float) = composeRule.runOnIdle { input.progress(value) }
    private fun cancelGesture() = composeRule.runOnIdle { input.cancel() }
    private fun completeGesture() = composeRule.runOnIdle { input.complete() }

    private fun assertBounds(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 1f)
        assertEquals(expected.top, actual.top, 1f)
        assertEquals(expected.right, actual.right, 1f)
        assertEquals(expected.bottom, actual.bottom, 1f)
    }

    private fun assertHorizontalPreview(original: Rect, actual: Rect) {
        assertEquals(original.width, actual.width, 1f)
        assertEquals(original.height, actual.height, 1f)
        assertEquals(original.top, actual.top, 1f)
        assertTrue("Expected rightward predictive translation: $original -> $actual", actual.left > original.left)
    }

    class PageViewModel : ViewModel()

    private class BackInput : NavigationEventInput() {
        fun start(edge: Int = NavigationEvent.EDGE_LEFT) = dispatchOnBackStarted(event(0f, edge))
        fun progress(value: Float, edge: Int = NavigationEvent.EDGE_LEFT) =
            dispatchOnBackProgressed(event(value, edge))
        fun cancel() = dispatchOnBackCancelled()
        fun complete() = dispatchOnBackCompleted()
        private fun event(progress: Float, edge: Int = NavigationEvent.EDGE_LEFT) = NavigationEvent(
            swipeEdge = edge,
            progress = progress,
            touchX = 200f * progress,
            touchY = 200f,
        )
    }
}
