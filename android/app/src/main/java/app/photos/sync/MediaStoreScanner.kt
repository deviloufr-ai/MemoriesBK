package app.photos.sync

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import androidx.core.content.ContextCompat
import app.photos.data.LocalMedia
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

class MediaStoreScanner @Inject constructor(@ApplicationContext private val context: Context) {

    fun hasReadPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return granted(perm)
    }

    /**
     * Tout l'index MediaStore des dossiers sauvegardés, en une requête (quelques ms pour 50 000 lignes).
     * Les éléments en cours d'écriture (IS_PENDING) et à la corbeille sont exclus par défaut.
     */
    fun scan(): List<LocalMedia> {
        val projection = arrayOf(
            FileColumns._ID, FileColumns.DISPLAY_NAME, FileColumns.MIME_TYPE, FileColumns.SIZE,
            FileColumns.DATE_MODIFIED, FileColumns.DATE_TAKEN, FileColumns.MEDIA_TYPE,
        )
        // shortcut: dossiers fixes (appareil photo, captures, Pictures) ; ajouter un choix de dossiers dans les réglages.
        val selection = "${FileColumns.MEDIA_TYPE} IN (${FileColumns.MEDIA_TYPE_IMAGE}, ${FileColumns.MEDIA_TYPE_VIDEO}) " +
            "AND ${FileColumns.SIZE} > 0 " +
            "AND (${FileColumns.RELATIVE_PATH} LIKE 'DCIM/%' OR ${FileColumns.RELATIVE_PATH} LIKE 'Pictures/%')"
        val out = ArrayList<LocalMedia>()
        context.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), projection, selection, null, null,
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(FileColumns._ID)
            val name = c.getColumnIndexOrThrow(FileColumns.DISPLAY_NAME)
            val mime = c.getColumnIndexOrThrow(FileColumns.MIME_TYPE)
            val size = c.getColumnIndexOrThrow(FileColumns.SIZE)
            val modified = c.getColumnIndexOrThrow(FileColumns.DATE_MODIFIED)
            val taken = c.getColumnIndexOrThrow(FileColumns.DATE_TAKEN)
            val type = c.getColumnIndexOrThrow(FileColumns.MEDIA_TYPE)
            while (c.moveToNext()) {
                val mimeType = c.getString(mime) ?: continue
                val mediaId = c.getLong(id)
                val collection = if (c.getInt(type) == FileColumns.MEDIA_TYPE_VIDEO)
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                out += LocalMedia(
                    mediaStoreId = mediaId,
                    uri = ContentUris.withAppendedId(collection, mediaId).toString(),
                    displayName = c.getString(name) ?: "media_$mediaId",
                    mimeType = mimeType,
                    size = c.getLong(size),
                    dateModified = c.getLong(modified),
                    dateTaken = c.getLong(taken).takeIf { it > 0 },
                )
            }
        }
        return out
    }

    /**
     * Même flux pour le hash et pour l'upload, sinon le serveur rejette le fichier.
     * Avec ACCESS_MEDIA_LOCATION on lit l'original ; sans, Android masque le GPS des EXIF.
     */
    fun open(uri: String): InputStream {
        val target = Uri.parse(uri).let {
            // setRequireOriginal ne s'applique qu'aux URI MediaStore, pas à celles du sélecteur (« + Ajouter »)
            val mediaStore = it.authority == MediaStore.AUTHORITY && it.pathSegments.firstOrNull() != "picker"
            if (mediaStore && granted(Manifest.permission.ACCESS_MEDIA_LOCATION)) MediaStore.setRequireOriginal(it) else it
        }
        return context.contentResolver.openInputStream(target) ?: throw FileNotFoundException(uri)
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/** Repli `taken_at` du serveur : heure locale murale encodée en UTC, même convention que les EXIF. */
fun localWallClockIso(epochMillis: Long): String =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()).toString() + "Z"
