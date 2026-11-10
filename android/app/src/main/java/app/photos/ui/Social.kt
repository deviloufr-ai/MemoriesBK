package app.photos.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.photos.R
import app.photos.data.Social
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

/** J'aime + commentaires d'un média ; [onChanged] reçoit l'état renvoyé par le serveur après chaque action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(remoteId: String, social: Social, vm: GalleryViewModel, onChanged: (Social) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.comments), style = MaterialTheme.typography.titleMedium)
            if (social.likes.isNotEmpty())
                Text(stringResource(R.string.liked_by, social.likes.joinToString(", ")), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (social.comments.isEmpty())
                Text(stringResource(R.string.no_comments), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(social.comments, key = { it.id }) { c ->
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(c.author, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                Text(relative(c.createdAt), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                            }
                            Text(c.body, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (c.mine) IconButton(onClick = { scope.launch { vm.deleteComment(c.id)?.let(onChanged) } }) {
                            Icon(Icons.Outlined.Delete, stringResource(R.string.delete))
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(text, { text = it.take(2000) }, Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.add_comment)) }, maxLines = 4)
                IconButton(enabled = text.isNotBlank() && !sending, onClick = {
                    sending = true
                    scope.launch {
                        vm.addComment(remoteId, text.trim())?.let { onChanged(it); text = "" }
                        sending = false
                    }
                }) { Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send)) }
            }
        }
    }
}

private fun relative(iso: String): String = runCatching {
    DateUtils.getRelativeTimeSpanString(OffsetDateTime.parse(iso).toInstant().toEpochMilli()).toString()
}.getOrDefault("")
