package app.photos.sync

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.photos.PhotosApp
import app.photos.R
import app.photos.data.CheckHashesReq
import app.photos.data.LocalMedia
import app.photos.data.LocalMediaDao
import app.photos.data.PhotosApi
import app.photos.data.SettingsRepository
import app.photos.data.SyncState
import app.photos.data.UploadInitReq
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Une passe de synchronisation : indexer MediaStore → hacher les nouveautés → demander au serveur
 * ce qu'il a déjà → uploader le reste. Chaque étape persiste son avancement dans Room : si Android
 * arrête le worker (limite de 10 min, Doze, perte du Wi-Fi), la passe suivante reprend où on en était.
 */
@HiltWorker
class MediaSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scanner: MediaStoreScanner,
    private val dao: LocalMediaDao,
    private val api: PhotosApi,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val s = settings.current()
        if (s.token == null || !scanner.hasReadPermission()) return@withContext Result.success()
        // Tâche automatique déjà planifiée avant la désactivation : on ne fait rien (le manuel passe toujours)
        if (!s.autoBackup && SyncScheduler.TAG_MANUAL !in tags) return@withContext Result.success()
        val result = try {
            // Le déclencheur MediaStore et la passe périodique peuvent tomber en même temps
            syncLock.withLock {
                index()
                hashNew()
                markAlreadyOnServer()
                uploadMissing()
            }
            Result.success()
        } catch (e: IOException) {
            Result.retry() // réseau ou serveur indisponible : backoff exponentiel WorkManager
        } catch (e: HttpException) {
            Result.retry() // 401/5xx sur check-hashes ou init
        }
        // Les déclencheurs de contenu sont à usage unique : l'observateur se ré-arme après une passe réussie
        // (pas sur retry : la tâche courante sera relancée, ré-armer allongerait la chaîne à chaque essai).
        if (result is Result.Success && SyncScheduler.TAG_OBSERVER in tags && settings.current().autoBackup) {
            SyncScheduler.rearmObserver(applicationContext, s)
        }
        result
    }

    private suspend fun index() {
        val known = dao.fingerprints().associateBy { it.mediaStoreId }
        val scanned = scanner.scan()
        // Supprimées du téléphone (par une autre app) : on les retire de l'index local, la copie serveur reste
        val present = scanned.mapTo(HashSet()) { it.mediaStoreId }
        (known.filterValues { !it.manual }.keys - present).chunked(500).forEach { dao.deleteAll(it) }
        val changed = scanned.filter { m ->
            val k = known[m.mediaStoreId]
            k == null || k.dateModified != m.dateModified || k.size != m.size // nouveau ou retouché => re-hash
        }
        if (changed.isNotEmpty()) dao.upsert(changed)
    }

    private suspend fun hashNew() {
        for (m in dao.needingHash()) {
            if (isStopped) return
            try {
                dao.setHash(m.mediaStoreId, scanner.open(m.uri).use(::sha256Hex))
            } catch (e: FileNotFoundException) {
                dao.delete(m.mediaStoreId) // supprimé du téléphone entre le scan et le hash
            }
        }
    }

    private suspend fun markAlreadyOnServer() {
        dao.unsynced().chunked(500).forEach { batch ->
            val existing = api.checkHashes(CheckHashesReq(batch.mapNotNull { it.sha256 }.distinct())).existing
            batch.forEach { m ->
                existing[m.sha256]?.let { dao.setState(m.mediaStoreId, SyncState.SYNCED, serverId = it) }
            }
        }
    }

    private suspend fun uploadMissing() {
        val todo = dao.unsynced()
        if (todo.isEmpty()) return
        promoteToForegroundIfLarge(todo)
        val uploader = ChunkUploader(api)
        for (m in todo) {
            if (isStopped) return
            dao.setState(m.mediaStoreId, SyncState.UPLOADING)
            try {
                val serverId = uploader.upload(m.toInitReq(), priority = m.manual) { scanner.open(m.uri) }
                dao.setState(m.mediaStoreId, SyncState.SYNCED, serverId = serverId)
            } catch (e: FileNotFoundException) {
                dao.delete(m.mediaStoreId)
            } catch (e: IOException) {
                dao.setState(m.mediaStoreId, SyncState.PENDING)
                throw e
            } catch (e: HttpException) {
                if (e.code() == 401 || e.code() >= 500) { // jeton expiré ou serveur en panne : toute la passe est à refaire
                    dao.setState(m.mediaStoreId, SyncState.PENDING)
                    throw IOException(e)
                }
                // 4xx : ce fichier précis est refusé (type, taille) ; on passe au suivant, nouvel essai à la prochaine passe
                dao.setState(m.mediaStoreId, SyncState.ERROR, error = "HTTP ${e.code()}")
            }
        }
    }

    /** Service de premier plan pour les gros transferts : Android ne tue pas le process en plein upload vidéo. */
    private suspend fun promoteToForegroundIfLarge(todo: List<LocalMedia>) {
        if (todo.sumOf { it.size } < FOREGROUND_THRESHOLD_BYTES && todo.none { it.mimeType.startsWith("video/") }) return
        val notification = NotificationCompat.Builder(applicationContext, PhotosApp.CHANNEL_SYNC)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(applicationContext.getString(R.string.notif_backup_title))
            .setContentText(applicationContext.resources.getQuantityString(R.plurals.notif_items_to_send, todo.size, todo.size))
            .setOngoing(true)
            .setSilent(true)
            .build()
        try {
            setForeground(ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        } catch (e: IllegalStateException) {
            // Android 12+ refuse un service de premier plan lancé depuis l'arrière-plan :
            // on continue dans la fenêtre normale du worker, la passe suivante reprendra.
        }
    }

    private fun LocalMedia.toInitReq() = UploadInitReq(
        sha256 = requireNotNull(sha256),
        size = size,
        filename = displayName,
        mimeType = mimeType,
        takenAt = dateTaken?.let(::localWallClockIso),
    )

    companion object {
        private val syncLock = Mutex()
        private const val NOTIFICATION_ID = 1001
        private const val FOREGROUND_THRESHOLD_BYTES = 50L shl 20
    }
}
