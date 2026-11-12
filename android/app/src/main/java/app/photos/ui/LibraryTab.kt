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
import androidx.compose.material.icons.outlined.CallMerge
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.PersonSearch
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
    var selected by remember(items) { mutableStateOf(emptySet<String>()) }
    var picking by remember { mutableStateOf<String?>(null) }  // "merge" | "move"
    var mergeAsk by remember { mutableStateOf<Triple<String, String?, String>?>(null) }  // (id, visage, nom) de la cible
    var renameTo by remember { mutableStateOf("") }
    val person = collection as? Collection.Person
    val people by vm.people.collectAsState()
    BackHandler(selected.isNotEmpty()) { selected = emptySet() }
    val unnamed = stringResource(R.string.unnamed)
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = {
                    if (selected.isNotEmpty()) Text(selected.size.toString())
                    else Text(if (collection is Collection.Category) categoryLabel(collection.title) else collection.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                actions = {
                    if (person != null && selected.isNotEmpty()) {
                        IconButton(onClick = {
                            vm.moveFaces(person.id, items.orEmpty().filter { it.key in selected }, null, null); selected = emptySet()
                        }) { Icon(Icons.Outlined.PersonOff, stringResource(R.string.face_not_this, person.title)) }
                        IconButton(onClick = { picking = "move" }) { Icon(Icons.Outlined.PersonSearch, stringResource(R.string.faces_this_is)) }
                    } else if (person != null) {
                        IconButton(onClick = { picking = "merge" }) { Icon(Icons.Outlined.CallMerge, stringResource(R.string.merge_with)) }
                    }
                    if (collection is Collection.Person && selected.isEmpty()) IconButton(onClick = { renaming = true }) { Icon(Icons.Outlined.Edit, stringResource(R.string.name_person)) }
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
                selected = selected,
                onClick = { item ->
                    if (selected.isNotEmpty()) selected = if (item.key in selected) selected - item.key else selected + item.key
                    else onOpen(list, list.indexOf(item))
                },
                // Appui long sur la page d'une personne : sélection pour corriger plusieurs visages d'un coup
                onLongClick = if (person != null) ({ item -> selected = selected + item.key }) else null,
            )
        }
    }

    if (renaming && collection is Collection.Person) {
        TextPromptDialog(stringResource(R.string.name_person_title), stringResource(R.string.name_label), collection.title.takeUnless { it == unnamed } ?: "",
            onDismiss = { renaming = false }) { name ->
            renaming = false
            scope.launch {
                val r = vm.renamePerson(collection.id, name)
                when {
                    r.id != null -> onRenamed(Collection.Person(r.id, name.ifBlank { unnamed }))
                    // Nom déjà pris : on montre les deux visages avant de fusionner (plus de fusion silencieuse)
                    r.twinId != null -> { renameTo = name; mergeAsk = Triple(r.twinId, people.firstOrNull { it.id == r.twinId }?.coverFaceId, name) }
                }
            }
        }
    }
    if (person != null) {
        val selfFace = people.firstOrNull { it.id == person.id }?.coverFaceId
        when (picking) {
            "merge" -> PersonPickerDialog(vm, stringResource(R.string.merge_with), person.id, allowNew = false,
                onDismiss = { picking = null }) { p, _ ->
                picking = null
                if (p != null) mergeAsk = Triple(p.id, p.coverFaceId, p.name ?: unnamed)
            }
            "move" -> PersonPickerDialog(vm, stringResource(R.string.faces_this_is), person.id, allowNew = true,
                onDismiss = { picking = null }) { p, name ->
                picking = null
                vm.moveFaces(person.id, items.orEmpty().filter { it.key in selected }, p?.id, name)
                selected = emptySet()
            }
        }
        mergeAsk?.let { (id, face, name) ->
            MergeConfirmDialog(selfFace to person.title, face to name, onDismiss = { mergeAsk = null }) {
                mergeAsk = null
                scope.launch {
                    val kept = if (renameTo.isNotBlank()) vm.renamePerson(person.id, renameTo, merge = true).id
                    else vm.mergePerson(person.id, id, name)
                    renameTo = ""
                    kept?.let { onRenamed(Collection.Person(it, name)) }
                }
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
