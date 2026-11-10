package app.photos.ui

import androidx.compose.ui.res.stringResource
import app.photos.R
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

private data class CleanupKind(val id: String, @StringRes val label: Int, @StringRes val help: Int)

private val KINDS = listOf(
    CleanupKind("duplicates", R.string.cleanup_duplicates, R.string.cleanup_duplicates_help),
    CleanupKind("blurry", R.string.cleanup_blurry, R.string.cleanup_blurry_help),
    CleanupKind("dark", R.string.cleanup_dark, R.string.cleanup_dark_help),
    CleanupKind("screenshots", R.string.cleanup_screenshots, R.string.cleanup_screenshots_help),
    CleanupKind("words", R.string.cleanup_words, R.string.cleanup_words_help),
)

/**
 * Nettoyage : suggestions présélectionnées que l'utilisateur ajuste avant de les mettre à la corbeille.
 * Rien n'est jamais supprimé sans validation, et tout reste restaurable (Corbeille du serveur, corbeille Android).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanupScreen(vm: GalleryViewModel, onOpen: (List<GridItem>, Int) -> Unit, trash: (List<GridItem>) -> Unit, onBack: () -> Unit) {
    var kind by rememberSaveable { mutableStateOf(KINDS.first().id) }
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    val version by vm.trashVersion.collectAsState()
    val result by produceState<List<Pair<GridItem, Boolean>>?>(null, kind, submitted, version) {
        value = null
        value = if (kind == "words" && submitted.isBlank()) emptyList() else vm.cleanup(kind, submitted.takeIf { kind == "words" })
    }
    var selected by remember(result) { mutableStateOf(result.orEmpty().filter { it.second }.map { it.first.key }.toSet()) }
    val items = result.orEmpty().map { it.first }
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                    title = { Text(stringResource(R.string.cleanup_title)) },
                )
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(KINDS, key = { it.id }) { k ->
                        FilterChip(selected = kind == k.id, onClick = { kind = k.id }, label = { Text(stringResource(k.label)) })
                    }
                }
                Text(stringResource(KINDS.first { it.id == kind }.help), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                if (kind == "words") OutlinedTextField(
                    query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), singleLine = true,
                    placeholder = { Text(stringResource(R.string.cleanup_words_placeholder)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submitted = query.trim() }),
                )
            }
        },
        bottomBar = {
            if (items.isNotEmpty()) Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selected = items.map { it.key }.toSet() }) { Text(stringResource(R.string.select_all)) }
                    TextButton(onClick = { selected = emptySet() }) { Text(stringResource(R.string.select_none)) }
                    Button(
                        onClick = { trash(items.filter { it.key in selected }) },
                        enabled = selected.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, null)
                        Text(" " + stringResource(R.string.trash_count, selected.size))
                    }
                }
            }
        },
    ) { padding ->
        val list = result
        when {
            list == null -> Text(stringResource(if (kind == "words") R.string.searching else R.string.analyzing), Modifier.padding(padding).padding(24.dp))
            list.isEmpty() -> EmptyState(Modifier.padding(padding), Icons.Outlined.CleaningServices,
                stringResource(if (kind == "words" && submitted.isBlank()) R.string.cleanup_words_empty else R.string.cleanup_nothing),
                stringResource(if (kind == "words") R.string.cleanup_words_empty_text else R.string.cleanup_nothing_text))
            else -> PhotoGrid(
                items.map { GridRow.Cell(it) }, 3, rememberLazyGridState(), Modifier.padding(padding),
                selected = selected, alwaysSelecting = true,
                onClick = { item -> selected = if (item.key in selected) selected - item.key else selected + item.key },
                onLongClick = { item -> onOpen(items, items.indexOf(item)) },
            )
        }
    }
}

/** Corbeille du serveur : tout est restaurable (le fichier n'est jamais effacé du NAS). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(vm: GalleryViewModel, onBack: () -> Unit) {
    val version by vm.trashVersion.collectAsState()
    val items by produceState<List<GridItem>?>(null, version) { value = vm.trashList() }
    var selected by remember(items) { mutableStateOf(emptySet<String>()) }
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = { Text(stringResource(R.string.trash)) },
            )
        },
        bottomBar = {
            val list = items.orEmpty()
            if (list.isNotEmpty()) Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selected = list.map { it.key }.toSet() }) { Text(stringResource(R.string.select_all)) }
                    Button(onClick = { vm.restore(list.filter { it.key in selected }) }, enabled = selected.isNotEmpty(),
                        modifier = Modifier.weight(1f)) { Text(stringResource(R.string.restore_count, selected.size)) }
                }
            }
        },
    ) { padding ->
        val list = items
        when {
            list == null -> Text(stringResource(R.string.loading), Modifier.padding(padding).padding(24.dp))
            list.isEmpty() -> EmptyState(Modifier.padding(padding), Icons.Outlined.DeleteOutline, stringResource(R.string.trash_empty),
                stringResource(R.string.trash_empty_text))
            else -> PhotoGrid(
                list.map { GridRow.Cell(it) }, 3, rememberLazyGridState(), Modifier.padding(padding),
                selected = selected, alwaysSelecting = true,
                onClick = { item -> selected = if (item.key in selected) selected - item.key else selected + item.key },
            )
        }
    }
}
