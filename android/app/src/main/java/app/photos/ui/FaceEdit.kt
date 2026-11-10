package app.photos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.photos.R
import app.photos.data.PersonRef
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * Menu d'un visage reconnu (panneau Infos) : voir la personne, la corriger ou retirer le visage.
 * Toute correction est définitive côté serveur : la détection automatique ne la touche plus.
 */
@Composable
fun FaceDialog(face: PersonRef, vm: GalleryViewModel, onOpen: () -> Unit, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val name = face.name ?: stringResource(R.string.unnamed)
    var picking by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun assign(personId: String? = null, name: String? = null) = scope.launch {
        if (vm.assignFace(face.faceId ?: return@launch, personId, name)) onChanged()
    }

    if (!picking) {
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = face.faceId?.let { id -> { AsyncImage(faceUrl(id), null, Modifier.size(72.dp).clip(CircleShape)) } },
            title = { Text(name) },
            text = {
                Column {
                    TextButton(onClick = onOpen) { Text(stringResource(R.string.face_open)) }
                    if (face.faceId != null) {
                        TextButton(onClick = { picking = true }) { Text(stringResource(R.string.face_not_this, name)) }
                        TextButton(onClick = { assign() }) {
                            Text(stringResource(R.string.face_remove), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
        return
    }

    val people by vm.people.collectAsState()
    LaunchedEffect(Unit) { vm.loadExplore() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.face_who)) },
        text = {
            Column {
                OutlinedTextField(newName, { newName = it }, singleLine = true,
                    label = { Text(stringResource(R.string.face_new_name)) })
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(people.filter { it.id != face.id }, key = { it.id }) { p ->
                        ListItem(
                            leadingContent = { AsyncImage(faceUrl(p.coverFaceId), null, Modifier.size(40.dp).clip(CircleShape)) },
                            headlineContent = { Text(p.name ?: stringResource(R.string.unnamed)) },
                            modifier = Modifier.clickable { assign(personId = p.id) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = newName.isNotBlank(), onClick = { assign(name = newName.trim()) }) { Text(stringResource(R.string.face_assign)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
