package app.photos.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Contrat : docs/API.md. Hôte et jeton injectés par [ServerInterceptor]. */
interface PhotosApi {
    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginReq): LoginRes

    @POST("api/v1/sync/check-hashes")
    suspend fun checkHashes(@Body body: CheckHashesReq): CheckHashesRes

    @POST("api/v1/uploads")
    suspend fun initUpload(@Body body: UploadInitReq): UploadInitRes

    @PUT("api/v1/uploads/{id}")
    suspend fun putChunk(
        @Path("id") uploadId: String,
        @Header("Content-Range") contentRange: String,
        @Body chunk: RequestBody,
    ): OffsetRes

    @POST("api/v1/uploads/{id}/complete")
    suspend fun complete(@Path("id") uploadId: String, @Query("priority") priority: Boolean = false): Response<CompleteRes>

    @GET("api/v1/media/timeline")
    suspend fun timeline(@Query("cursor") cursor: String?, @Query("limit") limit: Int = 200): TimelinePage

    @GET("api/v1/search/semantic")
    suspend fun search(@Query("q") query: String, @Query("limit") limit: Int = 60): SearchRes

    @GET("api/v1/media/{id}")
    suspend fun details(@Path("id") mediaId: String): MediaDetails

    @GET("api/v1/media/query")
    suspend fun query(
        @Query("person_id") personId: String? = null,
        @Query("album_id") albumId: String? = null,
        @Query("pet_id") petId: String? = null,
        @Query("city") city: String? = null,
        @Query("country_code") countryCode: String? = null,
        @Query("tag") tag: String? = null,
        @Query("limit") limit: Int? = null,
    ): SearchRes

    @GET("api/v1/family/media")
    suspend fun familyMedia(@Query("limit") limit: Int? = null): SearchRes

    @POST("api/v1/family/media")
    suspend fun familyAdd(@Body body: IdsReq): AddedRes

    @POST("api/v1/family/media/remove")
    suspend fun familyRemove(@Body body: IdsReq): Response<Unit>

    @GET("api/v1/pets")
    suspend fun pets(): PetsRes

    @POST("api/v1/pets")
    suspend fun namePet(@Body body: PetCreate): Pet

    @DELETE("api/v1/pets/{id}")
    suspend fun deletePet(@Path("id") petId: String): Response<Unit>

    @GET("api/v1/family/people")
    suspend fun familyPeople(): FamilyPeopleRes

    @POST("api/v1/family/people")
    suspend fun familyPeopleAdd(@Body body: PersonReq): AddedRes

    @DELETE("api/v1/family/people/{id}")
    suspend fun familyPeopleRemove(@Path("id") personId: String): Response<Unit>

    @POST("api/v1/family/pets")
    suspend fun familyPetsAdd(@Body body: PetIdReq): AddedRes

    @DELETE("api/v1/family/pets/{id}")
    suspend fun familyPetsRemove(@Path("id") petId: String): Response<Unit>

    @POST("api/v1/media/trash")
    suspend fun trash(@Body body: IdsReq): TrashRes

    @PUT("api/v1/faces/{id}/person")
    suspend fun assignFace(@Path("id") faceId: String, @Body body: FaceAssign)

    @GET("api/v1/people")
    suspend fun people(): PeopleRes

    @POST("api/v1/people/{id}/merge")
    suspend fun mergePerson(@Path("id") personId: String, @Body body: MergeReq): PersonPatchRes

    @POST("api/v1/people/{id}/faces/move")
    suspend fun moveFaces(@Path("id") personId: String, @Body body: FacesMove): FacesMoveRes

    @POST("api/v1/face-events/{batch}/undo")
    suspend fun undoFaces(@Path("batch") batch: String): Response<Unit>

    @GET("api/v1/review")
    suspend fun review(): ReviewRes

    @POST("api/v1/review")
    suspend fun answerReview(@Body body: ReviewAnswer): Response<Unit>

    @GET("api/v1/media/{id}/social")
    suspend fun social(@Path("id") mediaId: String): Social

    @PUT("api/v1/media/{id}/like")
    suspend fun setLike(@Path("id") mediaId: String, @Body body: LikeReq): Social

    @POST("api/v1/media/{id}/comments")
    suspend fun addComment(@Path("id") mediaId: String, @Body body: CommentReq): Social

    @DELETE("api/v1/comments/{id}")
    suspend fun deleteComment(@Path("id") commentId: String): Social

    @PATCH("api/v1/people/{id}")
    suspend fun renamePerson(@Path("id") personId: String, @Body body: PersonPatch): PersonPatchRes

    @GET("api/v1/places")
    suspend fun places(): PlacesRes

    @GET("api/v1/albums")
    suspend fun albums(): AlbumsRes

    @POST("api/v1/albums")
    suspend fun createAlbum(@Body body: AlbumCreate): AlbumCreateRes

    @POST("api/v1/albums/{id}/media")
    suspend fun addToAlbum(@Path("id") albumId: String, @Body body: IdsReq): AddedRes

    @DELETE("api/v1/albums/{id}")
    suspend fun deleteAlbum(@Path("id") albumId: String): Response<Unit>

    @GET("api/v1/memory-videos")
    suspend fun memoryVideos(): MemoryVideosRes

    @POST("api/v1/memory-videos")
    suspend fun createMemoryVideo(@Body body: MonthReq): MemoryVideo

    @GET("api/v1/memories")
    suspend fun memories(): MemoriesRes

    @GET("api/v1/cleanup/{kind}")
    suspend fun cleanup(@Path("kind") kind: String, @Query("q") query: String? = null): SearchRes

    @POST("api/v1/cleanup/{kind}/keep")
    suspend fun cleanupKeep(@Path("kind") kind: String, @Body body: IdsReq): Response<Unit>

    @GET("api/v1/trash")
    suspend fun trashList(): SearchRes

    @POST("api/v1/media/restore")
    suspend fun restore(@Body body: IdsReq): Response<Unit>

    @GET("api/v1/categories")
    suspend fun categories(): CategoriesRes

    @POST("api/v1/media/{id}/enhance")
    suspend fun enhance(@Path("id") mediaId: String, @Body body: EnhanceReq): Response<Unit>

    @GET("api/v1/family")
    suspend fun family(): FamilyRes

    @POST("api/v1/family/members")
    suspend fun addMember(@Body body: MemberCreate): Response<Unit>

    @GET("api/v1/family/members/{id}/sharing")
    suspend fun sharing(@Path("id") memberId: String): Sharing

    @PUT("api/v1/family/members/{id}/sharing")
    suspend fun putSharing(@Path("id") memberId: String, @Body body: Sharing): Sharing

    @PATCH("api/v1/family/members/{id}")
    suspend fun patchMember(@Path("id") memberId: String, @Body body: MemberPatch): Response<Unit>
}

@Serializable data class Social(val liked: Boolean, val likes: List<String>, val comments: List<Comment>)
@Serializable
data class Comment(val id: String, val body: String, val author: String, val mine: Boolean, @SerialName("created_at") val createdAt: String)
@Serializable data class LikeReq(val liked: Boolean)
@Serializable data class CommentReq(val body: String)

@Serializable
data class Category(val name: String, @SerialName("media_count") val mediaCount: Int, @SerialName("cover_id") val coverId: String)
@Serializable data class CategoriesRes(val categories: List<Category>)
@Serializable data class EnhanceReq(val mode: String)

@Serializable
data class Member(
    val id: String,
    val email: String,
    @SerialName("display_name") val displayName: String? = null,
    val role: String? = null,
    val me: Boolean = false,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    val restricted: Boolean = false,
)

@Serializable data class FamilyRes(val members: List<Member>, val roles: List<String>, val gemini: Boolean = false)

@Serializable
data class MemberCreate(
    val email: String,
    val password: String,
    @SerialName("display_name") val displayName: String,
    val role: String,
)

/** Accès limité : le membre ne voit que ses médias + ceux qui suivent une des règles (dates « AAAA-MM-JJ »). */
@Serializable data class Sharing(val restricted: Boolean, val rules: List<ShareRule> = emptyList())
@Serializable
data class ShareRule(
    @SerialName("date_from") val dateFrom: String? = null,
    @SerialName("date_to") val dateTo: String? = null,
    val people: List<String> = emptyList(),
    val albums: List<String> = emptyList(),
)

@Serializable data class MemberPatch(@SerialName("display_name") val displayName: String?, val role: String?)

@Serializable data class IdsReq(val ids: List<String>)
@Serializable data class TrashRes(val trashed: Int)
@Serializable data class AddedRes(val added: Int)
@Serializable data class FamilyPeopleRes(
    @SerialName("person_ids") val personIds: List<String>,
    @SerialName("pet_ids") val petIds: List<String> = emptyList(),
    @SerialName("from_years") val fromYears: Map<String, Int> = emptyMap(),
)
@Serializable data class PetIdReq(@SerialName("pet_id") val petId: String, @SerialName("from_year") val fromYear: Int? = null)
@Serializable data class Pet(val id: String, val name: String, @SerialName("cover_id") val coverId: String? = null)
@Serializable data class PetsRes(val pets: List<Pet>)
@Serializable data class PetCreate(val name: String, val ids: List<String>)
@Serializable data class PersonReq(@SerialName("person_id") val personId: String, @SerialName("from_year") val fromYear: Int? = null)

@Serializable
data class MediaDetails(
    val id: String,
    @SerialName("taken_at") val takenAt: String,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    @SerialName("camera_make") val cameraMake: String? = null,
    @SerialName("camera_model") val cameraModel: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val city: String? = null,
    val admin1: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    val description: String? = null,
    @SerialName("ocr_text") val ocrText: String? = null,
    val tags: List<String> = emptyList(),
    val people: List<PersonRef> = emptyList(),
    @SerialName("uploaded_by") val uploadedBy: String? = null,
)

@Serializable data class PersonRef(val id: String, val name: String? = null, @SerialName("face_id") val faceId: String? = null)
@Serializable data class FaceAssign(@SerialName("person_id") val personId: String? = null, val name: String? = null)

@Serializable
data class Person(
    val id: String,
    val name: String? = null,
    @SerialName("media_count") val mediaCount: Int,
    @SerialName("cover_face_id") val coverFaceId: String,
)

@Serializable data class PeopleRes(val people: List<Person>)
@Serializable data class PersonPatch(val name: String?, val merge: Boolean = false)
@Serializable data class PersonPatchRes(val id: String, val merged: Boolean, val batch: String? = null)
@Serializable data class MergeReq(val into: String)
@Serializable data class FacesMove(
    @SerialName("media_ids") val mediaIds: List<String>,
    @SerialName("to_person_id") val toPersonId: String? = null,
    val name: String? = null,
)
@Serializable data class FacesMoveRes(val moved: Int, val batch: String? = null, @SerialName("to_person_id") val toPersonId: String? = null)
@Serializable data class ReviewItem(
    val kind: String,
    @SerialName("face_id") val faceId: String? = null,
    @SerialName("media_id") val mediaId: String,
    @SerialName("subject_id") val subjectId: String,
    val name: String,
)
@Serializable data class ReviewRes(val items: List<ReviewItem>)
@Serializable data class ReviewAnswer(
    val kind: String,
    @SerialName("subject_id") val subjectId: String,
    @SerialName("media_id") val mediaId: String,
    @SerialName("face_id") val faceId: String? = null,
    val yes: Boolean,
)

@Serializable
data class Place(
    val city: String,
    @SerialName("country_code") val countryCode: String? = null,
    @SerialName("media_count") val mediaCount: Int,
    @SerialName("cover_id") val coverId: String,
)

@Serializable data class PlacesRes(val places: List<Place>)

@Serializable
data class Album(
    val id: String,
    val title: String,
    @SerialName("media_count") val mediaCount: Int,
    @SerialName("cover_id") val coverId: String? = null,
)

@Serializable data class AlbumsRes(val albums: List<Album>)
@Serializable data class AlbumCreate(val title: String, @SerialName("media_ids") val mediaIds: List<String> = emptyList())
@Serializable data class AlbumCreateRes(val id: String, val added: Int)

@Serializable data class MemoryDto(@SerialName("years_ago") val yearsAgo: Int, val items: List<RemoteMedia>)
@Serializable data class MemoriesRes(val memories: List<MemoryDto>)

@Serializable data class LoginReq(val email: String, val password: String)
@Serializable data class LoginRes(@SerialName("access_token") val accessToken: String)

@Serializable data class CheckHashesReq(val hashes: List<String>)
@Serializable data class CheckHashesRes(val existing: Map<String, String>)

@Serializable
data class UploadInitReq(
    val sha256: String,
    val size: Long,
    val filename: String,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("taken_at") val takenAt: String? = null,
)

@Serializable
data class UploadInitRes(
    val status: String, // "exists" | "upload"
    @SerialName("media_id") val mediaId: String? = null,
    @SerialName("upload_id") val uploadId: String? = null,
    val offset: Long = 0,
    @SerialName("chunk_max") val chunkMax: Long = 0,
)

@Serializable data class OffsetRes(val offset: Long)
@Serializable data class CompleteRes(val status: String, @SerialName("media_id") val mediaId: String? = null)

@Serializable
data class RemoteMedia(
    val id: String,
    val sha256: String,
    val kind: String,
    @SerialName("taken_at") val takenAt: String,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    val score: Double? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("owner_id") val ownerId: String? = null,
    val suggested: Boolean = false, // nettoyage : présélectionné pour la corbeille
)

@Serializable data class TimelinePage(val items: List<RemoteMedia>, @SerialName("next_cursor") val nextCursor: String? = null)
@Serializable data class SearchRes(val items: List<RemoteMedia>)

@Serializable
data class MemoryVideo(
    val id: String,
    val month: String, // "2026-10"
    val status: String, // queued, running, ready, empty, error
    @SerialName("cover_media_id") val coverMediaId: String? = null,
    @SerialName("duration_s") val durationS: Double? = null,
    @SerialName("media_count") val mediaCount: Int = 0,
)

@Serializable data class MemoryVideosRes(val videos: List<MemoryVideo>)
@Serializable data class MonthReq(val month: String)
