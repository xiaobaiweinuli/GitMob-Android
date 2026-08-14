package com.gitmob.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.serialization.NavKeySerializer
import androidx.savedstate.compose.serialization.serializers.MutableStateSerializer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 底部 Tab 的顶层路由集合（5 个）。
 * 每个 Tab 维护一个独立的 NavBackStack，push 路由（Inbox/RepoDetail/Profile 等）
 * 只进当前激活 Tab 的栈，不污染其他 Tab 的浏览历史。
 */
private val topLevelRoutes: Set<Route> = setOf(
    HomeRoute,
    ReposRoute,
    StarsRoute,
    GistRoute,
    SettingsRoute,
)

/**
 * 创建并记住一个 GitMobNavState。
 *
 * 对齐 android-skills navigation-3 multiple-backstacks recipe：
 *   - exit through home 模式：返回键始终从 HomeRoute 栈退出 App
 *   - 每个 top level route 独立 NavBackStack + 独立 SaveableStateHolder + 独立 ViewModelStore
 *   - 状态通过 rememberSerializable + MutableStateSerializer 持久化，支持进程重建恢复
 *
 * @param startRoute 初始/首页 Tab（必须是 topLevelRoutes 之一），默认 HomeRoute
 */
@Composable
fun rememberGitMobNavState(
    startRoute: Route = HomeRoute,
): GitMobNavState {
    val topLevelRoute = rememberSerializable(
        startRoute,
        topLevelRoutes,
        serializer = MutableStateSerializer(NavKeySerializer()),
    ) { mutableStateOf(startRoute) }

    // 每个顶层路由一个独立的 NavBackStack，各自保存自己的 push 浏览历史
    val backStacks = topLevelRoutes.associateWith { key ->
        rememberNavBackStack(key)
    }

    return remember(startRoute) {
        GitMobNavState(
            startRoute = startRoute,
            topLevelRoute = topLevelRoute,
            backStacks = backStacks,
        )
    }
}

/**
 * 多栈导航状态容器（本身纯状态持有，不修改自身状态）。
 * 所有导航动作通过 [GitMobNavigator] 执行。
 *
 * @param startRoute   返回键退出 App 时的最终栈（exit through home 模式），通常是 HomeRoute
 * @param topLevelRoute 当前激活的顶层 Tab 的 MutableState 引用（用于 rememberSerializable 持久化）
 * @param backStacks   每个顶层路由对应一个独立 NavBackStack，key = Route，value = 该 Tab 的导航栈
 */
class GitMobNavState(
    val startRoute: Route,
    topLevelRoute: MutableState<Route>,
    val backStacks: Map<Route, NavBackStack<NavKey>>,
) {
    /** 当前激活的顶层 Tab */
    var topLevelRoute: Route by topLevelRoute

    /**
     * 把多栈状态扁平化成 NavDisplay 可渲染的 NavEntry 列表。
     *
     * exit through home 模式：
     *   - 当前 Tab = startRoute → 只渲染 startRoute 栈
     *   - 当前 Tab ≠ startRoute → 同时渲染 startRoute 栈 + 当前 Tab 栈
     *   这样 startRoute 的 NavEntry 始终在组合里，ViewModel/状态不会因切到其他 Tab 而销毁；
     *   当前 Tab 之外的非 startRoute Tab 的 entries 不渲染，但它们各自 backStacks 中的
     *   SaveableStateHolder 仍保留 rememberSaveable 内容，切回时能恢复。
     *
     * @param entryProvider NavDisplay 共用的 entryProvider（路由 → Composable）
     * @return 扁平化后的装饰 NavEntry 列表，直接传给 NavDisplay(entries = ...)
     */
    @Composable
    fun toDecoratedEntries(
        entryProvider: (NavKey) -> NavEntry<NavKey>,
    ): List<NavEntry<NavKey>> {
        val decoratedEntries = backStacks.mapValues { (_, stack) ->
            // 每个栈独立的 decorators：SaveableState + ViewModelStore，
            // 保证切 Tab 后 ViewModel 不共享、滚动位置不串台。
            val decorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
                rememberViewModelStoreNavEntryDecorator<NavKey>(),
            )
            rememberDecoratedNavEntries(
                backStack = stack,
                entryDecorators = decorators,
                entryProvider = entryProvider,
            )
        }

        return getActiveTopLevelRoutes()
            .flatMap { route -> decoratedEntries[route] ?: emptyList() }
    }

    /**
     * exit through home 模式：当前 Tab 是首页时只保留首页栈；
     * 否则保留首页栈（后台）+ 当前 Tab 栈（前台）。
     * 其他 Tab 的 entries 虽然不返回给 NavDisplay，但它们的
     * SaveableStateHolder 仍然持有 saved state，再次切回时会恢复。
     */
    private fun getActiveTopLevelRoutes(): List<Route> =
        if (topLevelRoute == startRoute) listOf(startRoute)
        else listOf(startRoute, topLevelRoute)
}

/**
 * 导航事件处理器：负责修改 [GitMobNavState]。
 *
 * 规则：
 *   - 顶层路由（5 个 Tab） → 切换 topLevelRoute（不操作各栈内容）
 *   - 非顶层路由（push 路由：Inbox/RepoDetail 等） → 入当前激活 Tab 的栈
 *   - 返回键：当前栈 size > 1 出栈；size = 1 且 Tab ≠ 首页 → 切回首页；size = 1 且 Tab = 首页 → 交给系统 finish Activity
 */
class GitMobNavigator(
    val state: GitMobNavState,
) {
    /** Tab 再次点击（reselect）事件，可被 Screen 订阅用于回到列表顶部等交互。 */
    private val _reselectEvents = MutableSharedFlow<Route>(extraBufferCapacity = 1)
    val reselectEvents = _reselectEvents.asSharedFlow()

    /**
     * 导航入口：顶层 Tab 切换 或 push 路由入栈。
     *
     * @param route 目标路由：是 topLevelRoute 就切 Tab，否则 push 到当前栈
     */
    fun navigate(route: Route) {
        if (route in topLevelRoutes) {
            state.topLevelRoute = route
        } else {
            state.backStacks[state.topLevelRoute]?.add(route)
        }
    }

    /**
     * 用户再次点击当前已选中的 Tab 时触发，
     * 用于 HomeScreen 的列表滚到顶部等"重置当前页"交互。
     */
    fun onReselect(route: Route) {
        _reselectEvents.tryEmit(route)
    }

    /**
     * 返回键处理。
     *
     * @return `true` 表示本方法已处理返回事件；`false` 表示当前栈已空，交给系统 finish Activity
     */
    fun goBack(): Boolean {
        val currentStack = state.backStacks[state.topLevelRoute]
            ?: error("GitMobNavigator: Stack for ${state.topLevelRoute} not found")

        val currentRoute = currentStack.lastOrNull()
            ?: return false  // 栈理论上不会空（每个栈至少有它的根 Tab）

        // 当前栈只剩下根 Tab（size == 1）
        if (currentRoute == state.topLevelRoute) {
            // 在首页 Tab 上按返回 → 交给系统 finish
            if (state.topLevelRoute == state.startRoute) return false
            // 在非首页 Tab 上按返回 → 切回首页 Tab（退出 through home）
            state.topLevelRoute = state.startRoute
            return true
        }

        // 当前栈还有 push 路由 → 正常出栈
        currentStack.removeLastOrNull()
        return true
    }
}
