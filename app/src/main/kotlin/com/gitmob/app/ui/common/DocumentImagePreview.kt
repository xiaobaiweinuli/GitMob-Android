package com.gitmob.app.ui.common

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
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
import com.gitmob.app.data.model.DocumentImage
import com.gitmob.app.data.model.DocumentImageGallery
import com.gitmob.app.data.model.RepoDocumentContext
import com.gitmob.app.data.repository.RepoDocumentResource
import com.materialkolor.material3.rememberDynamicColorScheme
import kotlin.math.roundToInt

@Composable
internal fun DocumentImageViewer(
    gallery: DocumentImageGallery,
    document: RepoDocumentContext?,
    onDismiss: () -> Unit,
    onOriginalLink: (String) -> Unit,
) {
    val pager = rememberPagerState(initialPage = gallery.initialIndex) { gallery.images.size }
    // Retain only the current image and its neighbors; each page owns its existing loader and zoom.
    val pages = ((pager.currentPage - 1).coerceAtLeast(0)..(pager.currentPage + 1).coerceAtMost(gallery.images.lastIndex))
        .associateWith { index ->
            key(index) {
                val image = gallery.images[index]
                val viewModel: DocumentImageViewModel = hiltViewModel(rememberViewModelStoreOwner())
                val visible = index == pager.currentPage || pager.layoutInfo.visiblePagesInfo.any { it.index == index }
                LaunchedEffect(image, document, visible) { if (visible) viewModel.show(image, document) }
                DisposableEffect(viewModel) { onDispose { viewModel.clear() } }
                val state by viewModel.state.collectAsStateWithLifecycle()
                val zoom = remember(image, state.resource) { ImageZoomState() }
                ImagePage(viewModel, state, zoom)
            }
        }
    val current = pages.getValue(pager.currentPage)
    ImageViewerDialog(gallery.images[pager.currentPage], document, current.state, current.viewModel::retry,
        current.viewModel::decodeFailed, current.viewModel::download, onDismiss, onOriginalLink,
        zoom = current.zoom, position = if (gallery.images.size > 1)
            stringResource(R.string.image_page_position, pager.currentPage + 1, gallery.images.size) else null,
    ) { modifier ->
        HorizontalPager(pager, modifier, userScrollEnabled = current.zoom.scale <= 1.01f) { index ->
            pages[index]?.let { page ->
                ImageSurface(page.state, page.viewModel::retry, page.viewModel::decodeFailed, page.viewModel::download,
                    gallery.images[index].description ?: stringResource(R.string.image_viewer_title),
                    Modifier.fillMaxSize(), zoom = page.zoom)
            }
        }
    }
}

private data class ImagePage(
    val viewModel: DocumentImageViewModel,
    val state: DocumentImageState,
    val zoom: ImageZoomState,
)

@Composable
internal fun RepositoryImagePreview(
    document: RepoDocumentContext,
    byteSize: Long,
    modifier: Modifier = Modifier,
    viewModel: DocumentImageViewModel = hiltViewModel(key = "file-image-preview"),
) {
    val image = remember(document) { DocumentImage(document.baseUrl().toString(), description = document.filePath.substringAfterLast('/')) }
    LaunchedEffect(image, byteSize) { viewModel.show(image, document, byteSize) }
    DisposableEffect(viewModel) { onDispose { viewModel.clear() } }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var viewing by remember(image) { mutableStateOf(false) }
    ImageSurface(state, viewModel::retry, viewModel::decodeFailed, viewModel::download,
        image.description.orEmpty(), modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)).padding(16.dp),
        onClick = { viewing = true })
    if (viewing) ImageViewerDialog(image, document, state, viewModel::retry, viewModel::decodeFailed,
        viewModel::download, { viewing = false }, {})
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageViewerDialog(
    image: DocumentImage,
    document: RepoDocumentContext?,
    state: DocumentImageState,
    onRetry: () -> Unit,
    onDecodeError: () -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    onOriginalLink: (String) -> Unit,
    zoom: ImageZoomState = remember(image, state.resource) { ImageZoomState() },
    position: String? = null,
    imageContent: @Composable (Modifier) -> Unit = { modifier ->
        ImageSurface(state, onRetry, onDecodeError, onDownload, image.description ?: stringResource(R.string.image_viewer_title),
            modifier, zoom = zoom)
    },
) {
    val scheme = rememberDynamicColorScheme(seedColor = MaterialTheme.colorScheme.primary, isDark = true)
    val sourcePath = remember(image, document) {
        document?.resourcePath(image.source) ?: image.source.toUri().takeUnless { it.scheme == "data" }?.lastPathSegment
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MaterialTheme(colorScheme = scheme) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    var menu by remember { mutableStateOf(false) }
                    TopAppBar(
                        title = { Column {
                            Text(image.description?.takeIf { it.isNotBlank() } ?: sourcePath?.substringAfterLast('/')
                                ?: stringResource(R.string.image_viewer_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            sourcePath?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        } },
                        navigationIcon = { IconButton(onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.image_viewer_close)) } },
                        actions = { Box {
                            IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.image_actions)) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.common_download)) }, enabled = !state.isDownloading,
                                    onClick = { menu = false; onDownload() })
                                image.originalLink?.let { link -> DropdownMenuItem(text = { Text(stringResource(R.string.image_open_original_link)) },
                                    onClick = { menu = false; onDismiss(); onOriginalLink(link) }) }
                            }
                        } },
                        windowInsets = WindowInsets(0, 0, 0, 0),
                    )
                    imageContent(Modifier.weight(1f).fillMaxWidth())
                    Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        position?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                        image.originalLink?.let { link -> TextButton({ onDismiss(); onOriginalLink(link) }) { Text(stringResource(R.string.image_open_original_link)) } }
                        if (state.resource != null) {
                            TextButton(zoom::reset) { Text(if (zoom.scale <= 1.01f) stringResource(R.string.image_fit_screen)
                                else stringResource(R.string.image_zoom_reset, (zoom.scale * 100).roundToInt())) }
                            Text(stringResource(R.string.image_gesture_hint), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageSurface(
    state: DocumentImageState,
    onRetry: () -> Unit,
    onDecodeError: () -> Unit,
    onDownload: () -> Unit,
    description: String,
    modifier: Modifier,
    zoom: ImageZoomState? = null,
    onClick: (() -> Unit)? = null,
) {
    Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val resource = state.resource
        when {
            state.isLoading -> CircularProgressIndicator()
            state.error != null -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val tooLarge = (state.error as? ApiError.UserVisible)?.messageRes == R.string.image_preview_too_large
                Text(stringResource(if (tooLarge) R.string.image_preview_too_large else R.string.image_preview_failed))
                if (tooLarge) OutlinedButton(onDownload, enabled = !state.isDownloading) { Text(stringResource(R.string.common_download)) }
                else OutlinedButton(onRetry) { Text(stringResource(R.string.common_retry)) }
            }
            resource != null -> DecodedImage(resource, description, zoom, onClick, onDecodeError)
            else -> CircularProgressIndicator()
        }
    }
}

@Composable
private fun DecodedImage(
    resource: RepoDocumentResource,
    description: String,
    zoom: ImageZoomState?,
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
    val enlargeLabel = stringResource(R.string.image_viewer_title)
    val zoomInLabel = stringResource(R.string.image_zoom_in)
    val zoomOutLabel = stringResource(R.string.image_zoom_out)
    val resetLabel = stringResource(R.string.image_fit_screen)
    val zoomDescription = stringResource(R.string.image_zoom_percent, ((zoom?.scale ?: 1f) * 100).roundToInt())
    var gestures = Modifier.fillMaxSize().onSizeChanged { viewport = Size(it.width.toFloat(), it.height.toFloat()) }
    if (zoom != null) {
        val transform = rememberTransformableState { centroid, factor, pan, _ -> zoom.transform(centroid, pan, factor) }
        gestures = gestures.pointerInput(zoom) { detectTapGestures(onDoubleTap = zoom::doubleTap) }
            .transformable(transform, canPan = { zoom.scale > 1.01f })
            .semantics {
                stateDescription = zoomDescription
                customActions = listOf(
                    CustomAccessibilityAction(zoomInLabel) { zoom.transform(androidx.compose.ui.geometry.Offset(viewport.width / 2, viewport.height / 2), androidx.compose.ui.geometry.Offset.Zero, 1.5f); true },
                    CustomAccessibilityAction(zoomOutLabel) { zoom.transform(androidx.compose.ui.geometry.Offset(viewport.width / 2, viewport.height / 2), androidx.compose.ui.geometry.Offset.Zero, 1 / 1.5f); true },
                    CustomAccessibilityAction(resetLabel) { zoom.reset(); true },
                )
            }
    } else if (onClick != null) gestures = gestures.clickable(onClickLabel = enlargeLabel, onClick = onClick)
    Box(gestures, contentAlignment = Alignment.Center) {
        Image(painter, description, Modifier.fillMaxSize().graphicsLayer {
            scaleX = zoom?.scale ?: 1f; scaleY = zoom?.scale ?: 1f
            translationX = zoom?.offset?.x ?: 0f; translationY = zoom?.offset?.y ?: 0f
        }, contentScale = ContentScale.Fit)
        if (painterState is AsyncImagePainter.State.Loading || painterState is AsyncImagePainter.State.Empty) CircularProgressIndicator()
    }
}
