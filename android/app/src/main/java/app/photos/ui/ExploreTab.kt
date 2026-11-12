package app.photos.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import app.photos.R
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** Requête envoyée au serveur (en français) -> libellé affiché. */
private val SUGGESTIONS = listOf(
    "Plage" to R.string.category_beach, "Montagne" to R.string.category_mountain, "Coucher de soleil" to R.string.suggestion_sunset,
    "Nourriture" to R.string.category_food, "Chat" to R.string.suggestion_cat, "Chien" to R.string.suggestion_dog,
    "Fête" to R.string.suggestion_party, "Neige" to R.string.category_snow, "Ville la nuit" to R.string.suggestion_city_night,
    "Selfie" to R.string.suggestion_selfie, "Documents" to R.string.category_documents, "Captures d'écran" to R.string.category_screenshots,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExploreTab(vm: GalleryViewModel, onOpen: (List<GridItem>, Int) -> Unit, onCollection: (Collection) -> Unit) {
    val results by vm.searchResults.collectAsState()
    val searching by vm.searching.collectAsState()
    val people by vm.people.collectAsState()
    val pets by vm.pets.collectAsState()
    val places by vm.places.collectAsState()
    val albums by vm.albums.collectAsState()
    val categories by vm.categories.collectAsState()
    var creating by remember { mutableStateOf(false) }
    val query by vm.searchQuery.collectAsState()
    val focus = LocalFocusManager.current
    val unnamed = stringResource(R.string.unnamed)
    LaunchedEffect(Unit) { vm.loadExplore(); vm.loadAlbums() }

    /** [shown] : texte affiché dans le champ ; [q] : requête envoyée (clé française pour les suggestions). */
    fun run(q: String, shown: String = q) {
        vm.searchQuery.value = shown
        focus.clearFocus()
        vm.search(q)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        OutlinedTextField(
            value = query,
            onValueChange = { vm.searchQuery.value = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = {
                if (results != null) IconButton(onClick = { vm.searchQuery.value = ""; vm.search("") }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back_to_explore))
                } else Icon(Icons.Default.Search, null)
            },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { vm.searchQuery.value = "" }) { Icon(Icons.Default.Clear, stringResource(R.string.clear)) }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run(query) }),
        )
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())

        val shown = results
        if (shown != null) {
            if (shown.isEmpty() && !searching) Text(stringResource(R.string.no_results, query), Modifier.padding(24.dp))
            else PhotoGrid(
                shown.map { GridRow.Cell(it) }, 4, rememberLazyGridState(),
                onClick = { onOpen(shown, shown.indexOf(it)) },
            )
            return@Column
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            if (people.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.section_people)) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(people, key = { it.id }) { p ->
                            Column(
                                Modifier.width(76.dp).clickable { onCollection(Collection.Person(p.id, p.name ?: unnamed)) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                AsyncImage(faceUrl(p.coverFaceId), p.name, contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(72.dp).clip(CircleShape))
                                Text(p.name ?: stringResource(R.string.add_name), style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (p.name == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
            if (pets.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.section_pets)) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(pets, key = { it.id }) { p ->
                            Column(
                                Modifier.width(76.dp).clickable { onCollection(Collection.Pet(p.id, p.name)) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                AsyncImage(p.coverId?.let(::thumbUrl), p.name, contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(72.dp).clip(CircleShape))
                                Text(p.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
            if (categories.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.section_categories)) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(categories, key = { it.name }) { cat ->
                            Box(
                                Modifier.size(120.dp).clip(RoundedCornerShape(14.dp))
                                    .clickable { onCollection(Collection.Category(cat.name)) },
                            ) {
                                AsyncImage(thumbUrl(cat.coverId), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                                    0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
                                Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                                    Text(categoryLabel(cat.name), color = Color.White, style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${cat.mediaCount}", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
            if (places.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.section_places)) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(places, key = { "${it.city}|${it.countryCode}" }) { place ->
                            Box(
                                Modifier.size(120.dp).clip(RoundedCornerShape(14.dp))
                                    .clickable { onCollection(Collection.Place(place.city, place.countryCode)) },
                            ) {
                                AsyncImage(thumbUrl(place.coverId), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                                    0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.65f))))
                                Text(
                                    place.city, color = Color.White,
                                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.align(Alignment.BottomStart)
                                        .padding(8.dp),
                                )
                            }
                        }
                    }
                }
            }
            item { SectionTitle(stringResource(R.string.section_albums)) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Column(Modifier.width(120.dp).clickable { creating = true }) {
                            Box(Modifier.size(120.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Add, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                            Text(stringResource(R.string.new_album), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                    items(albums, key = { it.id }) { album ->
                        Column(Modifier.width(120.dp).clickable { onCollection(Collection.Album(album.id, album.title)) }) {
                            Box(Modifier.size(120.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                album.coverId?.let { AsyncImage(thumbUrl(it), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                            }
                            Text(album.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp))
                            Text(pluralStringResource(R.plurals.item_count, album.mediaCount, album.mediaCount), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item { SectionTitle(stringResource(R.string.section_ideas)) }
            item {
                FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SUGGESTIONS.forEach { (key, label) ->
                        val text = stringResource(label)
                        AssistChip(onClick = { run(key, text) }, label = { Text(text) })
                    }
                }
            }
            if (people.isEmpty() && places.isEmpty()) item {
                Text(
                    stringResource(R.string.explore_empty),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
    }
    if (creating) CreateAlbumDialog(vm) { creating = false }
}

@Composable
private fun CreateAlbumDialog(vm: GalleryViewModel, onDone: () -> Unit) =
    TextPromptDialog(stringResource(R.string.new_album), stringResource(R.string.album_title_label), "", onDismiss = onDone) { if (it.isNotBlank()) vm.createAlbum(it); onDone() }

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 10.dp))
}
