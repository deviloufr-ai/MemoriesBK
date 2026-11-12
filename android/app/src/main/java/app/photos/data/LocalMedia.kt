package app.photos.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** États affichés en badge sur chaque vignette locale. */
enum class SyncState { PENDING, UPLOADING, SYNCED, ERROR }

@Entity(tableName = "local_media", indices = [Index("sha256"), Index("state")])
data class LocalMedia(
    @PrimaryKey val mediaStoreId: Long,
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val size: Long,
    val dateModified: Long, // secondes (MediaStore)
    val dateTaken: Long?, // millisecondes epoch
    val sha256: String? = null, // null = à hacher
    val state: SyncState = SyncState.PENDING,
    val serverId: String? = null,
    val error: String? = null,
    /** Ajouté via « + Ajouter » (sélecteur Android) : hors des dossiers scannés, id synthétique négatif. */
    val manual: Boolean = false,
)

data class Fingerprint(val mediaStoreId: Long, val dateModified: Long, val size: Long, val manual: Boolean)

@Dao
interface LocalMediaDao {
    @Query("SELECT mediaStoreId, dateModified, size, manual FROM local_media")
    suspend fun fingerprints(): List<Fingerprint>

    @Upsert
    suspend fun upsert(items: List<LocalMedia>)

    @Query("SELECT * FROM local_media WHERE sha256 IS NULL")
    suspend fun needingHash(): List<LocalMedia>

    @Query("UPDATE local_media SET sha256 = :sha256 WHERE mediaStoreId = :id")
    suspend fun setHash(id: Long, sha256: String)

    @Query("SELECT * FROM local_media WHERE sha256 IS NOT NULL AND state != 'SYNCED' ORDER BY dateTaken DESC")
    suspend fun unsynced(): List<LocalMedia>

    @Query("UPDATE local_media SET state = :state, serverId = :serverId, error = :error WHERE mediaStoreId = :id")
    suspend fun setState(id: Long, state: SyncState, serverId: String? = null, error: String? = null)

    @Query("DELETE FROM local_media WHERE mediaStoreId = :id")
    suspend fun delete(id: Long)

    /** Par lots de ≤ 500 : SQLite limite le nombre de paramètres d'une requête. */
    @Query("DELETE FROM local_media WHERE mediaStoreId IN (:ids)")
    suspend fun deleteAll(ids: List<Long>)

    /** Pour la grille : badges Synchronisé / En attente / En cours / Erreur. */
    @Query("SELECT * FROM local_media ORDER BY dateTaken DESC")
    fun observeAll(): Flow<List<LocalMedia>>
}

@Database(entities = [LocalMedia::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun localMedia(): LocalMediaDao
}

/** v2 : colonne `manual` ; une migration plutôt qu'une base recréée, sinon tous les hash seraient à recalculer. */
val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE local_media ADD COLUMN manual INTEGER NOT NULL DEFAULT 0")
    }
}
