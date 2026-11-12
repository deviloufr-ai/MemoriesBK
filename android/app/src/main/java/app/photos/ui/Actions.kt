package app.photos.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import app.photos.R
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material3.Icon
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.photos.data.SERVER_PLACEHOLDER
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Partage : fichiers locaux tels quels, médias serveur téléchargés d'abord dans le cache. */
suspend fun shareItems(context: Context, okHttp: OkHttpClient, items: List<GridItem>) {
    val uris = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        items.map { item -> item.localUri?.let(Uri::parse) ?: download(context, okHttp, dir, item) }
    }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    intent.type = when {
        items.all { it.isVideo } -> "video/*"
        items.none { it.isVideo } -> "image/*"
        else -> "*/*"
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun download(context: Context, okHttp: OkHttpClient, dir: File, item: GridItem): Uri {
    // vidéo souvenir (pas de remoteId) : son URL directe
    val request = Request.Builder().url(item.remoteId?.let { "$SERVER_PLACEHOLDER/api/v1/media/$it/original" } ?: item.full).build()
    okHttp.newCall(request).execute().use { res ->
        if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
        val mime = res.header("Content-Type")?.substringBefore(';')
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: if (item.isVideo) "mp4" else "jpg"
        val file = File(dir, "${item.remoteId ?: item.key}.$ext")
        file.outputStream().use { out -> res.body!!.byteStream().copyTo(out) }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
}

/**
 * Suppression : confirmation, corbeille serveur, puis suppression sur le téléphone via la boîte
 * de dialogue système (Android 11+ ; obligatoire pour effacer les fichiers d'autres apps, ex. l'appareil photo).
 */
@Composable
fun rememberTrashAction(vm: GalleryViewModel, onDone: () -> Unit = {}): (List<GridItem>) -> Unit {
    val context = LocalContext.current
    var confirm by remember { mutableStateOf<List<GridItem>?>(null) }
    var pending by remember { mutableStateOf<List<GridItem>>(emptyList()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) vm.forgetLocal(pending.mapNotNull { it.localId })
        pending = emptyList()
        onDone()
    }
    confirm?.let { items ->
        val onPhone = items.count { it.localUri != null }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(pluralStringResource(R.plurals.delete_items_title, items.size, items.size)) },
            text = {
                Text(
                    buildString {
                        if (items.any { it.remoteId != null }) append(stringResource(R.string.delete_server_note))
                        if (onPhone > 0) append(stringResource(R.string.delete_phone_note, onPhone))
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    vm.trashRemote(items)
                    // createTrashRequest n'accepte que les URI MediaStore : celles du sélecteur (« + Ajouter »)
                    // le font planter. Ces fichiers restent dans leur app d'origine ; on les retire juste de MemoriesBK.
                    val (local, picked) = items.filter { it.localUri != null }.partition { isMediaStoreUri(Uri.parse(it.localUri)) }
                    vm.forgetLocal(picked.mapNotNull { it.localId })
                    val sender = if (local.isNotEmpty() && Build.VERSION.SDK_INT >= 30) runCatching {
                        // Corbeille d'Android (restaurable 30 jours) plutôt que suppression définitive
                        MediaStore.createTrashRequest(context.contentResolver, local.map { Uri.parse(it.localUri) }, true)
                    }.getOrNull() else null
                    if (sender != null) {
                        pending = local
                        launcher.launch(IntentSenderRequest.Builder(sender.intentSender).build())
                    } else {
                        onDone()
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    return { confirm = it }
}

/** Choix d'un album (ou création) pour les éléments déjà sauvegardés sur le serveur ; [pet] : ouvre directement « Mon animal ». */
@Composable
fun rememberAddToAlbum(vm: GalleryViewModel, pet: Boolean = false, onDone: () -> Unit = {}): (List<GridItem>) -> Unit {
    var items by remember { mutableStateOf<List<GridItem>?>(null) }
    items?.let { target ->
        val albums by vm.albums.collectAsState()
        val pets by vm.pets.collectAsState()
        var title by remember { mutableStateOf("") }
        var petMode by remember { mutableStateOf(pet) }  // « Mon animal » : ces photos servent d'exemples
        LaunchedEffect(Unit) { vm.loadAlbums(); vm.loadExplore() }
        val skipped = target.count { it.remoteId == null }
        AlertDialog(
            onDismissRequest = { items = null },
            title = { Text(stringResource(if (petMode) R.string.my_pet else R.string.add_to_album)) },
            text = {
                Column {
                    if (skipped > 0) Text(pluralStringResource(R.plurals.skipped_not_backed_up, skipped, skipped),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                    if (petMode) {
                        Text(stringResource(R.string.my_pet_help), style = MaterialTheme.typography.bodySmall)
                        pets.forEach { pet ->
                            Text(pet.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth()
                                .clickable { vm.namePet(pet.name, target); items = null; onDone() }.padding(vertical = 12.dp))
                        }
                        OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.pet_name)) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    } else LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        item(key = "family") {
                            Row(
                                Modifier.fillMaxWidth().clickable { vm.shareToFamily(target); items = null; onDone() }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.Groups, null, Modifier.size(48.dp).padding(10.dp))
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(stringResource(R.string.tab_family), style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(R.string.share_family_hint), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        item(key = "pet") {
                            Row(
                                Modifier.fillMaxWidth().clickable { petMode = true; title = "" }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.Pets, null, Modifier.size(48.dp).padding(10.dp))
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(stringResource(R.string.my_pet), style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(R.string.my_pet_hint), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        items(albums, key = { it.id }) { album ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    vm.addToAlbum(album.id, target); items = null; onDone()
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AsyncImage(album.coverId?.let(::thumbUrl), null, contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(album.title, style = MaterialTheme.typography.bodyLarge)
                                    Text(pluralStringResource(R.plurals.item_count, album.mediaCount, album.mediaCount), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    if (!petMode) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.new_album)) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank(), onClick = {
                    if (petMode) vm.namePet(title, target) else vm.createAlbum(title, target)
                    items = null; onDone()
                }) { Text(stringResource(if (petMode) R.string.pet_confirm else R.string.create_and_add)) }
            },
            dismissButton = { TextButton(onClick = { items = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    return { items = it }
}

/** Saisie d'un texte (nom de personne, titre d'album). */
@Composable
fun TextPromptDialog(title: String, label: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** content://media/external/images/media/42 oui ; content://media/picker/… (sélecteur de photos) non. */
fun isMediaStoreUri(uri: Uri) = uri.authority == MediaStore.AUTHORITY && uri.pathSegments.none { it.startsWith("picker") }
