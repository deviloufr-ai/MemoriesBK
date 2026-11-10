package app.photos

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.photos.data.SettingsRepository
import app.photos.sync.SyncScheduler
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class PhotosApp : Application(), Configuration.Provider, ImageLoaderFactory {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var okHttp: OkHttpClient

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    /**
     * Coil passe par l'OkHttpClient de l'API (hôte + jeton). Les miniatures serveur ont
     * `Cache-Control: immutable` : après le premier affichage, la grille se sert du cache disque sans réseau.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(okHttp)
        .components { add(VideoFrameDecoder.Factory()) } // vignettes des vidéos locales
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("thumbs")).maxSizeBytes(1L shl 30).build() }
        .crossfade(true)
        .build()

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_SYNC, getString(R.string.channel_sync), NotificationManager.IMPORTANCE_LOW),
        )
        // Une seule source de vérité pour la planification : chaque changement de réglage replanifie.
        MainScope().launch {
            settings.flow
                .distinctUntilChangedBy { listOf(it.wifiOnly, it.chargingOnly, it.token != null, it.autoBackup) }
                .collectIndexed { i, s ->
                    if (s.autoBackup) SyncScheduler.schedule(this@PhotosApp, s, replace = i > 0)
                    else SyncScheduler.cancelAuto(this@PhotosApp)
                }
        }
    }

    companion object {
        const val CHANNEL_SYNC = "sync"
    }
}
