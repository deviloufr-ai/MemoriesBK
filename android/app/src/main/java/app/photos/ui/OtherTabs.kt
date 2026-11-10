package app.photos.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import app.photos.R
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.photos.data.Member
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.time.LocalDate
import java.time.OffsetDateTime

/** Récents : ce qui attend encore la sauvegarde, puis les derniers ajouts au serveur, par jour d'ajout. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentTab(vm: GalleryViewModel, onOpen: (List<GridItem>, Int) -> Unit) {
    val recent by vm.recent.collectAsState()
    val pending by vm.notBackedUp.collectAsState()
    LaunchedEffect(Unit) { vm.loadRecent() }
    val context = LocalContext.current
    val pendingHeader = stringResource(R.string.pending_backup_header, pending.size)

    val rows = remember(recent, pending) {
        buildList {
            if (pending.isNotEmpty()) {
                add(GridRow.Header(pendingHeader, LocalDate.now()))
                pending.forEach { add(GridRow.Cell(it)) }
            }
            var current: String? = null
            recent.orEmpty().forEach { item ->
                val label = addedLabel(context, item.addedAt)
                if (label != current) { add(GridRow.Header(label, item.takenAt.toLocalDate())); current = label }
                add(GridRow.Cell(item))
            }
        }
    }
    val cells = remember(rows) { rows.mapNotNull { (it as? GridRow.Cell)?.item } }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_recent)) }) }) { padding ->
        when {
            recent == null && pending.isEmpty() -> Text(stringResource(R.string.loading), Modifier.padding(padding).padding(24.dp))
            rows.isEmpty() -> EmptyState(Modifier.padding(padding), Icons.Outlined.CloudSync, stringResource(R.string.nothing_recent),
                stringResource(R.string.nothing_recent_text))
            else -> PhotoGrid(rows, 3, rememberLazyGridState(), Modifier.padding(padding),
                onClick = { onOpen(cells, cells.indexOf(it)) })
        }
    }
}

private fun addedLabel(context: Context, addedAt: LocalDate?): String {
    val today = LocalDate.now()
    return when (addedAt) {
        null -> context.getString(R.string.added_older)
        today -> context.getString(R.string.added_today)
        today.minusDays(1) -> context.getString(R.string.added_yesterday)
        else -> context.getString(R.string.added_on, addedAt.format(localizedFormatter("MMMMdEEEE")))
    }
}

/** Souvenirs : « Il y a N ans » cette semaine, en grandes cartes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoriesTab(vm: GalleryViewModel, onOpen: (List<GridItem>, Int) -> Unit) {
    val memories by vm.memories.collectAsState()
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_memories)) }) }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { MemoryVideosRow(vm, onOpen) }
            if (memories.isEmpty()) item {
                EmptyState(Modifier, Icons.Outlined.AutoAwesome, stringResource(R.string.no_memories),
                    stringResource(R.string.no_memories_text))
            }
            items(memories, key = { it.yearsAgo }) { memory ->
                Box(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(20.dp))
                        .clickable(onClickLabel = stringResource(R.string.view_memory)) { onOpen(memory.items, 0) },
                ) {
                    val cover = memory.items.first()
                    AsyncImage(if (cover.isVideo) cover.thumb else cover.full, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))))
                    Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                        Text(pluralStringResource(R.plurals.years_ago, memory.yearsAgo, memory.yearsAgo), color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(pluralStringResource(R.plurals.memory_photos, memory.items.size, memory.items.size, cover.takenAt.year),
                            color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** Gérer : compte, sauvegarde et accès à la version web. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageTab(vm: GalleryViewModel, onCleanup: () -> Unit, onTrash: () -> Unit) {
    val s by vm.syncSettings.collectAsState()
    val backup by vm.backup.collectAsState()
    val family by vm.family.collectAsState()
    var editing by remember { mutableStateOf<Member?>(null) }
    var adding by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf<Member?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.loadFamily() }
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_manage)) }) }) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item { SectionTitle(stringResource(R.string.section_account)) }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Person, null) },
                    headlineContent = { Text(s?.email ?: stringResource(R.string.account_connected)) },
                    supportingContent = { Text(s?.serverUrl ?: "") },
                )
            }
            familySection(family, onEdit = { editing = it }, onAdd = { adding = true }, onSharing = { sharing = it })
            item { SectionTitle(stringResource(R.string.section_backup)) }
            item {
                val b = backup
                val started = stringResource(R.string.backup_started)
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.CloudSync, null) },
                    headlineContent = { Text(stringResource(R.string.backup_now)) },
                    supportingContent = {
                        Text(if (b == null) "" else stringResource(R.string.backup_progress, b.total - b.waiting - b.uploading - b.errors, b.total) +
                            (if (b.errors > 0) stringResource(R.string.backup_errors_suffix, b.errors) else ""))
                    },
                    modifier = Modifier.clickable { vm.syncNow(); vm.error.value = started },
                )
            }
            item {
                SwitchRow(Icons.Outlined.Backup, stringResource(R.string.auto_backup),
                    stringResource(if (s?.autoBackup != false) R.string.auto_backup_on else R.string.auto_backup_off),
                    s?.autoBackup != false) { vm.setAutoBackup(it) }
            }
            item {
                SwitchRow(Icons.Outlined.Wifi, stringResource(R.string.wifi_only), stringResource(R.string.wifi_only_text), s?.wifiOnly == true) { vm.setWifiOnly(it) }
            }
            item {
                SwitchRow(Icons.Outlined.BatteryChargingFull, stringResource(R.string.charging_only), stringResource(R.string.charging_only_text),
                    s?.chargingOnly == true) { vm.setChargingOnly(it) }
            }
            item {
                SwitchRow(Icons.Outlined.Home, stringResource(R.string.use_local), stringResource(R.string.use_local_text),
                    s?.useLocal != false) { vm.setUseLocal(it) }
            }
            item { SectionTitle(stringResource(R.string.section_library)) }
            item { FreeUpSpaceItem(vm) }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.CleaningServices, null) },
                    headlineContent = { Text(stringResource(R.string.cleanup_title)) },
                    supportingContent = { Text(stringResource(R.string.cleanup_subtitle)) },
                    modifier = Modifier.clickable(onClick = onCleanup),
                )
            }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.DeleteOutline, null) },
                    headlineContent = { Text(stringResource(R.string.trash)) },
                    supportingContent = { Text(stringResource(R.string.trash_subtitle)) },
                    modifier = Modifier.clickable(onClick = onTrash),
                )
            }
            item { SectionTitle(stringResource(R.string.section_computer)) }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Language, null) },
                    headlineContent = { Text(stringResource(R.string.web_version)) },
                    supportingContent = { Text(stringResource(R.string.web_version_text, s?.serverUrl ?: "")) },
                    modifier = Modifier.clickable {
                        s?.serverUrl?.let { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                    },
                )
            }
            item {
                // serverUrl = adresse Internet (Tailscale Funnel), joignable depuis n'importe quel téléphone
                val link = s?.serverUrl?.trimEnd('/')?.plus("/MemoriesBK.apk")
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Share, null) },
                    headlineContent = { Text(stringResource(R.string.share_app)) },
                    supportingContent = { Text(stringResource(R.string.share_app_text)) },
                    modifier = Modifier.clickable {
                        link?.let {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.share_app_message, it))
                            context.startActivity(Intent.createChooser(send, null))
                        }
                    },
                )
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.AutoMirrored.Outlined.Logout, null, tint = MaterialTheme.colorScheme.error) },
                    headlineContent = { Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable { confirmLogout = true },
                )
            }
        }
    }
    if (confirmLogout) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmLogout = false },
        title = { Text(stringResource(R.string.sign_out_confirm_title)) },
        text = { Text(stringResource(R.string.sign_out_confirm_text)) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { confirmLogout = false; vm.logout() }) {
                Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.cancel)) } },
    )
    val roles = family?.roles ?: listOf("papa", "maman", "enfant", "grand-parent", "autre")
    if (adding) MemberDialog(null, roles, onDismiss = { adding = false }) { name, role, email, password ->
        adding = false
        vm.addMember(email, password, name, role)
    }
    sharing?.let { SharingSheet(it, vm, onDismiss = { sharing = null }) }
    editing?.let { m ->
        MemberDialog(m, roles, onDismiss = { editing = null }) { name, role, _, _ ->
            editing = null
            vm.updateMember(m.id, name, role)
        }
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
fun EmptyState(modifier: Modifier, icon: ImageVector, title: String, text: String) {
    Column(modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp))
    }
}

/** Date d'ajout au serveur (Récents), en heure locale du téléphone. */
val GridItem.addedAt: LocalDate?
    get() = createdAt?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate() }.getOrNull() }
