package app.photos.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import app.photos.R
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ViewQuilt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotosTab(
    vm: GalleryViewModel,
    gridState: LazyGridState,
    onOpen: (List<GridItem>, Int) -> Unit,
    onSearch: () -> Unit,
    share: (List<GridItem>) -> Unit,
    trash: (List<GridItem>) -> Unit,
    addToAlbum: (List<GridItem>) -> Unit,
    namePet: (List<GridItem>) -> Unit,
    selected: Set<String>,
    onSelectedChange: (Set<String>) -> Unit,
) {
    val rows by vm.rows.collectAsState()
    val zoom by vm.zoom.collectAsState()
    val mosaic by vm.mosaic.collectAsState()
    val anchors by vm.anchors.collectAsState()
    val scope = rememberCoroutineScope()
    val cells = remember(rows) { rows.mapNotNull { (it as? GridRow.Cell)?.item } }
    val selectedItems = { cells.filter { it.key in selected } }
    fun toggle(item: GridItem) = onSelectedChange(if (item.key in selected) selected - item.key else selected + item.key)

    // Mois actuellement visible = mois de la première ligne affichée
    val currentIndex by remember(anchors) {
        derivedStateOf {
            val first = gridState.firstVisibleItemIndex
            anchors.indexOfLast { it.second <= first }.coerceAtLeast(0)
        }
    }
    val current = anchors.getOrNull(currentIndex)?.first
    fun jumpTo(index: Int) = scope.launch { gridState.scrollToItem(anchors[index].second) }

    // Les médias serveur, plus récents, arrivent après ceux du téléphone et s'insèrent au-dessus : la grille
    // resterait ancrée sur l'ancienne 1re photo (ex. 2025) et cacherait les récentes. On reste en haut
    // tant que l'utilisateur n'a pas fait défiler lui-même.
    var userScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(gridState.isScrollInProgress) { if (gridState.isScrollInProgress) userScrolled = true }
    LaunchedEffect(rows.firstOrNull()) { if (!userScrolled) gridState.scrollToItem(0) }

    BackHandler(selected.isNotEmpty()) { onSelectedChange(emptySet()) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            if (selected.isNotEmpty()) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    navigationIcon = { IconButton(onClick = { onSelectedChange(emptySet()) }) { Icon(Icons.Default.Close, stringResource(R.string.cancel_selection)) } },
                    title = { Text(pluralStringResource(R.plurals.selected_count, selected.size, selected.size)) },
                    actions = {
                        // Actions principales visibles (avant : Famille et Animal cachés dans « Ajouter à un album »)
                        IconButton(onClick = { vm.shareToFamily(selectedItems()); onSelectedChange(emptySet()) }) {
                            Icon(Icons.Outlined.Groups, stringResource(R.string.share_to_family))
                        }
                        IconButton(onClick = { addToAlbum(selectedItems()) }) { Icon(Icons.Outlined.LibraryAdd, stringResource(R.string.add_to_album)) }
                        IconButton(onClick = { namePet(selectedItems()) }) { Icon(Icons.Outlined.Pets, stringResource(R.string.my_pet)) }
                        IconButton(onClick = { share(selectedItems()) }) { Icon(Icons.Outlined.Share, stringResource(R.string.share)) }
                        IconButton(onClick = { trash(selectedItems()) }) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete)) }
                    },
                )
            } else Column {
                TopAppBar(
                    title = {
                        Column {
                            YearPicker(current?.year, anchors.map { it.first.year }.distinct()) { year ->
                                jumpTo(anchors.indexOfFirst { it.first.year == year })
                            }
                            BackupLine(vm)
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.mosaic.value = !mosaic }) {
                            if (mosaic) Icon(Icons.Outlined.GridView, stringResource(R.string.show_grid))
                            else Icon(Icons.Outlined.ViewQuilt, stringResource(R.string.show_mosaic))
                        }
                        IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, stringResource(R.string.search)) }
                    },
                )
                MonthTabs(anchors, currentIndex, ::jumpTo)
            }
        },
    ) { padding ->
        PhotoGrid(
            rows, zoom.columns, gridState,
            modifier = Modifier.padding(padding).pinchToZoom(
                onSpread = { vm.zoom.value = zoom.larger() },
                onPinch = { vm.zoom.value = zoom.smaller() },
            ).horizontalSwipe(anchors) { dir ->
                // Mois triés du plus récent au plus ancien, comme les onglets : glisser à gauche = mois suivant (plus ancien)
                val target = currentIndex - dir
                if (target in anchors.indices) jumpTo(target)
            },
            selected = selected,
            onClick = { item -> if (selected.isNotEmpty()) toggle(item) else onOpen(cells, cells.indexOf(item)) },
            onLongClick = ::toggle,
            onEndReached = vm::loadMore,
        )
    }
}

/** Onglets des mois de l'année affichée : un toucher saute au mois. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthTabs(anchors: List<Pair<YearMonth, Int>>, currentIndex: Int, jumpTo: (Int) -> Unit) {
    val year = anchors.getOrNull(currentIndex)?.first?.year
    val months = anchors.withIndex().filter { it.value.first.year == year }
    if (months.size <= 1) return
    val selectedTab = months.indexOfFirst { it.index == currentIndex }.coerceAtLeast(0)
    PrimaryScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 12.dp) {
        months.forEachIndexed { i, m ->
            Tab(
                selected = i == selectedTab,
                onClick = { jumpTo(m.index) },
                text = { Text(m.value.first.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()).replaceFirstChar(Char::uppercase)) },
            )
        }
    }
}

@Composable
fun YearPicker(year: Int?, years: List<Int>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = years.size > 1, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text((year ?: "MemoriesBK").toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface)
            if (years.size > 1) Icon(Icons.Default.ArrowDropDown, stringResource(R.string.choose_year), tint = MaterialTheme.colorScheme.onSurface)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            years.forEach { y ->
                DropdownMenuItem(text = { Text(y.toString(), fontWeight = if (y == year) FontWeight.Bold else null) },
                    onClick = { open = false; onPick(y) })
            }
        }
    }
}

@Composable
private fun BackupLine(vm: GalleryViewModel) {
    val status by vm.backup.collectAsState()
    val settings by vm.syncSettings.collectAsState()
    val s = status ?: return
    val remaining = s.waiting + s.uploading
    val (icon, text) = when {
        s.total == 0 -> return
        settings?.autoBackup == false -> Icons.Outlined.CloudOff to
            stringResource(R.string.backup_auto_off) + if (remaining > 0) stringResource(R.string.backup_pending_suffix, remaining) else ""
        remaining > 0 -> Icons.Outlined.CloudUpload to pluralStringResource(R.plurals.backup_remaining, remaining, remaining)
        s.errors > 0 -> Icons.Outlined.ErrorOutline to pluralStringResource(R.plurals.items_in_error, s.errors, s.errors)
        else -> Icons.Default.CloudDone to stringResource(R.string.backup_done)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp))
    }
}
