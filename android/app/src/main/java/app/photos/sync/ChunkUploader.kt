package app.photos.sync

import app.photos.data.PhotosApi
import app.photos.data.UploadInitReq
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

private val OCTET_STREAM = "application/octet-stream".toMediaType()

/**
 * Upload résumable (docs/API.md). La reprise passe toujours par `initUpload`, qui renvoie l'offset
 * connu du serveur : une coupure au milieu d'un bloc ou une réponse perdue se rattrapent au passage suivant.
 * Mémoire bornée à un bloc (8 Mio), quelle que soit la taille de la vidéo.
 */
class ChunkUploader(private val api: PhotosApi, private val chunkSize: Int = 8 shl 20) {

    /** Retourne l'id serveur du média. */
    suspend fun upload(req: UploadInitReq, priority: Boolean = false, open: () -> InputStream): String {
        val init = api.initUpload(req)
        if (init.status == "exists") return requireNotNull(init.mediaId)
        val uploadId = requireNotNull(init.uploadId)
        val chunkLen = if (init.chunkMax > 0) minOf(chunkSize.toLong(), init.chunkMax).toInt() else chunkSize
        val buf = ByteArray(chunkLen)
        var offset = init.offset

        open().use { input ->
            input.skipFully(offset)
            while (offset < req.size) {
                currentCoroutineContext().ensureActive()
                val n = input.readUpTo(buf, minOf(chunkLen.toLong(), req.size - offset).toInt())
                if (n == 0) throw IOException("fichier plus court que prévu (modifié depuis le hash ?)")
                val range = "bytes $offset-${offset + n - 1}/${req.size}"
                val next = api.putChunk(uploadId, range, buf.toRequestBody(OCTET_STREAM, 0, n)).offset
                if (next != offset + n) throw IOException("offset serveur inattendu ($next), reprise au prochain passage")
                offset = next
            }
        }

        val res = api.complete(uploadId, priority)
        if (res.code() == 410) throw IOException("session perdue côté serveur, nouvel upload au prochain passage")
        if (!res.isSuccessful) throw HttpException(res)
        return requireNotNull(res.body()?.mediaId)
    }
}

/** SHA-256 en streaming : tampon de 64 Kio, jamais le fichier entier en RAM. */
fun sha256Hex(input: InputStream): String {
    val md = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        md.update(buf, 0, n)
    }
    return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun InputStream.skipFully(n: Long) {
    var left = n
    while (left > 0) {
        val skipped = skip(left)
        if (skipped > 0) left -= skipped
        else if (read() >= 0) left-- // certains flux renvoient 0 sans être en fin de fichier
        else throw EOFException("fin de fichier avant l'offset $n")
    }
}

private fun InputStream.readUpTo(buf: ByteArray, len: Int): Int {
    var total = 0
    while (total < len) {
        val r = read(buf, total, len - total)
        if (r < 0) break
        total += r
    }
    return total
}
