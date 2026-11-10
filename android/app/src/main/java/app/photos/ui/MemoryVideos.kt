package app.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.photos.R
import app.photos.data.MemoryVideo
import app.photos.data.SERVER_PLACEHOLDER
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.YearMonth

private val MONTH_FMT = localizedFormatter("MMMMyyyy")

fun MemoryVideo.yearMonth(): YearMonth = YearMonth.parse(month)

/** La vidéo, ouvrable dans la visionneuse comme n'importe quelle vidéo. */
fun MemoryVideo.gridItem() = GridItem(
    key = "mv$id",
    takenAt = LocalDateTime.of(yearMonth().year, yearMonth().month, 1, 0, 0),
    isVideo = true,
    badge = Badge.CLOUD_ONLY,
    thumb = coverMediaId?.let { "$SERVER_PLACEHOLDER/api/v1/media/$it/thumb/md" } ?: "",
    full = "$SERVER_PLACEHOLDER/api/v1/memory-videos/$id/video",
    remoteId = null,
)

/** Rangée « Vidéos du mois » en tête de l'onglet Souvenirs, plus une carte pour en créer une. */
@Composable
fun MemoryVideosRow(vm: GalleryViewModel, onOpen: (List<GridItem>, Int) -> Unit) {
    val videos by vm.memoryVideos.collectAsState()
    var creating by remember { mutableStateOf(false) }
    // Montage en cours sur le serveur : on rafraîchit jusqu'à ce qu'il soit fini
    val busy = videos.any { it.status == "queued" || it.status == "running" }
    LaunchedEffect(busy) {
        vm.loadMemoryVideos()
        while (busy) { delay(30_000); vm.loadMemoryVideos() }
    }
    Column {
        Text(stringResource(R.string.memory_videos), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(videos, key = { it.id }) { v ->
                val ready = v.status == "ready"
                Box(
                    Modifier.size(224.dp, 126.dp).clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = ready) { onOpen(listOf(v.gridItem()), 0) },
                ) {
                    v.coverMediaId?.let { AsyncImage(v.gridItem().thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))))
                    when (v.status) {
                        "ready" -> Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(48.dp))
                        "queued", "running" -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                    }
                    Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                        Text(v.yearMonth().format(MONTH_FMT).replaceFirstChar(Char::uppercase), color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(
                            when (v.status) {
                                "ready" -> stringResource(R.string.memory_video_items, v.mediaCount)
                                "empty" -> stringResource(R.string.memory_video_empty)
                                "error" -> stringResource(R.string.memory_video_error)
                                else -> stringResource(R.string.memory_video_making)
                            },
                            color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            item {
                Box(
                    Modifier.size(224.dp, 126.dp).clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer).clickable { creating = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Add, null)
                        Text(stringResource(R.string.memory_video_create), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
    if (creating) {
        val now = YearMonth.now()
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(stringResource(R.string.memory_video_pick_month)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items((0..23).map { now.minusMonths(it.toLong()) }) { ym ->
                        ListItem(
                            headlineContent = { Text(ym.format(MONTH_FMT).replaceFirstChar(Char::uppercase)) },
                            modifier = Modifier.clickable { creating = false; vm.createMemoryVideo(ym.toString()) },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { creating = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
