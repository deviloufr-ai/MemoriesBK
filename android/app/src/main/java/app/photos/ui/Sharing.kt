package app.photos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.photos.R
import app.photos.data.Member
import app.photos.data.ShareRule
import app.photos.data.Sharing
import kotlinx.coroutines.launch

/** Administrateur : ce que [member] peut voir (tout, ou ses photos + celles qui suivent une des règles). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharingSheet(member: Member, vm: GalleryViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val people by vm.people.collectAsState()
    val albums by vm.albums.collectAsState()
    var loaded by remember { mutableStateOf(false) }
    var restricted by remember { mutableStateOf(member.restricted) }
    val rules = remember { mutableStateListOf<ShareRule>() }
    var addingRule by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val name = member.displayName ?: member.email

    LaunchedEffect(member.id) {
        if (people.isEmpty()) vm.loadExplore()
        if (albums.isEmpty()) vm.loadAlbums()
        vm.sharing(member.id)?.let { restricted = it.restricted; rules.clear(); rules.addAll(it.rules); loaded = true }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.sharing_title, name), style = MaterialTheme.typography.titleMedium)
            listOf(false to R.string.sharing_all, true to R.string.sharing_limited).forEach { (value, label) ->
                Row(Modifier.fillMaxWidth().selectable(restricted == value, onClick = { restricted = value }),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(restricted == value, onClick = null)
                    Text(stringResource(label), Modifier.padding(start = 8.dp))
                }
            }
            if (restricted) {
                Text(stringResource(R.string.sharing_help, name), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                rules.forEachIndexed { i, r ->
                    ListItem(
                        headlineContent = { Text(ruleSummary(r, people.associate { it.id to (it.name ?: "?") }, albums.associate { it.id to it.title })) },
                        trailingContent = { IconButton(onClick = { rules.removeAt(i) }) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete)) } },
                    )
                }
                TextButton(onClick = { addingRule = true }) {
                    Icon(Icons.Outlined.Add, null); Text(stringResource(R.string.sharing_add_rule), Modifier.padding(start = 8.dp))
                }
            }
            Button(enabled = loaded && !saving, modifier = Modifier.fillMaxWidth(), onClick = {
                saving = true
                scope.launch {
                    if (vm.putSharing(member.id, Sharing(restricted, if (restricted) rules.toList() else emptyList())) != null) onDismiss()
                    saving = false
                }
            }) { Text(stringResource(R.string.save)) }
        }
    }
    if (addingRule) RuleDialog(vm, onDismiss = { addingRule = false }) { rules += it; addingRule = false }
}

@Composable
private fun ruleSummary(r: ShareRule, people: Map<String, String>, albums: Map<String, String>): String {
    val from = r.dateFrom?.take(4)
    val to = r.dateTo?.take(4)
    val period = when {
        from != null && to != null -> if (from == to) from else "$from – $to"
        from != null -> stringResource(R.string.sharing_since, from)
        to != null -> stringResource(R.string.sharing_until, to)
        else -> stringResource(R.string.sharing_any_date)
    }
    val what = (r.people.map { people[it] ?: "?" } + r.albums.map { "« ${albums[it] ?: "?"} »" })
        .ifEmpty { listOf(stringResource(R.string.sharing_all_photos)) }
    return "$period · ${what.joinToString(", ")}"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleDialog(vm: GalleryViewModel, onDismiss: () -> Unit, onAdd: (ShareRule) -> Unit) {
    val people by vm.people.collectAsState()
    val albums by vm.albums.collectAsState()
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    val pickedPeople = remember { mutableStateListOf<String>() }
    val pickedAlbums = remember { mutableStateListOf<String>() }
    fun year(s: String) = s.trim().toIntOrNull()?.takeIf { it in 1900..2100 }
    val valid = (from.isBlank() || year(from) != null) && (to.isBlank() || year(to) != null) &&
        (year(from) == null || year(to) == null || year(from)!! <= year(to)!!)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sharing_add_rule)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(from, { from = it.take(4) }, Modifier.weight(1f), label = { Text(stringResource(R.string.sharing_from_year)) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(to, { to = it.take(4) }, Modifier.weight(1f), label = { Text(stringResource(R.string.sharing_to_year)) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                val named = people.filter { it.name != null }
                if (named.isNotEmpty()) {
                    Text(stringResource(R.string.sharing_people), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        named.forEach { p ->
                            FilterChip(p.id in pickedPeople, { if (!pickedPeople.remove(p.id)) pickedPeople += p.id }, label = { Text(p.name!!) })
                        }
                    }
                }
                if (albums.isNotEmpty()) {
                    Text(stringResource(R.string.sharing_albums), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        albums.forEach { a ->
                            FilterChip(a.id in pickedAlbums, { if (!pickedAlbums.remove(a.id)) pickedAlbums += a.id }, label = { Text(a.title) })
                        }
                    }
                }
                Text(stringResource(R.string.sharing_rule_help), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onAdd(ShareRule(year(from)?.let { "$it-01-01" }, year(to)?.let { "$it-12-31" }, pickedPeople.toList(), pickedAlbums.toList()))
            }) { Text(stringResource(R.string.add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
