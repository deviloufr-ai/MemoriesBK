package app.photos.ui

import android.graphics.Point
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.photos.R
import app.photos.data.MapCluster
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import java.io.File
import kotlin.math.floor

/** Point où ouvrir la carte (lieu d'une photo) ; null = cadrer toutes les photos géolocalisées. */
data class MapFocus(val lat: Double, val lon: Double)

/** Zone affichée, envoyée au serveur une fois la carte immobile. */
private data class Viewport(val south: Double, val west: Double, val north: Double, val east: Double, val zoom: Int)

private const val THUMB_DP = 56
private const val FOCUS_ZOOM = 15.0

/**
 * Carte des photos (fond OpenStreetMap). Les photos sont regroupées par le serveur selon le zoom ;
 * toucher un groupe ouvre sa grille (une seule photo : directement la visionneuse).
 * Seules les tuiles de fond viennent d'Internet : la position des photos ne quitte pas le serveur de la maison.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    vm: GalleryViewModel,
    focus: MapFocus?,
    onOpen: (List<GridItem>, Int) -> Unit,
    onCollection: (Collection) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val thumbPx = with(density) { THUMB_DP.dp.roundToPx() }

    var clusters by remember { mutableStateOf(emptyList<MapCluster>()) }
    var viewport by remember { mutableStateOf<Viewport?>(null) }
    var loading by remember { mutableStateOf(false) }
    var empty by remember { mutableStateOf(false) }
    // +1 à chaque image de déplacement : relit les positions à l'écran sans recomposer les vignettes
    var moved by remember { mutableIntStateOf(0) }

    val map = remember {
        Configuration.getInstance().apply {
            // Politique d'usage des tuiles OSM : un User-Agent propre à l'app ; cache dans le stockage privé
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setTilesScaledToDpi(true) // zoom = taille en dp : cases de regroupement du serveur ≈ 64 dp
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setHorizontalMapRepetitionEnabled(false) // longitudes toujours dans [-180, 180]
            setVerticalMapRepetitionEnabled(false)
            setScrollableAreaLimitLatitude(MapView.getTileSystem().maxLatitude, MapView.getTileSystem().minLatitude, 0)
            setMinZoomLevel(2.0)
            setMaxZoomLevel(19.0)
        }
    }

    fun readViewport(): Viewport {
        val box = map.boundingBox
        return Viewport(
            box.latSouth.coerceIn(-90.0, 90.0), box.lonWest.coerceIn(-180.0, 180.0),
            box.latNorth.coerceIn(-90.0, 90.0), box.lonEast.coerceIn(-180.0, 180.0),
            floor(map.zoomLevelDouble).toInt(),
        )
    }

    DisposableEffect(map) {
        val listener = object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean { moved++; return false }
            override fun onZoom(event: ZoomEvent?): Boolean { moved++; return false }
        }
        map.addMapListener(listener)
        onDispose { map.removeMapListener(listener); map.onDetach() }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) map.onResume() else if (e == Lifecycle.Event.ON_PAUSE) map.onPause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Cadrage initial : la photo demandée, sinon l'ensemble des photos géolocalisées
    LaunchedEffect(map) {
        if (focus != null) {
            map.controller.setZoom(FOCUS_ZOOM)
            map.controller.setCenter(GeoPoint(focus.lat, focus.lon))
        } else {
            val extent = vm.mapExtent()
            if (extent != null && extent.count == 0) empty = true
            val s = extent?.south; val w = extent?.west; val n = extent?.north; val e = extent?.east
            if (s != null && w != null && n != null && e != null) {
                val fit = {
                    if (n - s < 0.001 && e - w < 0.001) {
                        map.controller.setZoom(FOCUS_ZOOM)
                        map.controller.setCenter(GeoPoint((s + n) / 2, (w + e) / 2))
                    } else map.zoomToBoundingBox(BoundingBox(n, e, s, w), false, thumbPx)
                }
                if (map.width > 0) fit() else map.addOnFirstLayoutListener { _, _, _, _, _ -> fit() }
            }
        }
        map.post { moved++ }
    }

    // Zone relue une fois la carte immobile 300 ms : pas de recomposition ni de requête à chaque image du geste
    LaunchedEffect(map) {
        snapshotFlow { moved }.collectLatest {
            delay(300)
            while (map.width == 0) delay(50) // pas encore mesurée : la zone visible n'a pas de sens
            viewport = readViewport()
        }
    }

    // Groupes de la zone visible (les anciens restent affichés pendant le chargement)
    LaunchedEffect(viewport) {
        val v = viewport ?: return@LaunchedEffect
        loading = true
        vm.mapClusters(v.south, v.west, v.north, v.east, v.zoom)?.let { clusters = it }
        loading = false
    }

    val areaTitle = stringResource(R.string.map_area)
    fun open(c: MapCluster) {
        val area = Collection.Area(c.city ?: areaTitle, c.south, c.west, c.north, c.east)
        if (c.count > 1) return onCollection(area)
        scope.launch { vm.collection(area).takeIf { it.isNotEmpty() }?.let { onOpen(it, 0) } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = { Text(stringResource(R.string.map)) },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            AndroidView({ map }, Modifier.fillMaxSize())
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter)) // par-dessus : la carte ne bouge pas

            // Vignettes des groupes, placées sur la carte ; relues à chaque déplacement (lambda d'offset = pas de recomposition)
            val point = remember { Point() }
            clusters.forEach { c ->
                val geo = remember(c) { GeoPoint(c.lat, c.lon) }
                Box(
                    Modifier
                        .offset {
                            moved // lu ici : la position suit la carte pendant le geste
                            map.projection.toPixels(geo, point)
                            IntOffset(point.x - thumbPx / 2, point.y - thumbPx / 2)
                        }
                        .size(THUMB_DP.dp)
                        .shadow(4.dp, RoundedCornerShape(10.dp))
                        .clip(RoundedCornerShape(10.dp))
                        .border(2.dp, Color.White, RoundedCornerShape(10.dp))
                        .clickable { open(c) },
                ) {
                    AsyncImage(thumbUrl(c.coverId), c.city, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    if (c.count > 1) Text(
                        if (c.count > 999) "999+" else c.count.toString(),
                        color = Color.White, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.BottomEnd)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(topStart = 6.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }

            // Repère du lieu de la photo d'où l'on vient
            focus?.let { f ->
                val geo = remember(f) { GeoPoint(f.lat, f.lon) }
                val dot = with(density) { 14.dp.roundToPx() }
                Box(
                    Modifier
                        .offset {
                            moved
                            map.projection.toPixels(geo, point)
                            IntOffset(point.x - dot / 2, point.y - dot / 2)
                        }
                        .size(14.dp)
                        .border(2.dp, Color.White, CircleShape)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }

            if (empty) Text(
                stringResource(R.string.map_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Center).padding(32.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
                    .padding(16.dp),
            )

            val total = clusters.sumOf { it.count }
            val v = viewport
            if (total > 0 && v != null) ExtendedFloatingActionButton(
                onClick = { onCollection(Collection.Area(areaTitle, v.south, v.west, v.north, v.east)) },
                icon = { Icon(Icons.Outlined.PhotoLibrary, null) },
                text = { Text(pluralStringResource(R.plurals.map_see_photos, total, total)) },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp),
            )

            // Attribution exigée par OpenStreetMap
            Text(
                stringResource(R.string.map_attribution),
                style = MaterialTheme.typography.labelSmall, color = Color.Black,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding()
                    .background(Color.White.copy(alpha = 0.7f)).padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}
