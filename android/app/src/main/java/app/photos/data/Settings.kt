package app.photos.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

data class SyncSettings(
    val serverUrl: String?,
    val token: String?,
    val wifiOnly: Boolean,
    val chargingOnly: Boolean,
    val email: String? = null,
    val autoBackup: Boolean = true,
    val localUrl: String? = null, // adresse du serveur sur le Wi-Fi de la maison (sans passer par Internet)
    val useLocal: Boolean = true,
)

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext context: Context) {
    private val store = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

    // shortcut: jeton en clair dans DataStore (sandbox de l'app) ; le chiffrer via Android Keystore si l'appareil est partagé.
    val flow: Flow<SyncSettings> = store.data.map {
        SyncSettings(
            serverUrl = it[SERVER_URL],
            token = it[TOKEN],
            wifiOnly = it[WIFI_ONLY] ?: true,
            chargingOnly = it[CHARGING_ONLY] ?: false,
            email = it[EMAIL],
            autoBackup = it[AUTO_BACKUP] ?: true,
            localUrl = it[LOCAL_URL] ?: DEFAULT_LOCAL_SERVER,
            useLocal = it[USE_LOCAL] ?: true,
        )
    }

    suspend fun current(): SyncSettings = flow.first()

    suspend fun setServerUrl(url: String) = store.edit { it[SERVER_URL] = url }
    suspend fun setToken(token: String) = store.edit { it[TOKEN] = token }
    suspend fun setEmail(email: String) = store.edit { it[EMAIL] = email }
    suspend fun logout() = store.edit { it.remove(TOKEN) }
    suspend fun setWifiOnly(value: Boolean) = store.edit { it[WIFI_ONLY] = value }
    suspend fun setChargingOnly(value: Boolean) = store.edit { it[CHARGING_ONLY] = value }
    suspend fun setAutoBackup(value: Boolean) = store.edit { it[AUTO_BACKUP] = value }
    suspend fun setLocalUrl(url: String) = store.edit { it[LOCAL_URL] = url }
    suspend fun setUseLocal(value: Boolean) = store.edit { it[USE_LOCAL] = value }

    private companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val TOKEN = stringPreferencesKey("token")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val CHARGING_ONLY = booleanPreferencesKey("charging_only")
        val EMAIL = stringPreferencesKey("email")
        val AUTO_BACKUP = booleanPreferencesKey("auto_backup")
        val LOCAL_URL = stringPreferencesKey("local_url")
        val USE_LOCAL = booleanPreferencesKey("use_local")
    }
}

/** Hôte factice des URL Retrofit/Coil/ExoPlayer, remplacé à chaque requête par [ServerInterceptor]. */
const val SERVER_PLACEHOLDER = "http://server.invalid"

/** Serveur sur le réseau de la maison : pas de limite de débit du tunnel Internet (Tailscale Funnel). */
const val DEFAULT_LOCAL_SERVER = "http://192.168.1.41:8080"

/**
 * Remplace l'hôte factice par l'adresse du serveur et ajoute le jeton.
 * Deux adresses : locale si le serveur y répond (Wi-Fi de la maison), sinon Internet. Le résultat du test
 * est gardé 30 s ; une connexion locale qui échoue bascule tout de suite sur Internet.
 */
@Singleton
class ServerInterceptor @Inject constructor(private val settings: SettingsRepository) : Interceptor {
    @Volatile private var localOk = false
    @Volatile private var checkedAt = 0L

    private fun localReachable(url: HttpUrl): Boolean {
        val now = System.currentTimeMillis()
        if (now - checkedAt > 30_000) {
            localOk = runCatching { Socket().use { it.connect(InetSocketAddress(url.host, url.port), 800) } }.isSuccess
            checkedAt = now
        }
        return localOk
    }

    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val s = runBlocking { settings.current() } // DataStore garde la valeur en mémoire après la 1re lecture
        // shortcut: seul schéma/hôte/port sont repris ; un serveur sous un sous-chemin (/photos) n'est pas géré.
        val internet = s.serverUrl?.toHttpUrlOrNull() ?: throw IOException("serveur non configuré")
        val local = s.localUrl?.toHttpUrlOrNull()?.takeIf { s.useLocal }
        fun send(base: HttpUrl): okhttp3.Response {
            val url = chain.request().url.newBuilder().scheme(base.scheme).host(base.host).port(base.port).build()
            val request = chain.request().newBuilder().url(url)
            s.token?.let { request.header("Authorization", "Bearer $it") }
            return chain.proceed(request.build())
        }
        if (local != null && localReachable(local)) {
            try {
                return send(local)
            } catch (e: ConnectException) { // sorti de la maison entre deux tests : rien n'a été envoyé, on réessaie
                localOk = false
            } catch (e: NoRouteToHostException) {
                localOk = false
            }
        }
        return send(internet)
    }
}
