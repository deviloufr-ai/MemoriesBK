package app.photos.sync

import app.photos.data.CompleteRes
import app.photos.data.OffsetRes
import app.photos.data.PhotosApi
import app.photos.data.UploadInitReq
import app.photos.data.UploadInitRes
import kotlinx.coroutines.test.runTest
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.ByteArrayInputStream
import java.io.IOException
import java.lang.reflect.Proxy

private val unused = Proxy.newProxyInstance(PhotosApi::class.java.classLoader, arrayOf(PhotosApi::class.java)) { _, m, _ ->
    error("non utilisé par ce test : ${m.name}")
} as PhotosApi

class ChunkUploaderTest {
    private val file = ByteArray(10) { it.toByte() }
    private val req = UploadInitReq(sha256Hex(ByteArrayInputStream(file)), file.size.toLong(), "a.jpg", "image/jpeg")

    /** Serveur en mémoire qui applique les mêmes règles que server/api.py ; le reste de l'API lève une erreur. */
    private class FakeApi(val initRes: UploadInitRes, val completeCode: Int = 202) : PhotosApi by unused {
        val received = Buffer()
        val ranges = mutableListOf<String>()
        var offset = initRes.offset

        override suspend fun initUpload(body: UploadInitReq) = initRes
        override suspend fun putChunk(uploadId: String, contentRange: String, chunk: RequestBody): OffsetRes {
            ranges += contentRange
            chunk.writeTo(received)
            offset += chunk.contentLength()
            return OffsetRes(offset)
        }
        override suspend fun complete(uploadId: String, priority: Boolean): Response<CompleteRes> =
            if (completeCode < 300) Response.success(completeCode, CompleteRes("processing", "m1"))
            else Response.error(completeCode, "{}".toResponseBody(null))
    }

    @Test
    fun `reprend a l'offset serveur et decoupe en blocs`() = runTest {
        val api = FakeApi(UploadInitRes("upload", uploadId = "u1", offset = 3, chunkMax = 4))
        val id = ChunkUploader(api, chunkSize = 1 shl 20).upload(req) { ByteArrayInputStream(file) }

        assertEquals("m1", id)
        assertEquals(listOf("bytes 3-6/10", "bytes 7-9/10"), api.ranges)
        assertArrayEquals(file.copyOfRange(3, 10), api.received.readByteArray())
    }

    @Test
    fun `fichier deja present ne transfere rien`() = runTest {
        val api = FakeApi(UploadInitRes("exists", mediaId = "m0"))
        assertEquals("m0", ChunkUploader(api).upload(req) { error("ne doit pas ouvrir le fichier") })
        assertTrue(api.ranges.isEmpty())
    }

    @Test(expected = IOException::class)
    fun `session perdue cote serveur leve une erreur transitoire`() = runTest {
        val api = FakeApi(UploadInitRes("upload", uploadId = "u1", chunkMax = 64), completeCode = 410)
        ChunkUploader(api).upload(req) { ByteArrayInputStream(file) }
    }

    @Test
    fun `sha256 connu`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex(ByteArrayInputStream("abc".toByteArray())),
        )
    }
}

