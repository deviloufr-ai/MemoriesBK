package app.photos.ui

import androidx.compose.runtime.mutableFloatStateOf
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import app.photos.R
import android.Manifest
import android.os.Build
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoGrid(
    rows: List<GridRow>,
    columns: Int,
    state: LazyGridState,
    modifier: Modifier = Modifier,
    selected: Set<String> = emptySet(),
    alwaysSelecting: Boolean = false, // écrans de tri : cases visibles même sans sélection
    onClick: (GridItem) -> Unit,
    onLongClick: ((GridItem) -> Unit)? = null,
    onEndReached: () -> Unit = {},
    header: (LazyGridScope.() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            header?.invoke(this)
            items(
                rows,
                key = { it.key },
                span = { if (it is GridRow.Header || (it as GridRow.Cell).big) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                contentType = { if (it is GridRow.Header) 0 else if ((it as GridRow.Cell).big) 2 else 1 },
            ) { row ->
                when (row) {
                    is GridRow.Header -> Text(
                        row.label,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 12.dp, top = 20.dp, bottom = 8.dp),
                    )
                    is GridRow.Cell -> Thumb(
                        row.item,
                        selecting = alwaysSelecting || selected.isNotEmpty(),
                        isSelected = row.item.key in selected,
                        compact = columns > 6,
                        big = row.big,
                        onClick = { onClick(row.item) },
                        onLongClick = onLongClick?.let { { it(row.item) } },
                    )
                }
            }
        }
        FastScroller(state, rows, Modifier.align(Alignment.TopEnd))
    }
    // Pagination infinie : page suivante à 60 cellules de la fin
    LaunchedEffect(state, rows.size) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { if (rows.isNotEmpty() && it > rows.size - 60) onEndReached() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Thumb(
    item: GridItem,
    selecting: Boolean,
    isSelected: Boolean,
    compact: Boolean,
    big: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    // Sélectionnée : la vignette rétrécit et s'arrondit, comme dans la plupart des galeries
    val inset by animateDpAsState(if (isSelected) 10.dp else 0.dp, label = "inset")
    val selectLabel = stringResource(R.string.action_select)
    Box(
        Modifier.aspectRatio(if (big) 16f / 10f else 1f)
            .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClickLabel = if (selecting) selectLabel else stringResource(R.string.action_open), onClick = onClick,
                onLongClickLabel = selectLabel, onLongClick = onLongClick),
    ) {
        AsyncImage(
            // pleine largeur : la miniature 320 px serait floue, on prend la 1440 px (ou l'original local)
            model = if (big && !item.isVideo) item.full else item.thumb,
            contentDescription = item.description,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().padding(inset).clip(RoundedCornerShape(if (isSelected) 12.dp else 0.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        if (item.isVideo) Icon(
            Icons.Default.PlayCircle, contentDescription = null, tint = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(if (compact) 12.dp else 18.dp),
        )
        if (!compact) SyncBadge(item.badge, Modifier.align(Alignment.BottomEnd).padding(4.dp))
        if (selecting) Icon(
            if (isSelected) Icons.Default.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = if (isSelected) stringResource(R.string.state_selected) else null,
            tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp)
                .background(if (isSelected) Color.White else Color.Transparent, CircleShape),
        )
    }
}

@Composable
fun SyncBadge(badge: Badge, modifier: Modifier) {
    if (badge == Badge.CLOUD_ONLY) return // sur le serveur seulement : vignette normale
    Box(modifier.size(20.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape), Alignment.Center) {
        val m = Modifier.size(14.dp)
        when (badge) {
            Badge.SYNCED -> Icon(Icons.Default.CloudDone, stringResource(R.string.badge_synced), m, tint = Color.White)
            Badge.PENDING -> Icon(Icons.Outlined.CloudUpload, stringResource(R.string.badge_pending), m, tint = Color.White)
            Badge.UPLOADING -> {
                val label = stringResource(R.string.badge_uploading)
                CircularProgressIndicator(m.semantics { contentDescription = label }, color = Color.White, strokeWidth = 2.dp)
            }
            Badge.ERROR -> Icon(Icons.Default.ErrorOutline, stringResource(R.string.badge_error), m, tint = Color(0xFFFF8A80))
            Badge.CLOUD_ONLY -> Unit
        }
    }
}

/** Poignée à droite : glisser pour parcourir des années de photos, avec la date en bulle. */
@Composable
private fun FastScroller(state: LazyGridState, rows: List<GridRow>, modifier: Modifier) {
    if (rows.size < 200) return
    var trackPx by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    var target by remember { mutableIntStateOf(-1) }
    val thumbPx = with(LocalDensity.current) { 48.dp.toPx() }
    val fraction by remember(rows.size) {
        derivedStateOf { state.firstVisibleItemIndex.toFloat() / (rows.size - 1).coerceAtLeast(1) }
    }
    // La poignée reste 1,5 s après le défilement, le temps de l'attraper
    var linger by remember { mutableStateOf(false) }
    LaunchedEffect(state.isScrollInProgress) {
        if (state.isScrollInProgress) linger = true else { delay(1500); linger = false }
    }
    val visible = dragging || linger
    LaunchedEffect(target) { if (target >= 0) state.scrollToItem(target) }
    var dragY by remember { mutableFloatStateOf(0f) }

    // Pas de zone tactile sur toute la hauteur : seule la poignée visible capte le doigt, le reste défile normalement
    Box(modifier.fillMaxHeight().width(56.dp).onSizeChanged { trackPx = it.height }) {
        if (!visible) return@Box
        val y = (fraction * (trackPx - thumbPx)).roundToInt()
        val handle = Modifier.pointerInput(rows.size) {
            detectVerticalDragGestures(
                onDragStart = { dragging = true; dragY = fraction * (trackPx - thumbPx) },
                onDragEnd = { dragging = false },
                onDragCancel = { dragging = false },
            ) { change, amount ->
                change.consume()
                dragY = (dragY + amount).coerceIn(0f, trackPx - thumbPx)
                target = (dragY / (trackPx - thumbPx).coerceAtLeast(1f) * (rows.size - 1)).roundToInt()
            }
        }
        Row(Modifier.align(Alignment.TopEnd).offset { IntOffset(0, y) }, verticalAlignment = Alignment.CenterVertically) {
            if (dragging) {
                val label = remember(state.firstVisibleItemIndex, rows) { labelAt(rows, state.firstVisibleItemIndex) }
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.offset(x = (-8).dp)) {
                    Text(label, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
            Surface(shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
                color = MaterialTheme.colorScheme.secondaryContainer, shadowElevation = 2.dp,
                modifier = handle.size(width = 20.dp, height = 48.dp)) {}
        }
    }
}

private fun labelAt(rows: List<GridRow>, index: Int): String {
    for (i in index.coerceIn(0, rows.lastIndex) downTo 0) (rows[i] as? GridRow.Header)?.let { return it.label }
    return ""
}

/**
 * Pincement à deux doigts : intercepté en passe Initial pour que la grille ne défile pas pendant le geste.
 * Le défilement à un doigt n'est pas touché.
 */
fun Modifier.pinchToZoom(onSpread: () -> Unit, onPinch: () -> Unit) = pointerInput(onSpread, onPinch) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var zoom = 1f
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                zoom *= event.calculateZoom()
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (zoom > 1.25f) onSpread() else if (zoom < 0.8f) onPinch()
    }
}

/** Glissement horizontal franc (> 80 dp) : -1 = vers la gauche, +1 = vers la droite. Le défilement vertical n'est pas touché. */
fun Modifier.horizontalSwipe(key: Any?, onSwipe: (Int) -> Unit) = pointerInput(key) {
    val threshold = 80.dp.toPx()
    var dx = 0f
    detectHorizontalDragGestures(
        onDragStart = { dx = 0f },
        onDragEnd = { if (dx < -threshold) onSwipe(-1) else if (dx > threshold) onSwipe(1) },
    ) { change, amount -> change.consume(); dx += amount }
}

fun requiredPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= 33) {
        add(Manifest.permission.READ_MEDIA_IMAGES)
        add(Manifest.permission.READ_MEDIA_VIDEO)
        add(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    add(Manifest.permission.ACCESS_MEDIA_LOCATION)
}.toTypedArray()
