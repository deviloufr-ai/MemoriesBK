package app.photos.ui

import androidx.compose.ui.res.stringResource
import app.photos.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/** Personne, lieu ou album : grille par mois, ouverture dans la visionneuse. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    collection: Collection,
    vm: GalleryViewModel,
    onOpen: (List<GridItem>, Int) -> Unit,
    onBack: () -> Unit,
    onRenamed: (Collection) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // trashVersion : une photo supprimée depuis la visionneuse disparaît aussi de la liste
    val version by vm.trashVersion.collectAsState()
    val items by produceState<List<GridItem>?>(null, collection, version) { value = vm.collection(collection) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val unnamed = stringResource(R.string.unnamed)
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = { Text(if (collection is Collection.Category) categoryLabel(collection.title) else collection.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    if (collection is Collection.Person) IconButton(onClick = { renaming = true }) { Icon(Icons.Outlined.Edit, stringResource(R.string.name_person)) }
                    if (collection is Collection.Album || collection is Collection.Pet) IconButton(onClick = { deleting = true }) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete_album)) }
                },
            )
        },
    ) { padding ->
        val list = items
        when {
            list == null -> Text(stringResource(R.string.loading), Modifier.padding(padding).padding(24.dp))
            list.isEmpty() -> Text(stringResource(R.string.nothing_here), Modifier.padding(padding).padding(24.dp))
            else -> PhotoGrid(
                sections(list, Zoom.MONTH), 4, rememberLazyGridState(), Modifier.padding(padding),
                onClick = { onOpen(list, list.indexOf(it)) },
            )
        }
    }

    if (renaming && collection is Collection.Person) {
        TextPromptDialog(stringResource(R.string.name_person_title), stringResource(R.string.name_label), collection.title.takeUnless { it == unnamed } ?: "",
            onDismiss = { renaming = false }) { name ->
            renaming = false
            scope.launch {
                vm.renamePerson(collection.id, name)?.let { id -> onRenamed(Collection.Person(id, name.ifBlank { unnamed })) }
            }
        }
    }
    if (deleting && collection is Collection.Pet) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.delete_pet_title, collection.title)) },
            text = { Text(stringResource(R.string.delete_pet_text)) },
            confirmButton = { TextButton(onClick = { deleting = false; vm.deletePet(collection.id); onBack() }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (deleting && collection is Collection.Album) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.delete_album_title, collection.title)) },
            text = { Text(stringResource(R.string.delete_album_text)) },
            confirmButton = { TextButton(onClick = { deleting = false; vm.deleteAlbum(collection.id); onBack() }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
