package app.photos.ui

import androidx.compose.ui.res.stringResource
import app.photos.R
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Person
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import okhttp3.OkHttpClient
import java.util.Locale

/** « dimanche 8 novembre 2026 • 14:05 » / « 2026年11月8日日曜日 • 14:05 », selon la langue. */
private fun dateLabel(t: java.time.LocalDateTime) =
    t.format(localizedFormatter("yMMMMdEEEE")) + " • " + t.format(localizedFormatter("Hm"))

@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
fun ViewerScreen(
    items: List<GridItem>,
    startIndex: Int,
    vm: GalleryViewModel,
    okHttp: OkHttpClient,
    share: (List<GridItem>) -> Unit,
    trash: (List<GridItem>) -> Unit,
    addToAlbum: (List<GridItem>) -> Unit,
    onPerson: (Collection) -> Unit,
    onClose: () -> Unit,
) {
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))) { items.size }
    var chrome by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf<GridItem?>(null) }
    var enhancing by remember { mutableStateOf<GridItem?>(null) }
    var comments by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val family by vm.family.collectAsState()
    if (items.isEmpty()) return

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, key = { items[it].key }, beyondViewportPageCount = 1) { page ->
            val item = items[page]
            if (item.isVideo) {
                // Un seul lecteur à la fois : les pages voisines n'affichent que l'aperçu
                if (pager.currentPage == page) VideoPlayer(item.full, okHttp) { chrome = it }
                else AsyncImage(item.thumb, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                ZoomableImage(item, onTap = { chrome = !chrome })
            }
        }

        val current = items[pager.currentPage]
        var social by remember(current.key) { mutableStateOf<app.photos.data.Social?>(null) }
        LaunchedEffect(current.remoteId) { social = current.remoteId?.let { vm.social(it) } }
        if (comments) current.remoteId?.let { id ->
            social?.let { CommentsSheet(id, it, vm, onChanged = { s -> social = s }, onDismiss = { comments = false }) }
        }
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                    .statusBarsPadding().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White) }
                // Photo encore seulement sur ce téléphone (pas d'ownerId) : c'est la mienne
                val members = family?.members.orEmpty()
                val author = (current.ownerId?.let { id -> members.find { it.id == id } } ?: members.find { it.me }.takeIf { current.ownerId == null })
                    ?.let { if (it.me) stringResource(R.string.you) else it.displayName ?: it.email }
                Column {
                    Text(dateLabel(current.takenAt).replaceFirstChar { it.titlecase(Locale.getDefault()) },
                        color = Color.White, style = MaterialTheme.typography.titleSmall)
                    if (author != null && members.size > 1)
                        Text(stringResource(R.string.added_by_viewer, author), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                    .navigationBarsPadding().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                social?.let { so ->
                    val id = current.remoteId!!
                    ViewerAction(if (so.liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        if (so.likes.isEmpty()) stringResource(R.string.like) else so.likes.size.toString(),
                        tint = if (so.liked) Color(0xFFE53935) else Color.White) {
                        scope.launch { vm.setLike(id, !so.liked)?.let { social = it } }
                    }
                    ViewerAction(Icons.AutoMirrored.Outlined.Comment,
                        if (so.comments.isEmpty()) stringResource(R.string.comment) else so.comments.size.toString()) { comments = true }
                }
                ViewerAction(Icons.Outlined.Share, stringResource(R.string.share)) { share(listOf(current)) }
                ViewerAction(Icons.Outlined.Info, stringResource(R.string.info)) { info = current }
                if (!current.isVideo) ViewerAction(Icons.Outlined.AutoFixHigh, stringResource(R.string.enhance)) {
                    // Sans clé Gemini sur le serveur : retouche locale directe, sinon choix
                    if (family?.gemini == true) enhancing = current else vm.enhance(current, "auto")
                }
                ViewerAction(Icons.Outlined.LibraryAdd, stringResource(R.string.album)) { addToAlbum(listOf(current)) }
                ViewerAction(Icons.Outlined.Delete, stringResource(R.string.delete)) { trash(listOf(current)) }
            }
        }
    }
    enhancing?.let { item ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { enhancing = null },
            title = { Text(stringResource(R.string.enhance_title)) },
            text = { Text(stringResource(R.string.enhance_text)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { enhancing = null; vm.enhance(item, "auto") }) { Text(stringResource(R.string.enhance_auto)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { enhancing = null; vm.enhance(item, "gemini") }) { Text(stringResource(R.string.enhance_gemini)) }
            },
        )
    }
    info?.let { InfoSheet(it, vm, onDismiss = { info = null }, onPerson = { c -> info = null; onPerson(c) }) }
}

@Composable
private fun ViewerAction(icon: ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = label, tint = tint) }
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Pincement / double tap pour zoomer, glisser pour se déplacer dans l'image zoomée.
 * À l'échelle 1, un glisser à un doigt n'est pas consommé : il fait tourner les pages.
 */
@Composable
private fun ZoomableImage(item: GridItem, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    fun clamp(o: Offset): Offset {
        val maxX = (scale - 1) * size.width / 2
        val maxY = (scale - 1) * size.height / 2
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
    Box(
        Modifier.fillMaxSize().onSizeChanged { size = it }
            .pointerInput(item.key) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero } else {
                            scale = 2.5f
                            // zoom centré sur le point touché
                            offset = clamp((Offset(size.width / 2f, size.height / 2f) - tap) * (scale - 1))
                        }
                    },
                )
            }
            .pointerInput(item.key) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // Zoomé : on ne consomme qu'au-delà du seuil de glissement, sinon les taps
                    // (double tap pour dézoomer) n'arrivent jamais à detectTapGestures.
                    var moved = Offset.Zero
                    do {
                        val event = awaitPointerEvent()
                        val multi = event.changes.size > 1
                        if (!multi && scale > 1f) moved += event.calculatePan()
                        // l'événement qui franchit le seuil est consommé aussi, pour que le pager ne tourne pas de page
                        val tapLike = !multi && moved.getDistance() <= viewConfiguration.touchSlop
                        if (multi || (scale > 1f && !tapLike)) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            offset = if (scale == 1f) Offset.Zero else clamp(offset + event.calculatePan())
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        AsyncImage(
            model = item.full,
            contentDescription = item.description,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
            },
        )
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun InfoSheet(item: GridItem, vm: GalleryViewModel, onDismiss: () -> Unit, onPerson: (Collection) -> Unit) {
    var version by remember { mutableStateOf(0) } // +1 après une correction de visage : recharge les infos
    var face by remember { mutableStateOf<app.photos.data.PersonRef?>(null) }
    val details by produceState(initialValue = null as app.photos.data.MediaDetails?, item.remoteId, version) {
        value = item.remoteId?.let { vm.details(it) }
    }
    val unnamed = stringResource(R.string.unnamed)
    face?.let { p ->
        FaceDialog(p, vm, onOpen = { face = null; onPerson(Collection.Person(p.id, p.name ?: unnamed)) },
            onDismiss = { face = null }, onChanged = { face = null; version++ })
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(dateLabel(item.takenAt).replaceFirstChar { it.titlecase(Locale.getDefault()) },
                style = MaterialTheme.typography.titleMedium)
            if (item.remoteId == null) {
                Text(stringResource(R.string.not_backed_up_yet), color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            val d = details ?: return@Column Text(stringResource(R.string.loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
            d.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            if (d.people.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                d.people.forEach { p -> AssistChip(onClick = { face = p }, label = { Text(p.name ?: unnamed) }) }
            }
            d.city?.let { city ->
                InfoRow(Icons.Outlined.Place, listOfNotNull(city, d.admin1, d.countryCode).joinToString(", "),
                    d.latitude?.let { "%.5f, %.5f".format(Locale.ROOT, it, d.longitude) })
            }
            d.uploadedBy?.let { InfoRow(Icons.Outlined.Person, stringResource(R.string.uploaded_by, it), null) }
            val camera = listOfNotNull(d.cameraMake, d.cameraModel).distinct().joinToString(" ")
            if (camera.isNotBlank()) InfoRow(Icons.Outlined.CameraAlt, camera, null)
            InfoRow(
                Icons.Outlined.Image,
                d.originalName ?: stringResource(R.string.file),
                listOfNotNull(
                    if (d.width != null && d.height != null) "${d.width} × ${d.height}" else null,
                    d.sizeBytes?.let { if (it < 1_048_576) stringResource(R.string.size_kb, it / 1024) else stringResource(R.string.size_mb, it / 1_048_576.0) },
                ).joinToString(" • ").ifEmpty { null },
            )
            d.ocrText?.takeIf { it.isNotBlank() }?.let { InfoRow(Icons.Outlined.TextSnippet, stringResource(R.string.detected_text), it) }
            if (d.tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                d.tags.forEach { SuggestionChip(onClick = {}, label = { Text(categoryLabel(it)) }) }
            }
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, subtitle: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/**
 * Lecture de l'original via le même OkHttpClient (hôte + jeton) ; le serveur gère Range, donc le seek
 * ne télécharge pas le fichier entier. content:// (vidéo locale) passe par DefaultDataSource.
 */
@OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(uri: String, okHttp: OkHttpClient, onChrome: (Boolean) -> Unit) {
    val context = LocalContext.current
    val player = remember(uri) {
        val dataSource = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(okHttp))
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    // Nos barres (date, partager…) suivent les commandes du lecteur : masquées après 3 s de lecture, un toucher les ramène
    AndroidView(factory = {
        PlayerView(it).apply {
            this.player = player
            controllerShowTimeoutMs = 3000
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> onChrome(v == android.view.View.VISIBLE) })
        }
    }, modifier = Modifier.fillMaxSize())
}
