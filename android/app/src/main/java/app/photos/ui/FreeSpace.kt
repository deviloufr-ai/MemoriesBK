package app.photos.ui

import android.app.Activity
import android.net.Uri
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhonelinkErase
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.photos.R
import app.photos.data.LocalMedia

/** Fichiers par demande système : la liste passe dans un message Binder (~1 Mo max), trop long = plantage. */
private const val BATCH = 500

/**
 * « Libérer de l'espace » : supprime du téléphone les photos et vidéos déjà sauvegardées sur le serveur.
 * Elles restent visibles dans MemoriesBK (copie serveur). Android affiche sa propre confirmation.
 */
@Composable
fun FreeUpSpaceItem(vm: GalleryViewModel) {
    val context = LocalContext.current
    val freeable by vm.freeable.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    // Lots restant à supprimer : enchaînés automatiquement, Android confirme chacun
    var queue by remember { mutableStateOf<List<List<LocalMedia>>>(emptyList()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && queue.isNotEmpty()) {
            vm.freedSpace(queue.first())
            queue = queue.drop(1)
        } else {
            queue = emptyList() // refusé dans la fenêtre d'Android : on arrête là
        }
    }
    LaunchedEffect(queue) {
        val batch = queue.firstOrNull() ?: return@LaunchedEffect
        val sender = runCatching { MediaStore.createDeleteRequest(context.contentResolver, batch.map { Uri.parse(it.uri) }) }.getOrNull()
        if (sender == null) queue = emptyList()
        else launcher.launch(IntentSenderRequest.Builder(sender.intentSender).build())
    }
    val bytes = freeable.sumOf { it.size }
    ListItem(
        leadingContent = { Icon(Icons.Outlined.PhonelinkErase, null) },
        headlineContent = { Text(stringResource(R.string.free_space)) },
        supportingContent = {
            Text(
                if (freeable.isEmpty()) stringResource(R.string.free_space_none)
                else pluralStringResource(R.plurals.free_space_count, freeable.size, freeable.size, Formatter.formatShortFileSize(context, bytes)),
            )
        },
        modifier = Modifier.clickable(enabled = freeable.isNotEmpty()) { confirm = true },
    )
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.free_space)) },
            text = {
                Text(pluralStringResource(R.plurals.free_space_confirm, freeable.size, freeable.size,
                    Formatter.formatShortFileSize(context, bytes)))
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; queue = freeable.chunked(BATCH) }) {
                    Text(stringResource(R.string.free_space_action))
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
