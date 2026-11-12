package app.photos.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.photos.R
import app.photos.data.Person
import app.photos.data.ReviewItem
import coil.compose.AsyncImage

/** Choix d'une personne (avec son visage) ou d'un nouveau nom. [exclude] : la personne de départ. */
@Composable
fun PersonPickerDialog(vm: GalleryViewModel, title: String, exclude: String?, allowNew: Boolean,
                       onDismiss: () -> Unit, onPick: (Person?, String?) -> Unit) {
    val people by vm.people.collectAsState()
    LaunchedEffect(Unit) { vm.loadExplore() }
    var name by remember { mutableStateOf("") }
    val unnamed = stringResource(R.string.unnamed)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(people.filter { it.id != exclude }, key = { it.id }) { p ->
                        ListItem(
                            modifier = Modifier.clickable { onPick(p, null) },
                            leadingContent = { AsyncImage(faceUrl(p.coverFaceId), null, Modifier.size(40.dp).clip(CircleShape)) },
                            headlineContent = { Text(p.name ?: unnamed) },
                            supportingContent = { Text(p.mediaCount.toString() + " photos") },
                        )
                    }
                }
                if (allowNew) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.face_new_name)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            if (allowNew) TextButton(enabled = name.isNotBlank(), onClick = { onPick(null, name.trim()) }) {
                Text(stringResource(R.string.face_assign))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Confirmation de fusion : les deux visages côte à côte, et la fusion reste annulable. */
@Composable
fun MergeConfirmDialog(from: Pair<String?, String>, into: Pair<String?, String>, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.merge_title, into.second)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(from, into).forEach { (face, label) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AsyncImage(face?.let(::faceUrl), null, Modifier.size(72.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                            Text(label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Text(stringResource(R.string.merge_text, from.second, into.second))
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.merge_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** « À vérifier » : une question oui/non à la fois sur les visages douteux et les photos presque reconnues d'un animal. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(vm: GalleryViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var queue by remember { mutableStateOf<List<ReviewItem>?>(null) }
    var round by remember { mutableIntStateOf(0) }
    var answered by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) { queue = null; queue = vm.reviewQueue() }
    val current = queue?.firstOrNull()
    fun next(yes: Boolean?) {
        val item = current ?: return
        if (yes != null) { vm.answerReview(item, yes); answered++ }
        queue = queue.orEmpty().drop(1)
        if (queue.orEmpty().isEmpty()) round++  // série finie : on recharge (les réponses ont modifié la file)
    }
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            title = { Text(stringResource(R.string.review_title)) },
        )
    }) { padding ->
        when {
            queue == null -> Text(stringResource(R.string.loading), Modifier.padding(padding).padding(24.dp))
            current == null -> EmptyState(Modifier.padding(padding), Icons.Outlined.FactCheck, stringResource(R.string.review_done),
                stringResource(R.string.review_done_text, answered))
            else -> Column(
                Modifier.padding(padding).fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AsyncImage(thumbUrl(current.mediaId).replace("/sm", "/md"), null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)))
                current.faceId?.let { AsyncImage(faceUrl(it), null, Modifier.size(96.dp).clip(CircleShape)) }
                Text(stringResource(R.string.review_question, current.name), style = MaterialTheme.typography.headlineSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { next(false) }) { Text(stringResource(R.string.review_no)) }
                    TextButton(onClick = { next(null) }) { Text(stringResource(R.string.review_skip)) }
                    Button(onClick = { next(true) }) { Text(stringResource(R.string.review_yes)) }
                }
            }
        }
    }
}
