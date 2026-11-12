package app.photos.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import app.photos.R
import app.photos.data.LocalMedia
import app.photos.data.SyncState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.photos.data.Album
import app.photos.data.Category
import app.photos.data.EnhanceReq
import app.photos.data.FamilyRes
import app.photos.data.MemberCreate
import app.photos.data.MemberPatch
import app.photos.data.AlbumCreate
import app.photos.data.IdsReq
import app.photos.data.LocalMediaDao
import app.photos.data.LoginReq
import app.photos.data.MediaDetails
import app.photos.data.Person
import app.photos.data.PersonPatch
import app.photos.data.PhotosApi
import app.photos.data.Place
import app.photos.data.RemoteMedia
import app.photos.data.SettingsRepository
import app.photos.data.SyncSettings
import app.photos.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val api: PhotosApi,
    private val settings: SettingsRepository,
    private val dao: LocalMediaDao,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val syncSettings: StateFlow<SyncSettings?> = settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val zoom = MutableStateFlow(Zoom.DAY)
    val mosaic = MutableStateFlow(true)
    val familyMedia = MutableStateFlow<List<GridItem>?>(null)

    /** Message avec bouton « Annuler » (suppression, fusion, visages déplacés). */
    data class Undo(val message: String, val run: suspend () -> Unit)
    val undo = MutableStateFlow<Undo?>(null)
    val familyPeople = MutableStateFlow<Set<String>>(emptySet())
    val familyPets = MutableStateFlow<Set<String>>(emptySet())
    val familyFromYears = MutableStateFlow<Map<String, Int>>(emptyMap())
    val pets = MutableStateFlow<List<app.photos.data.Pet>>(emptyList())
    val error = MutableStateFlow<String?>(null)
    val searchQuery = MutableStateFlow("") // survit aux changements d'onglet, comme les résultats
    val searchResults = MutableStateFlow<List<GridItem>?>(null) // null = pas de recherche en cours
    val searching = MutableStateFlow(false)
    val people = MutableStateFlow<List<Person>>(emptyList())
    val places = MutableStateFlow<List<Place>>(emptyList())
    val albums = MutableStateFlow<List<Album>>(emptyList())
    val memories = MutableStateFlow<List<Memory>>(emptyList())
    val memoryVideos = MutableStateFlow<List<app.photos.data.MemoryVideo>>(emptyList())
    val categories = MutableStateFlow<List<Category>>(emptyList())
    val family = MutableStateFlow<FamilyRes?>(null)
    /** Incrémenté après chaque mise à la corbeille / restauration : les écrans Nettoyer et Corbeille se rechargent. */
    val trashVersion = MutableStateFlow(0)

    private val remote = MutableStateFlow<List<RemoteMedia>>(emptyList())
    private var cursor: String? = null
    private var endReached = false
    private var loading = false

    private val local = dao.observeAll().shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /** Sur le téléphone ET confirmés sur le serveur : supprimables du téléphone sans rien perdre. */
    val freeable: StateFlow<List<LocalMedia>> = local.map { list ->
        list.filter { it.state == SyncState.SYNCED && it.serverId != null && isMediaStoreUri(Uri.parse(it.uri)) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val rows: StateFlow<List<GridRow>> = combine(remote, local, zoom, mosaic) { r, l, z, m ->
        sections(merge(r, l), z, mosaic = m && z == Zoom.DAY)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** (mois, index de sa première ligne) pour le sélecteur d'année et les onglets de mois. */
    val anchors = rows.map(::monthAnchors).flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val backup: StateFlow<BackupStatus?> = local.map(::backupStatus).flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Appel réseau : les erreurs deviennent un message (snackbar) ; 401 => retour à l'écran de connexion. */
    private suspend fun <T> call(block: suspend () -> T): T? = try {
        block()
    } catch (e: IOException) {
        error.value = context.getString(R.string.error_unreachable)
        null
    } catch (e: HttpException) {
        when (e.code()) {
            401 -> settings.logout()
            503 -> error.value = context.getString(R.string.error_search_starting)
            else -> error.value = context.getString(R.string.error_server, e.code())
        }
        null
    }

    fun loadMore() {
        if (loading || endReached) return
        loading = true
        // Tout l'historique d'un coup (quelques milliers de lignes légères) : le sélecteur d'année voit
        // toutes les années dès l'ouverture. shortcut: tout en mémoire, paginer si > ~50 000 médias.
        viewModelScope.launch {
            var wait = 2_000L
            while (!endReached) {
                val page = try {
                    api.timeline(cursor, limit = 500)
                } catch (e: IOException) {
                    // Serveur injoignable (redémarrage, pas de réseau) : on réessaie seul, sinon l'écran reste vide
                    if (wait == 2_000L) error.value = context.getString(R.string.error_unreachable) // un seul message
                    delay(wait)
                    wait = (wait * 2).coerceAtMost(60_000)
                    continue
                } catch (e: HttpException) {
                    call<Unit> { throw e } // même traitement que les autres appels (401 => connexion…)
                    break
                }
                wait = 2_000L
                remote.update { it + page.items }
                cursor = page.nextCursor
                endReached = page.nextCursor == null
            }
            loading = false
        }
    }

    fun refresh() {
        if (loading) return
        cursor = null
        endReached = false
        remote.value = emptyList()
        loadMore()
        loadMemories()
    }

    fun search(query: String) {
        if (query.isBlank()) {
            searchResults.value = null
            return
        }
        viewModelScope.launch {
            searching.value = true
            searchResults.value = call { api.search(query.trim()) }?.items?.map(::remoteItem) ?: searchResults.value
            searching.value = false
        }
    }

    /** Corrige un visage : personne existante, nouveau nom, ou ni l'un ni l'autre = retirer. */
    suspend fun assignFace(faceId: String, personId: String?, name: String?): Boolean =
        (call { api.assignFace(faceId, app.photos.data.FaceAssign(personId, name)) } != null).also { if (it) loadExplore() }

    suspend fun social(remoteId: String) = call { api.social(remoteId) }
    suspend fun setLike(remoteId: String, liked: Boolean) = call { api.setLike(remoteId, app.photos.data.LikeReq(liked)) }
    suspend fun addComment(remoteId: String, body: String) = call { api.addComment(remoteId, app.photos.data.CommentReq(body)) }
    suspend fun deleteComment(commentId: String) = call { api.deleteComment(commentId) }

    suspend fun sharing(memberId: String) = call { api.sharing(memberId) }
    suspend fun putSharing(memberId: String, body: app.photos.data.Sharing) =
        call { api.putSharing(memberId, body) }?.also { loadFamily() }

    fun loadExplore() = viewModelScope.launch {
        call { api.people() }?.let { people.value = it.people }
        call { api.pets() }?.let { pets.value = it.pets }
        call { api.places() }?.let { places.value = it.places }
        call { api.categories() }?.let { categories.value = it.categories }
    }

    fun loadFamilyMedia() = viewModelScope.launch {
        // Premier écran tout de suite (les 600 plus récentes), puis tout Famille (~10 000 photos, 2 s)
        if (familyMedia.value == null) call { api.familyMedia(600) }?.let { r -> familyMedia.value = r.items.map(::remoteItem) }
        call { api.familyMedia() }?.let { r -> familyMedia.value = r.items.map(::remoteItem) }
        call { api.familyPeople() }?.let {
            familyPeople.value = it.personIds.toSet(); familyPets.value = it.petIds.toSet(); familyFromYears.value = it.fromYears
        }
    }

    /** Personne ou animal déjà coché : seulement ses photos à partir de [year] (null = toutes les années). */
    fun setFamilyFromYear(id: String, isPet: Boolean, year: Int?) = viewModelScope.launch {
        if (isPet) call { api.familyPetsAdd(app.photos.data.PetIdReq(id, year)) }
        else call { api.familyPeopleAdd(app.photos.data.PersonReq(id, year)) }
        loadFamilyMedia()
    }

    /** Animal partagé : toutes ses photos, y compris les futures, apparaissent dans Famille. */
    fun toggleFamilyPet(petId: String) = viewModelScope.launch {
        if (petId in familyPets.value) call { api.familyPetsRemove(petId) }
        else call { api.familyPetsAdd(app.photos.data.PetIdReq(petId)) }
        loadFamilyMedia()
    }

    /** Par visage : toutes les photos de la personne, y compris les futures, apparaissent dans Famille. */
    fun toggleFamilyPerson(personId: String) = viewModelScope.launch {
        if (personId in familyPeople.value) call { api.familyPeopleRemove(personId) }
        else call { api.familyPeopleAdd(app.photos.data.PersonReq(personId)) }
        loadFamilyMedia()
    }

    /** Onglet Famille : chacun partage ce qu'il veut ; seuls les éléments déjà sauvegardés peuvent l'être. */
    fun shareToFamily(items: List<GridItem>) = viewModelScope.launch {
        val ids = items.mapNotNull { it.remoteId }
        val added = ids.chunked(500).sumOf { batch -> call { api.familyAdd(IdsReq(batch)) }?.added ?: 0 }
        error.value = context.resources.getQuantityString(R.plurals.items_shared_family, added, added)
        loadFamilyMedia()
    }

    /** Le serveur ne retire que ce que l'utilisateur a lui-même partagé (ou tout, pour l'administrateur). */
    fun removeFromFamily(items: List<GridItem>) = viewModelScope.launch {
        items.mapNotNull { it.remoteId }.chunked(500).forEach { batch -> call { api.familyRemove(IdsReq(batch)) } }
        loadFamilyMedia()
    }

    suspend fun cleanup(kind: String, query: String?): List<Pair<GridItem, Boolean>> =
        call { api.cleanup(kind, query) }?.items?.map { remoteItem(it) to it.suggested } ?: emptyList()

    /** Photos décochées au nettoyage : le serveur ne les proposera plus pour ce critère. */
    fun keepCleanup(kind: String, items: List<GridItem>) = viewModelScope.launch {
        items.mapNotNull { it.remoteId }.chunked(500).forEach { batch -> call { api.cleanupKeep(kind, IdsReq(batch)) } }
        trashVersion.update { it + 1 }
    }

    suspend fun trashList(): List<GridItem> = call { api.trashList() }?.items?.map(::remoteItem) ?: emptyList()

    fun restore(items: List<GridItem>) = viewModelScope.launch {
        val ids = items.mapNotNull { it.remoteId }
        ids.chunked(500).forEach { batch -> call { api.restore(IdsReq(batch)) } }
        error.value = context.resources.getQuantityString(R.plurals.items_restored, ids.size, ids.size)
        trashVersion.update { it + 1 }
        refresh()
    }

    fun loadFamily() = viewModelScope.launch { call { api.family() }?.let { family.value = it } }

    /** Crée le compte d'un membre de la famille ; il se connecte ensuite sur son propre téléphone. */
    fun addMember(email: String, password: String, name: String, role: String) = viewModelScope.launch {
        try {
            val r = api.addMember(MemberCreate(email.trim(), password, name.trim(), role))
            error.value = when {
                r.isSuccessful -> context.getString(R.string.member_added, name)
                r.code() == 409 -> context.getString(R.string.error_account_exists)
                r.code() == 422 -> context.getString(R.string.error_check_member_fields)
                else -> context.getString(R.string.error_server, r.code())
            }
        } catch (e: IOException) {
            error.value = context.getString(R.string.error_unreachable)
        }
        loadFamily()
    }

    fun updateMember(id: String, name: String, role: String) = viewModelScope.launch {
        call { api.patchMember(id, MemberPatch(name.trim().ifBlank { null }, role)) }
        loadFamily()
    }

    /** Retouche : nouvelle copie créée par le serveur, l'original reste intact. */
    fun enhance(item: GridItem, mode: String) = viewModelScope.launch {
        val id = item.remoteId ?: run { error.value = context.getString(R.string.error_not_backed_up); return@launch }
        val r = call { api.enhance(id, EnhanceReq(mode)) } ?: return@launch
        error.value = if (r.isSuccessful) context.getString(R.string.enhance_started)
        else context.getString(R.string.enhance_failed, r.code())
    }

    fun loadAlbums() = viewModelScope.launch { call { api.albums() }?.let { albums.value = it.albums } }

    fun loadMemoryVideos() = viewModelScope.launch { call { api.memoryVideos() }?.let { memoryVideos.value = it.videos } }

    fun createMemoryVideo(month: String) = viewModelScope.launch {
        call { api.createMemoryVideo(app.photos.data.MonthReq(month)) }?.let { v ->
            memoryVideos.update { list -> (listOf(v) + list.filter { it.id != v.id }).sortedByDescending { it.month } }
        }
    }

    private fun loadMemories() = viewModelScope.launch {
        call { api.memories() }?.let { res ->
            memories.value = res.memories.map { m -> Memory(m.yearsAgo, m.items.map(::remoteItem)) }
        }
    }

    /** [limit] : premier écran rapide (les plus récentes), puis tout (null). */
    suspend fun collection(c: Collection, limit: Int? = null): List<GridItem> = call {
        when (c) {
            is Collection.Person -> api.query(personId = c.id, limit = limit)
            is Collection.Place -> api.query(city = c.city, countryCode = c.countryCode, limit = limit)
            is Collection.Album -> api.query(albumId = c.id, limit = limit)
            is Collection.Category -> api.query(tag = c.title, limit = limit)
            is Collection.Pet -> api.query(petId = c.id, limit = limit)
        }
    }?.items?.map(::remoteItem) ?: emptyList()

    suspend fun details(remoteId: String): MediaDetails? = call { api.details(remoteId) }

    /** Corbeille serveur ; la suppression sur le téléphone passe par la boîte de dialogue système (cf. Actions.kt). */
    fun trashRemote(items: List<GridItem>) = viewModelScope.launch {
        val ids = items.mapNotNull { it.remoteId }
        if (ids.isEmpty()) return@launch
        ids.chunked(500).forEach { batch -> call { api.trash(IdsReq(batch)) } }
        remote.update { list -> list.filter { it.id !in ids } }
        familyMedia.update { list -> list?.filter { it.remoteId !in ids } }
        searchResults.update { list -> list?.filter { it.remoteId !in ids } }
        trashVersion.update { it + 1 }
        undo.value = Undo(context.resources.getQuantityString(R.plurals.items_trashed, ids.size, ids.size)) {
            ids.chunked(500).forEach { batch -> call { api.restore(IdsReq(batch)) } }
            trashVersion.update { it + 1 }
            refresh()
        }
        memories.update { list -> list.map { m -> m.copy(items = m.items.filter { it.remoteId !in ids }) }.filter { it.items.isNotEmpty() } }
    }

    /** Fichiers supprimés du téléphone : on les retire tout de suite de l'index local. */
    fun forgetLocal(localIds: List<Long>) = viewModelScope.launch { localIds.chunked(500).forEach { dao.deleteAll(it) } }

    fun addToAlbum(albumId: String, items: List<GridItem>) = viewModelScope.launch {
        val ids = items.mapNotNull { it.remoteId }
        call { api.addToAlbum(albumId, IdsReq(ids)) }?.let { error.value = context.resources.getQuantityString(R.plurals.items_added, it.added, it.added) }
        loadAlbums()
    }

    fun createAlbum(title: String, items: List<GridItem> = emptyList()) = viewModelScope.launch {
        call { api.createAlbum(AlbumCreate(title.trim(), items.mapNotNull { it.remoteId })) }
            ?.let { error.value = context.getString(R.string.album_created, title.trim()) }
        loadAlbums()
    }

    /** Animal nommé à partir de photos d'exemple ; un nom déjà utilisé ajoute ces photos au même animal. */
    fun namePet(name: String, items: List<GridItem>) = viewModelScope.launch {
        val ids = items.mapNotNull { it.remoteId }.take(50)
        if (ids.isEmpty()) return@launch
        call { api.namePet(app.photos.data.PetCreate(name.trim(), ids)) }
            ?.let { error.value = context.getString(R.string.pet_named, it.name) }
        call { api.pets() }?.let { pets.value = it.pets }
    }

    fun deletePet(petId: String) = viewModelScope.launch {
        call { api.deletePet(petId) }
        call { api.pets() }?.let { pets.value = it.pets }
    }

    fun deleteAlbum(albumId: String) = viewModelScope.launch {
        call { api.deleteAlbum(albumId) }
        loadAlbums()
    }

    /** Renvoie l'id final : nommer comme une personne existante fusionne les deux. */
    /** Résultat d'un renommage : [twinId] non nul = ce nom est déjà pris, l'app demande avant de fusionner. */
    data class Rename(val id: String?, val twinId: String? = null)

    suspend fun renamePerson(personId: String, name: String, merge: Boolean = false): Rename = try {
        api.renamePerson(personId, PersonPatch(name.ifBlank { null }, merge)).let { r ->
            loadExplore()
            r.batch?.let { offerFaceUndo(context.getString(R.string.merged_into, name), it) }
            Rename(r.id)
        }
    } catch (e: HttpException) {
        val twin = if (e.code() == 409) e.response()?.errorBody()?.string()?.let { Regex("\"twin\":\"([0-9a-f-]+)\"").find(it)?.groupValues?.get(1) } else null
        if (twin == null) error.value = context.getString(R.string.error_server, e.code())
        Rename(null, twin)
    } catch (e: IOException) {
        error.value = context.getString(R.string.error_unreachable)
        Rename(null)
    }

    private fun offerFaceUndo(message: String, batch: String) {
        undo.value = Undo(message) { call { api.undoFaces(batch) }; loadExplore(); trashVersion.update { it + 1 } }
    }

    /** Fusion explicite (après confirmation) ; renvoie la personne qui reste. */
    suspend fun mergePerson(personId: String, into: String, intoName: String): String? =
        call { api.mergePerson(personId, app.photos.data.MergeReq(into)) }?.let { r ->
            loadExplore()
            r.batch?.let { offerFaceUndo(context.getString(R.string.merged_into, intoName), it) }
            r.id
        }

    /** Sélection sur la page d'une personne : « ce n'est pas elle » (toPersonId et name nuls) ou « c'est … ». */
    fun moveFaces(personId: String, items: List<GridItem>, toPersonId: String?, name: String?) = viewModelScope.launch {
        val ids = items.mapNotNull { it.remoteId }
        val r = call { api.moveFaces(personId, app.photos.data.FacesMove(ids, toPersonId, name)) } ?: return@launch
        trashVersion.update { it + 1 }  // recharge la page de la personne
        loadExplore()
        r.batch?.let { offerFaceUndo(context.resources.getQuantityString(R.plurals.faces_moved, r.moved, r.moved), it) }
    }

    suspend fun reviewQueue(): List<app.photos.data.ReviewItem> = call { api.review() }?.items ?: emptyList()

    fun answerReview(item: app.photos.data.ReviewItem, yes: Boolean) = viewModelScope.launch {
        call { api.answerReview(app.photos.data.ReviewAnswer(item.kind, item.subjectId, item.mediaId, item.faceId, yes)) }
    }

    /**
     * Adresse locale (192.168.x.x…) mémorisée d'une ancienne connexion : ne marche qu'en Wi-Fi à la maison.
     * On passe à l'adresse Internet, qui marche partout (même serveur, même compte, le jeton reste valable).
     */
    fun useInternetServer() = viewModelScope.launch {
        val host = settings.flow.first().serverUrl?.toHttpUrlOrNull()?.host ?: return@launch
        val local = host == "localhost" || host.startsWith("192.168.") || host.startsWith("10.") ||
            Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host)
        if (local) {
            settings.setLocalUrl(settings.flow.first().serverUrl!!) // gardée pour le Wi-Fi de la maison
            settings.setServerUrl(DEFAULT_SERVER)
        }
    }

    fun login(serverUrl: String, email: String, password: String) = viewModelScope.launch {
        try {
            settings.setServerUrl(serverUrl.trim().trimEnd('/'))
            settings.setToken(api.login(LoginReq(email.trim(), password)).accessToken)
            settings.setEmail(email.trim())
            refresh()
        } catch (e: HttpException) {
            error.value = if (e.code() == 401) context.getString(R.string.error_invalid_credentials)
            else context.getString(R.string.error_server, e.code())
        } catch (e: IOException) {
            error.value = context.getString(R.string.error_unreachable_detail, e.message)
        }
    }

    fun logout() = viewModelScope.launch { settings.logout() }
    fun setWifiOnly(value: Boolean) = viewModelScope.launch { settings.setWifiOnly(value) }
    fun setChargingOnly(value: Boolean) = viewModelScope.launch { settings.setChargingOnly(value) }
    fun setAutoBackup(value: Boolean) = viewModelScope.launch { settings.setAutoBackup(value) }
    fun freedSpace(items: List<LocalMedia>) {
        forgetLocal(items.map { it.mediaStoreId })
        // le serveur les a toutes : elles réapparaissent dans la grille comme « sur le serveur »
        refresh()
    }
    fun setUseLocal(value: Boolean) = viewModelScope.launch { settings.setUseLocal(value) }
    fun syncNow() = SyncScheduler.syncNow(context)
}

/** Id local négatif et stable pour un élément hors MediaStore (les ids MediaStore sont positifs). */
private fun syntheticId(key: String): Long =
    -(key.fold(1125899906842597L) { h, ch -> 31 * h + ch.code } and Long.MAX_VALUE) - 1
