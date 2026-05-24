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
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import io.github.rosemoe.sora.widget.schemes.SchemeGitHub

/**
 * 基于 Sora Editor 的代码查看/编辑器 Compose 封装。
 *
 * 统一用于查看器（readOnly=true）和编辑器（readOnly=false），保证两者行号、
 * 字体大小、滚动条、主题配色完全一致。
 *
 * 性能优势（对比 BasicTextField）：
 * - 内部仅渲染可见行（虚拟化），RenderNode 缓存，大文件（几百 KB）无卡顿
 * - readOnly 模式保留流畅的原生文字选择/复制，彻底解决 SelectionContainer 卡顿问题
 * - 不在每次击键时触发 Compose 重组，内容只在 onEditorReady 拿到引用后按需读取
 *
 * @param initialContent   编辑器初始内容，只在首次创建时生效
 * @param isDarkTheme      是否深色主题，跟随 LocalGmColors.current.isDark
 * @param readOnly         true=只读查看模式，false=可编辑模式（默认）
 * @param modifier         建议查看器用 fillMaxSize()，编辑器用 fillMaxSize().padding(paddingValues)
 * @param onEditorReady    编辑器实例创建完成回调，调用方保存引用以在保存时读取内容
 * @param onContentChanged 任意内容变更时触发（readOnly=true 时不触发）
 */
@Composable
fun CodeEditorView(
    initialContent: String,
    isDarkTheme: Boolean,
    readOnly: Boolean = false,
    modifier: Modifier = Modifier,
    onEditorReady: (CodeEditor) -> Unit = {},
    onContentChanged: () -> Unit = {}
) {
    val context = LocalContext.current

    val editor = remember {
        CodeEditor(context).apply {
            typefaceText = Typeface.MONOSPACE
            isWordwrap = true
            setTextSize(12f)
            // 初始主题同步设置，避免 LaunchedEffect 延迟导致首帧白色闪烁
            colorScheme = buildGmColorScheme(isDarkTheme)
            // 只读模式：禁止键盘弹出和文字输入，保留完整选择/复制/滚动能力
            if (readOnly) {
                isEditable = false
            }
            // 内容同步设置，avoid LaunchedEffect 的首帧空白
            setText(initialContent)
        }
    }

    // 通知调用方编辑器就绪，只执行一次
    LaunchedEffect(Unit) {
        onEditorReady(editor)
    }

    // 主题跟随系统切换
    LaunchedEffect(isDarkTheme) {
        editor.colorScheme = buildGmColorScheme(isDarkTheme)
    }

    // 内容变化监听 + 生命周期释放
    DisposableEffect(editor) {
        val subscription = editor.subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
            if (!readOnly) onContentChanged()
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

/**
 * 构建与 GmColors 配色完全对齐的 EditorColorScheme。
 *
 * 基础配色取自 SchemeDarcula（深色）/ SchemeGitHub（浅色），
 * 覆盖背景和行号区配色以匹配 GmColors 的 bgDeep / bgCard / textSecondary / border。
 *
 * 颜色值与 GmColors.kt 中的定义保持同步：
 *   dark:  bgDeep=#0F1117  bgCard=#161B25  textSecondary=#9BA3BA  border=#2A3347
 *   light: bgDeep=#F4F6FC  bgCard=#FFFFFF  textSecondary=#5C6475  border=#DDE1EC
 */
private fun buildGmColorScheme(isDark: Boolean): EditorColorScheme {
    val scheme = if (isDark) SchemeDarcula() else SchemeGitHub()

    // 编辑器主体背景 → bgDeep（与 Scaffold containerColor 对齐，无缝融合）
    val bgDeep = android.graphics.Color.parseColor(if (isDark) "#0F1117" else "#F4F6FC")
    // 行号区背景 → bgCard（比 bgDeep 略亮，形成自然分隔层次）
    val bgCard = android.graphics.Color.parseColor(if (isDark) "#161B25" else "#FFFFFF")
    // 行号文字 → textSecondary（柔和，不抢主文字的视觉重量）
    val gutterText = android.graphics.Color.parseColor(if (isDark) "#9BA3BA" else "#5C6475")
    // 行号区与内容区分隔线 → border
    val border = android.graphics.Color.parseColor(if (isDark) "#2A3347" else "#DDE1EC")

    scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, bgDeep)
    scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, bgCard)
    scheme.setColor(EditorColorScheme.LINE_NUMBER, gutterText)
    scheme.setColor(EditorColorScheme.LINE_DIVIDER, border)

    return scheme
}
