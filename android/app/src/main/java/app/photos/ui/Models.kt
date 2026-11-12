package app.photos.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.photos.R
import app.photos.data.LocalMedia
import app.photos.data.RemoteMedia
import app.photos.data.SERVER_PLACEHOLDER
import app.photos.data.SyncState
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Badge de vignette. CLOUD_ONLY = sur le serveur mais plus sur ce téléphone. */
enum class Badge { SYNCED, PENDING, UPLOADING, ERROR, CLOUD_ONLY }

/** Granularité de la grille, changée au pincement. */
enum class Zoom(val columns: Int, private val skeleton: String) {
    DAY(3, "yMMMMdEEEE"), MONTH(5, "yMMMM"), YEAR(9, "y");

    /** Recalculé à chaque appel : suit la langue courante (« dimanche 8 novembre 2026 », « 2026年11月8日日曜日 »). */
    val formatter: DateTimeFormatter get() = localizedFormatter(skeleton)
    fun larger() = entries[maxOf(ordinal - 1, 0)]
    fun smaller() = entries[minOf(ordinal + 1, entries.lastIndex)]
}

data class GridItem(
    val key: String,
    val takenAt: LocalDateTime, // heure locale murale, même convention que le serveur
    val isVideo: Boolean,
    val badge: Badge,
    val thumb: String, // URL serveur ou content:// local, chargée par Coil
    val full: String, // image md / original vidéo, ou content:// local
    val remoteId: String?, // null = pas encore sur le serveur
    val localId: Long? = null, // id MediaStore si présent sur le téléphone
    val localUri: String? = null,
    val createdAt: String? = null, // date d'ajout au serveur (Récents)
    val ownerId: String? = null, // membre de la famille qui l'a ajouté
) {
    val description: String
        @Composable get() = stringResource(if (isVideo) R.string.video_of_date else R.string.photo_of_date,
            takenAt.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)))
}

/** Motif de date adapté à la langue du téléphone à partir d'un squelette ICU (ex. « yMMMMd »). */
fun localizedFormatter(skeleton: String): DateTimeFormatter =
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton))

sealed interface GridRow {
    val key: String
    /** [date] : date du premier élément de la section, sert aux onglets année/mois. */
    data class Header(val label: String, val date: LocalDate) : GridRow { override val key = "h:$label" }
    /** [big] : vignette pleine largeur de la mise en page mosaïque. */
    data class Cell(val item: GridItem, val big: Boolean = false) : GridRow { override val key = item.key }
}

data class Memory(val yearsAgo: Int, val items: List<GridItem>)

/** Ce qu'ouvre un écran de collection (personne, lieu, album). */
sealed interface Collection {
    val title: String
    data class Person(val id: String, override val title: String) : Collection
    data class Place(val city: String, val countryCode: String?) : Collection { override val title = city }
    data class Album(val id: String, override val title: String) : Collection
    data class Category(override val title: String) : Collection
    data class Pet(val id: String, override val title: String) : Collection
}

/** Catégories IA : le serveur renvoie le libellé français, qui reste la clé des requêtes. */
private val CATEGORY_LABELS = mapOf(
    "Plage" to R.string.category_beach, "Montagne" to R.string.category_mountain, "Nature" to R.string.category_nature,
    "Ville" to R.string.category_city, "Nourriture" to R.string.category_food, "Animaux" to R.string.category_animals,
    "Bébés et enfants" to R.string.category_kids, "Selfies" to R.string.category_selfies, "Groupes" to R.string.category_groups,
    "Fêtes" to R.string.category_parties, "Neige" to R.string.category_snow, "Nuit" to R.string.category_night,
    "Voitures" to R.string.category_cars, "Documents" to R.string.category_documents,
    "Captures d'écran" to R.string.category_screenshots,
)

/** Libellé traduit d'une catégorie serveur ; catégorie inconnue : libellé brut. */
@Composable
fun categoryLabel(name: String): String = CATEGORY_LABELS[name]?.let { stringResource(it) } ?: name

data class BackupStatus(val total: Int, val waiting: Int, val uploading: Int, val errors: Int)

fun backupStatus(local: List<LocalMedia>) = BackupStatus(
    total = local.size,
    waiting = local.count { it.state == SyncState.PENDING },
    uploading = local.count { it.state == SyncState.UPLOADING },
    errors = local.count { it.state == SyncState.ERROR },
)

/** Timeline = tous les médias locaux (avec état de sync) + médias serveur absents du téléphone. */
fun merge(remote: List<RemoteMedia>, local: List<LocalMedia>): List<GridItem> {
    // Une photo ajoutée via « + Ajouter » peut aussi être dans le DCIM : une seule vignette par contenu
    val unique = local.sortedBy { it.manual }.distinctBy { it.sha256 ?: "id:${it.mediaStoreId}" }
    val localShas = unique.mapNotNullTo(HashSet()) { it.sha256 }
    return (unique.map(::localItem) + remote.filter { it.sha256 !in localShas }.map(::remoteItem))
        .sortedByDescending { it.takenAt }
}

/**
 * Les éléments étant triés, chaque groupe (jour, mois, année) est contigu.
 * [mosaic] : dans chaque section, une vignette sur 7 (la 4e, puis la 11e…) passe en pleine largeur,
 * ce qui casse la monotonie de la grille sans algorithme de « meilleure photo ».
 */
fun sections(items: List<GridItem>, zoom: Zoom, mosaic: Boolean = false): List<GridRow> {
    val out = ArrayList<GridRow>(items.size + items.size / 8)
    var current: String? = null
    var n = 0
    val formatter = zoom.formatter
    for (item in items) {
        val label = item.takenAt.format(formatter).replaceFirstChar(Char::uppercase)
        if (label != current) {
            out += GridRow.Header(label, item.takenAt.toLocalDate())
            current = label
            n = 0
        }
        out += GridRow.Cell(item, big = mosaic && n % 7 == 3)
        n++
    }
    return out
}

/** Mois présents dans la timeline et index de leur première ligne, pour les onglets année/mois. */
fun monthAnchors(rows: List<GridRow>): List<Pair<YearMonth, Int>> {
    val out = ArrayList<Pair<YearMonth, Int>>()
    rows.forEachIndexed { i, row ->
        val ym = when (row) {
            is GridRow.Header -> YearMonth.from(row.date)
            is GridRow.Cell -> YearMonth.from(row.item.takenAt)
        }
        if (out.lastOrNull()?.first != ym) out += ym to i
    }
    return out
}

fun localItem(m: LocalMedia) = GridItem(
    key = "l${m.mediaStoreId}",
    takenAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(m.dateTaken ?: (m.dateModified * 1000)), ZoneId.systemDefault()),
    isVideo = m.mimeType.startsWith("video/"),
    badge = when (m.state) {
        SyncState.SYNCED -> Badge.SYNCED
        SyncState.UPLOADING -> Badge.UPLOADING
        SyncState.ERROR -> Badge.ERROR
        SyncState.PENDING -> Badge.PENDING
    },
    thumb = m.uri,
    full = m.uri,
    remoteId = m.serverId,
    localId = m.mediaStoreId,
    localUri = m.uri,
)

fun remoteItem(r: RemoteMedia): GridItem {
    val base = "$SERVER_PLACEHOLDER/api/v1/media/${r.id}"
    val isVideo = r.kind == "video"
    return GridItem(
        key = "r${r.id}",
        takenAt = OffsetDateTime.parse(r.takenAt).toLocalDateTime(), // UTC = heure locale murale (cf. serveur)
        isVideo = isVideo,
        badge = Badge.CLOUD_ONLY,
        thumb = "$base/thumb/sm",
        full = if (isVideo) "$base/original" else "$base/thumb/md",
        remoteId = r.id,
        createdAt = r.createdAt,
        ownerId = r.ownerId,
    )
}

fun thumbUrl(mediaId: String) = "$SERVER_PLACEHOLDER/api/v1/media/$mediaId/thumb/sm"
fun faceUrl(faceId: String) = "$SERVER_PLACEHOLDER/api/v1/faces/$faceId/thumb"
