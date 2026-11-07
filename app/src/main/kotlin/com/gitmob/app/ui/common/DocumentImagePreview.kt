package com.gitmob.app.ui.common

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
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
import com.gitmob.app.data.model.RepoDocumentContext
import com.gitmob.app.data.repository.RepoDocumentResource
import com.materialkolor.rememberDynamicColorScheme
import kotlin.math.roundToInt

@Composable
internal fun DocumentImageViewer(
    image: DocumentImage,
    document: RepoDocumentContext?,
    onDismiss: () -> Unit,
    onOriginalLink: (String) -> Unit,
    viewModel: DocumentImageViewModel = hiltViewModel(key = "document-image-viewer"),
) {
    LaunchedEffect(image, document) { viewModel.show(image, document) }
    DisposableEffect(viewModel) { onDispose { viewModel.clear() } }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ImageViewerDialog(image, document, state, viewModel::retry, viewModel::decodeFailed,
        viewModel::download, onDismiss, onOriginalLink)
}

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
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.image_file_size, byteSize), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ImageSurface(state, viewModel::retry, viewModel::decodeFailed, viewModel::download,
            image.description.orEmpty(), Modifier.fillMaxWidth().height(300.dp), onClick = { viewing = true })
    }
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
) {
    val scheme = rememberDynamicColorScheme(seedColor = MaterialTheme.colorScheme.primary, isDark = true)
    val zoom = remember(image, state.resource) { ImageZoomState() }
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
                    ImageSurface(state, onRetry, onDecodeError, onDownload, image.description ?: stringResource(R.string.image_viewer_title),
                        Modifier.weight(1f).fillMaxWidth(), zoom = zoom)
                    Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
            state.error != null -> Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
        gestures = gestures.pointerInput(zoom) { detectTapGestures(onDoubleTap = zoom::doubleTap) }
            .pointerInput(zoom) { detectTransformGestures { centroid, pan, factor, _ -> zoom.transform(centroid, pan, factor) } }
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
