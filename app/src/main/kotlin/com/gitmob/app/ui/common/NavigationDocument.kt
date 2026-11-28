package com.gitmob.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavEntryDecorator
import com.gitmob.app.data.model.RepoDocumentContext
import com.gitmob.app.data.translation.TranslationDocument

/** UI resources live only in this Activity's navigation entries, never in a ViewModel. */
private val LocalDocumentOwner = staticCompositionLocalOf<NavigationDocumentOwner?> { null }

@Composable
internal fun <T : Any> rememberDocumentNavEntryDecorator(): NavEntryDecorator<T> {
    val owners = remember { mutableMapOf<Any, NavigationDocumentOwner>() }
    DisposableEffect(owners) {
        onDispose {
            owners.values.forEach { it.close() }
            owners.clear()
        }
    }
    return remember(owners) {
        NavEntryDecorator<T>(onPop = { owners.remove(it)?.close() }) { entry ->
            val owner = owners.getOrPut(entry.contentKey) { NavigationDocumentOwner() }
            CompositionLocalProvider(LocalDocumentOwner provides owner) { entry.Content() }
        }
    }
}

@Composable
internal fun rememberNavigationDocument(
    bodyHtml: String,
    document: RepoDocumentContext?,
    revision: Long,
    createResources: (RepoDocumentContext) -> RepoDocumentResources,
): NavigationDocument {
    val navigationOwner = LocalDocumentOwner.current
    val owner = navigationOwner ?: remember { NavigationDocumentOwner() }
    // Standalone screen hosts still release their resources when their composition ends.
    DisposableEffect(owner) { onDispose { if (navigationOwner == null) owner.close() } }
    return remember(owner, bodyHtml, document, revision) {
        owner.document(DocumentIdentity(bodyHtml, document, revision), createResources)
    }
}

private data class DocumentIdentity(val html: String, val context: RepoDocumentContext?, val revision: Long)

private class NavigationDocumentOwner {
    private var identity: DocumentIdentity? = null
    private var document: NavigationDocument? = null

    fun document(key: DocumentIdentity, createResources: (RepoDocumentContext) -> RepoDocumentResources): NavigationDocument {
        if (identity != key) {
            close()
            identity = key
            document = NavigationDocument(key.context?.let(createResources))
        }
        return checkNotNull(document)
    }

    fun close() {
        document?.close()
        document = null
        identity = null
    }
}

/** One repository document, including its resource requests and live renderer. */
class NavigationDocument internal constructor(val resources: RepoDocumentResources?) {
    private val renderers = mutableMapOf<MarkdownLayoutMode, MarkdownWebViewState>()
    internal var translationDocument: TranslationDocument? = null

    internal fun renderer(layoutMode: MarkdownLayoutMode = MarkdownLayoutMode.Embedded,
        rawResources: RawWebResourceLoader.Factory? = null): MarkdownWebViewState =
        renderers.getOrPut(layoutMode) { MarkdownWebViewState(resources, rawResources?.create()) }

    internal fun close() {
        renderers.values.forEach { it.close() }
        renderers.clear()
        translationDocument = null
        resources?.close()
    }
}
