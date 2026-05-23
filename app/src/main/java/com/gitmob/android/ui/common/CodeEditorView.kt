package com.gitmob.android.ui.common

import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import io.github.rosemoe.sora.widget.schemes.SchemeGitHub

/**
 * 基于 Sora Editor 的代码编辑器 Compose 封装。
 *
 * 与原有 BasicTextField 方案的核心差异：
 * - Sora Editor 内部使用可见行虚拟化 + RenderNode 缓存，只渲染当前视口内的行
 * - 不在每次击键时触发 Compose 重组，彻底规避大文件（几百 KB）的卡顿问题
 * - 内容仅在调用方需要时通过 [onEditorReady] 拿到 CodeEditor 引用后主动读取
 *
 * @param initialContent  编辑器初始内容，只在首次创建时生效
 * @param isDarkTheme     是否深色主题，跟随 LocalGmColors.current.isDark
 * @param bgColorInt      背景色 Int（调用方传入 c.bgDeep.toArgb()）
 * @param textColorInt    文本色 Int（调用方传入 c.textPrimary.toArgb()）
 * @param modifier        Compose 布局修饰符，建议使用 Modifier.weight(1f) 或 fillMaxSize
 * @param onEditorReady   编辑器实例创建完成后的回调，调用方保存引用以便在保存时读取内容
 * @param onContentChanged 任意内容变更时触发，调用方可用此回调更新 hasChanges 状态
 */
@Composable
fun CodeEditorView(
    initialContent: String,
    isDarkTheme: Boolean,
    bgColorInt: Int,
    textColorInt: Int,
    modifier: Modifier = Modifier,
    onEditorReady: (CodeEditor) -> Unit = {},
    onContentChanged: () -> Unit = {}
) {
    val context = LocalContext.current

    // editor 实例生命周期与 Composable 一致，remember 保证不会因重组重建
    // 注意：key 不包含 isDarkTheme、bgColorInt 等，避免主题变化时重建编辑器丢失内容
    val editor = remember(initialContent) {
        CodeEditor(context).apply {
            typefaceText = Typeface.MONOSPACE
            // 自动换行
            isWordwrap = true
            // 设置初始内容，在 remember 内同步执行，避免 LaunchedEffect 的首帧空白
            setText(initialContent)
            // 设置字体大小为 12sp，对齐查看器
            setTextSize(12f)
        }
    }
    
    // 首帧立即设置主题和颜色，在 LaunchedEffect(Unit) 中执行，保证编辑器已创建
    LaunchedEffect(Unit) {
        editor.colorScheme = (if (isDarkTheme) SchemeDarcula() else SchemeGitHub()).apply {
            // 覆盖背景色，让编辑器与 Scaffold 完全融合
            setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.WHOLE_BACKGROUND, bgColorInt)
            setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.LINE_NUMBER_BACKGROUND, bgColorInt)
        }
    }

    // 通知调用方编辑器已就绪，只执行一次
    LaunchedEffect(Unit) {
        onEditorReady(editor)
    }

    // 主题切换：isDarkTheme 变化时重新设置 colorScheme
    // SchemeGitHub / SchemeDarcula 均为 sora-editor 核心模块内置，无需额外资源文件
    LaunchedEffect(isDarkTheme, bgColorInt, textColorInt) {
        editor.colorScheme = (if (isDarkTheme) SchemeDarcula() else SchemeGitHub()).apply {
            // 覆盖背景色，让编辑器与 Scaffold 完全融合
            setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.WHOLE_BACKGROUND, bgColorInt)
            setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.LINE_NUMBER_BACKGROUND, bgColorInt)
        }
    }

    // 内容变化监听 + 生命周期释放
    DisposableEffect(editor) {
        val subscription = editor.subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
            onContentChanged()
        }
        onDispose {
            subscription.unsubscribe()
            editor.release()
        }
    }

    AndroidView(
        factory = { editor },
        modifier = modifier
    )
}
