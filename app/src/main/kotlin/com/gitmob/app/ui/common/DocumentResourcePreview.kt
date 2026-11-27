package com.gitmob.app.ui.common

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import coil3.ImageLoader
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.svg.SvgDecoder
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.markdown.baseUrl
import com.gitmob.app.core.markdown.resourcePath
import com.gitmob.app.data.model.DocumentResource
import com.gitmob.app.data.model.DocumentResourceGallery
import com.gitmob.app.data.model.DocumentResourceKind
import com.gitmob.app.data.model.RepoDocumentContext
import com.gitmob.app.data.repository.RepoDocumentResource
import com.gitmob.app.ui.common.AppDropdownMenu as DropdownMenu
import com.gitmob.app.ui.common.AppDropdownMenuItem as DropdownMenuItem
import com.materialkolor.material3.rememberDynamicColorScheme
import kotlin.math.roundToInt

@Composable
internal fun DocumentResourceViewer(
    gallery: DocumentResourceGallery,
    document: RepoDocumentContext?,
    onDismiss: () -> Unit,
    onOriginalLink: (String) -> Unit,
) {
    val pager = rememberPagerState(initialPage = gallery.initialIndex) { gallery.resources.size }
    // Retain only the current resource and its neighbors; each page owns its loader and playback.
    val pages = ((pager.currentPage - 1).coerceAtLeast(0)..(pager.currentPage + 1).coerceAtMost(gallery.resources.lastIndex))
        .associateWith { index ->
            key(index) {
                val item = gallery.resources[index]
                val viewModel: DocumentResourceViewModel = hiltViewModel(rememberViewModelStoreOwner())
                val visible = index == pager.currentPage || pager.layoutInfo.visiblePagesInfo.any { it.index == index }
                LaunchedEffect(item, document, visible) { if (visible) viewModel.show(item, document) }
                DisposableEffect(viewModel) { onDispose { viewModel.clear() } }
                val state by viewModel.state.collectAsStateWithLifecycle()
                val zoom = remember(item, state.resource, state.playback) { ResourceZoomState() }
                ResourcePage(viewModel, state, zoom)
            }
        }
    val current = pages.getValue(pager.currentPage)
    var seeking by remember { mutableStateOf(false) }
    LaunchedEffect(current.state.unsupported) {
        if (current.state.unsupported) { onDismiss(); onOriginalLink(gallery.resources[pager.currentPage].source) }
    }
    ResourceViewerDialog(gallery.resources[pager.currentPage], document, current.state, current.viewModel::retry,
        current.viewModel::decodeFailed, current.viewModel::download, onDismiss, onOriginalLink,
        zoom = current.zoom, position = if (gallery.resources.size > 1)
            stringResource(R.string.media_page_position, pager.currentPage + 1, gallery.resources.size) else null,
    ) { modifier ->
        HorizontalPager(pager, modifier, userScrollEnabled = current.zoom.scale <= 1.01f && !seeking) { index ->
            pages[index]?.let { page ->
                ResourceSurface(page.state, page.viewModel::retry, page.viewModel::decodeFailed, page.viewModel::download,
                    gallery.resources[index].description ?: stringResource(R.string.media_viewer_title),
                    Modifier.fillMaxSize(), zoom = page.zoom,
                    onPlaybackError = page.viewModel::playbackFailed,
                    active = index == pager.settledPage && !pager.isScrollInProgress,
                    onSeeking = { seeking = it })
            }
        }
    }
}

private data class ResourcePage(
    val viewModel: DocumentResourceViewModel,
    val state: DocumentResourceState,
    val zoom: ResourceZoomState,
)

@Composable
internal fun RepositoryResourcePreview(
    document: RepoDocumentContext,
    byteSize: Long,
    modifier: Modifier = Modifier,
    viewModel: DocumentResourceViewModel = hiltViewModel(key = "file-resource-preview"),
) {
    val item = remember(document) { DocumentResource(document.baseUrl().toString(), description = document.filePath.substringAfterLast('/')) }
    LaunchedEffect(item, byteSize) { viewModel.show(item, document, byteSize) }
    DisposableEffect(viewModel) { onDispose { viewModel.clear() } }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var viewing by remember(item) { mutableStateOf(false) }
    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
        if (!viewing) ResourceSurface(state, viewModel::retry, viewModel::decodeFailed, viewModel::download,
            item.description.orEmpty(), Modifier.weight(1f).fillMaxWidth().padding(16.dp),
            onClick = { viewing = true }, onPlaybackError = viewModel::playbackFailed)
        else Box(Modifier.weight(1f))
        if (item.kind != DocumentResourceKind.IMAGE) {
            val showFullscreen = state.playback != null || item.kind == DocumentResourceKind.VIDEO || item.kind == DocumentResourceKind.AUDIO
            TextButton(
                onClick = { viewing = true },
                modifier = Modifier.align(Alignment.CenterHorizontally)
                    .then(if (showFullscreen) Modifier else Modifier.alpha(0f).clearAndSetSemantics {}),
                enabled = showFullscreen,
            ) {
                Text(stringResource(R.string.media_fullscreen))
            }
        }
    }
    if (viewing) ResourceViewerDialog(item, document, state, viewModel::retry, viewModel::decodeFailed,
        viewModel::download, { viewing = false }, {}, onPlaybackError = viewModel::playbackFailed)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResourceViewerDialog(
    item: DocumentResource,
    document: RepoDocumentContext?,
    state: DocumentResourceState,
    onRetry: () -> Unit,
    onDecodeError: () -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    onOriginalLink: (String) -> Unit,
    zoom: ResourceZoomState = remember(item, state.resource, state.playback) { ResourceZoomState() },
    position: String? = null,
    onPlaybackError: () -> Unit = onDecodeError,
    resourceContent: @Composable (Modifier) -> Unit = { modifier ->
        ResourceSurface(state, onRetry, onDecodeError, onDownload, item.description ?: stringResource(R.string.media_viewer_title),
            modifier, zoom = zoom, onPlaybackError = onPlaybackError)
    },
) {
    val scheme = rememberDynamicColorScheme(seedColor = MaterialTheme.colorScheme.primary, isDark = true)
    val sourcePath = remember(item, document) {
        document?.resourcePath(item.source) ?: item.source.toUri().takeUnless { it.scheme == "data" }?.lastPathSegment
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        androidx.compose.material3.MaterialExpressiveTheme(
            colorScheme = scheme,
            shapes = MaterialTheme.shapes,
            typography = MaterialTheme.typography,
            motionScheme = MaterialTheme.motionScheme,
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    var menu by remember { mutableStateOf(false) }
                    TopAppBar(
                        title = { Column {
                            Text(item.description?.takeIf { it.isNotBlank() } ?: sourcePath?.substringAfterLast('/')
                                ?: stringResource(R.string.media_viewer_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            sourcePath?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        } },
                        navigationIcon = { ActionIconButton(onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.media_viewer_close)) } },
                        actions = { Box {
                            ActionIconButton({ menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.media_actions)) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { ActionButtonContent(busy = state.isDownloading, label = stringResource(R.string.common_download)) }, enabled = !state.isDownloading,
                                    onClick = { menu = false; onDownload() })
                                item.originalLink?.let { link -> DropdownMenuItem(text = { Text(stringResource(R.string.image_open_original_link)) },
                                    onClick = { menu = false; onDismiss(); onOriginalLink(link) }) }
                            }
                        } },
                        windowInsets = WindowInsets(0, 0, 0, 0),
                    )
                    resourceContent(Modifier.weight(1f).fillMaxWidth())
                    Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        position?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                        item.originalLink?.let { link -> TextButton({ onDismiss(); onOriginalLink(link) }, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.image_open_original_link)) } }
                        if (item.kind != DocumentResourceKind.AUDIO) {
                            val kind = state.playback?.source?.kind ?: if (state.resource != null) DocumentResourceKind.IMAGE else item.kind
                            val showZoom = kind == DocumentResourceKind.IMAGE || kind == DocumentResourceKind.VIDEO
                            TextButton(
                                onClick = zoom::reset,
                                modifier = if (showZoom) Modifier else Modifier.alpha(0f).clearAndSetSemantics {},
                                enabled = showZoom,
                                shapes = ButtonDefaults.shapes(),
                                contentPadding = ButtonDefaults.TextButtonContentPadding,
                            ) {
                                Text(if (zoom.scale <= 1.01f) stringResource(R.string.image_fit_screen)
                                    else stringResource(R.string.image_zoom_reset, (zoom.scale * 100).roundToInt()))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResourceSurface(
    state: DocumentResourceState,
    onRetry: () -> Unit,
    onDecodeError: () -> Unit,
    onDownload: () -> Unit,
    description: String,
    modifier: Modifier,
    zoom: ResourceZoomState? = null,
    onClick: (() -> Unit)? = null,
    onPlaybackError: () -> Unit = onDecodeError,
    active: Boolean = true,
    onSeeking: (Boolean) -> Unit = {},
) {
    // Keep the loading overlay until the new player reports its initial state.
    var playbackLoading by remember(state.playback) { mutableStateOf(true) }
    Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val resource = state.resource
        when {
            state.isLoading -> Unit
            state.error != null -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val tooLarge = (state.error as? ApiError.UserVisible)?.messageRes == R.string.image_preview_too_large
                Text(stringResource(if (tooLarge) R.string.image_preview_too_large else R.string.media_preview_failed))
                if (tooLarge) OutlinedButton(onDownload, enabled = !state.isDownloading, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.ContentPadding) { ActionButtonContent(busy = state.isDownloading, label = stringResource(R.string.common_download)) }
                else {
                    OutlinedButton(onRetry, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.ContentPadding) { Text(stringResource(R.string.common_retry)) }
                    TextButton(onDownload, enabled = !state.isDownloading) { Text(stringResource(R.string.common_download)) }
                }
            }
            state.playback != null -> MediaPlaybackContent(
                playback = state.playback,
                active = active,
                onError = onPlaybackError,
                onLoadingChanged = { playbackLoading = it },
                modifier = Modifier.fillMaxSize(),
                onSeeking = onSeeking,
                zoom = zoom,
            )
            state.unsupported -> Text(stringResource(R.string.common_file_no_preview))
            resource != null -> DecodedImage(resource, description, zoom, onClick, onDecodeError)
            else -> Unit
        }
        val loading = when {
            state.isLoading -> true
            state.error != null -> false
            state.playback != null -> active && playbackLoading
            else -> !state.unsupported && resource == null
        }
        if (loading) CircularProgressIndicator()
    }
}

@Composable
private fun DecodedImage(
    resource: RepoDocumentResource,
    description: String,
    zoom: ResourceZoomState?,
    onClick: (() -> Unit)?,
    onDecodeError: () -> Unit,
) {
    val context = LocalContext.current
    val loader = remember(context) {
        ImageLoader.Builder(context.applicationContext).components {
            add(SvgDecoder.Factory())
            if (Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory())
        }.build()
    }
    DisposableEffect(loader) { onDispose { loader.shutdown() } }
    // Keep the inline preview small while the viewer decodes enough detail for zooming.
    val decodeSize = if (zoom == null) 1536 else 4096
    val request = remember(resource, context, decodeSize) {
        ImageRequest.Builder(context).data(resource.bytes).size(decodeSize, decodeSize).memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED).build()
    }
    val painter = rememberAsyncImagePainter(request, imageLoader = loader)
    val painterState by painter.state.collectAsState()
    LaunchedEffect(painterState) { if (painterState is AsyncImagePainter.State.Error) onDecodeError() }
    var viewport by remember { mutableStateOf(Size.Zero) }
    val intrinsic = (painterState as? AsyncImagePainter.State.Success)?.result?.image?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: Size.Zero
    LaunchedEffect(viewport, intrinsic, zoom) { zoom?.resize(viewport, intrinsic) }
    val enlargeLabel = stringResource(R.string.media_viewer_title)
    var gestures = Modifier.fillMaxSize().onSizeChanged { viewport = Size(it.width.toFloat(), it.height.toFloat()) }
    if (zoom != null) {
        gestures = gestures.resourceGestures(zoom)
    } else if (onClick != null) gestures = gestures.clickable(onClickLabel = enlargeLabel, onClick = onClick)
    Box(gestures, contentAlignment = Alignment.Center) {
        Image(painter, description, Modifier.fillMaxSize().resourceTransform(zoom), contentScale = ContentScale.Fit)
        if (painterState is AsyncImagePainter.State.Loading || painterState is AsyncImagePainter.State.Empty) CircularProgressIndicator()
    }
}
