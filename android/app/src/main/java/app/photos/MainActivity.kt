package app.photos

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.photos.ui.Collection
import app.photos.ui.CleanupScreen
import app.photos.ui.CollectionScreen
import app.photos.ui.TrashScreen
import app.photos.ui.UpdateChecker
import app.photos.ui.ExploreTab
import app.photos.ui.GalleryViewModel
import app.photos.ui.GridItem
import app.photos.ui.ManageTab
import app.photos.ui.MemoriesTab
import app.photos.ui.FamilyTab
import app.photos.ui.LoginScreen
import app.photos.ui.PhotosTab
import app.photos.ui.ViewerScreen
import app.photos.ui.rememberAddToAlbum
import app.photos.ui.rememberTrashAction
import app.photos.ui.requiredPermissions
import app.photos.ui.shareItems
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.IOException
import javax.inject.Inject

private data class TabSpec(@StringRes val label: Int, val icon: ImageVector, val selected: ImageVector)

private val TABS = listOf(
    TabSpec(R.string.tab_photos, Icons.Outlined.Photo, Icons.Filled.Photo),
    TabSpec(R.string.tab_family, Icons.Outlined.Groups, Icons.Filled.Groups),
    TabSpec(R.string.tab_memories, Icons.Outlined.AutoAwesome, Icons.Filled.AutoAwesome),
    TabSpec(R.string.tab_explore, Icons.Outlined.Search, Icons.Filled.Search),
    TabSpec(R.string.tab_manage, Icons.Outlined.ManageAccounts, Icons.Filled.ManageAccounts),
)

/** Écrans empilés au-dessus des onglets. */
private sealed interface Overlay {
    data class Viewer(val items: List<GridItem>, val index: Int) : Overlay
    data class Open(val collection: Collection) : Overlay
    data object Cleanup : Overlay
    data object Trash : Overlay
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val vm: GalleryViewModel by viewModels()
    @Inject lateinit var okHttp: OkHttpClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val context = LocalContext.current
            val colors = when {
                Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                Surface { App() }
            }
        }
    }

    @Composable
    private fun App() {
        val s by vm.syncSettings.collectAsState()
        val settings = s ?: return // DataStore pas encore lu
        if (settings.token == null) return LoginScreen(vm)

        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        val error by vm.error.collectAsState()
        var tab by rememberSaveable { mutableIntStateOf(0) }
        val overlays = remember { mutableStateListOf<Overlay>() }
        val gridState = rememberLazyGridState()
        var selected by remember { mutableStateOf(emptySet<String>()) }

        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.syncNow() }
        LaunchedEffect(Unit) {
            permissions.launch(requiredPermissions()) // sans dialogue si déjà accordées
            vm.useInternetServer().join()
            vm.refresh()
            vm.loadFamily()
        }
        LaunchedEffect(error) { error?.let { snackbar.showSnackbar(it); vm.error.value = null } }

        val closeViewer = { if (overlays.lastOrNull() is Overlay.Viewer) overlays.removeAt(overlays.lastIndex) }
        val share: (List<GridItem>) -> Unit = { items ->
            scope.launch {
                try { shareItems(context, okHttp, items) } catch (e: IOException) { vm.error.value = context.getString(R.string.share_failed, e.message) }
            }
        }
        val trash = rememberTrashAction(vm) { selected = emptySet(); closeViewer() }
        val addToAlbum = rememberAddToAlbum(vm) { selected = emptySet() }
        val namePet = rememberAddToAlbum(vm, pet = true) { selected = emptySet() }
        val open: (List<GridItem>, Int) -> Unit = { items, index -> overlays += Overlay.Viewer(items, index) }
        val openCollection: (Collection) -> Unit = { overlays += Overlay.Open(it) }

        Box {
            // Composé en premier = priorité la plus basse : visionneuse, sélection, recherche… gèrent « retour » avant.
            UpdateChecker(okHttp) { vm.error.value = it }
            var confirmExit by remember { mutableStateOf(false) }
            BackHandler { if (tab != 0) tab = 0 else confirmExit = true }
            if (confirmExit) androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmExit = false },
                title = { Text(stringResource(R.string.exit_confirm_title)) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { (context as? android.app.Activity)?.finish() }) {
                        Text(stringResource(R.string.exit_confirm))
                    }
                },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmExit = false }) { Text(stringResource(R.string.cancel)) } },
            )
            Scaffold(
                contentWindowInsets = WindowInsets(0),
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    if (selected.isEmpty()) NavigationBar {
                        TABS.forEachIndexed { i, t ->
                            NavigationBarItem(tab == i, { tab = i },
                                icon = { Icon(if (tab == i) t.selected else t.icon, null) }, label = { Text(stringResource(t.label)) })
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.padding(bottom = padding.calculateBottomPadding())) {
                    when (tab) {
                        0 -> PhotosTab(vm, gridState, open, onSearch = { tab = 3 }, share, trash, addToAlbum, namePet, selected) { selected = it }
                        1 -> FamilyTab(vm, open)
                        2 -> MemoriesTab(vm, open)
                        3 -> ExploreTab(vm, open, openCollection)
                        else -> ManageTab(vm, onCleanup = { overlays += Overlay.Cleanup }, onTrash = { overlays += Overlay.Trash })
                    }
                }
            }
            overlays.forEachIndexed { i, overlay ->
                when (overlay) {
                    is Overlay.Open -> CollectionScreen(
                        overlay.collection, vm, open,
                        onBack = { overlays.remove(overlay) },
                        onRenamed = { overlays[i] = Overlay.Open(it) },
                    )
                    Overlay.Cleanup -> CleanupScreen(vm, open, trash, onBack = { overlays.remove(overlay) })
                    Overlay.Trash -> TrashScreen(vm, onBack = { overlays.remove(overlay) })
                    is Overlay.Viewer -> {
                        BackHandler { overlays.remove(overlay) }
                        ViewerScreen(overlay.items, overlay.index, vm, okHttp, share, trash, addToAlbum,
                            onPerson = openCollection, onClose = { overlays.remove(overlay) })
                    }
                }
            }
        }
    }
}
