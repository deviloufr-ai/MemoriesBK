package app.photos.sync

import android.content.Context
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.photos.data.SyncSettings
import java.time.Duration

/**
 * Deux déclencheurs pour le même worker :
 *  - observateur MediaStore : tâche JobScheduler à content-URI trigger. Contrairement à un ContentObserver,
 *    il fonctionne app fermée, le système réveille le process quand une photo arrive ;
 *  - passe périodique (6 h) : filet de sécurité si un déclenchement est perdu.
 * Les deux respectent Doze : WorkManager regroupe l'exécution dans les fenêtres de maintenance,
 * sans exemption d'optimisation batterie.
 */
object SyncScheduler {
    const val TAG_OBSERVER = "media-observer"
    const val TAG_MANUAL = "media-manual"
    private const val OBSERVER = "media-observer"
    private const val PERIODIC = "media-periodic"

    /** [replace] = réglages modifiés : on remplace les tâches en attente par leurs nouvelles contraintes. */
    fun schedule(context: Context, s: SyncSettings, replace: Boolean) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<MediaSyncWorker>(Duration.ofHours(6))
                .setConstraints(constraints(s).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(1))
                .build(),
        )
        // KEEP au démarrage : le process est peut-être lancé justement pour exécuter l'observateur.
        enqueueObserver(context, s, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP)
    }

    /** Sauvegarde automatique désactivée : plus de déclenchement sur nouvelle photo ni de passe périodique. */
    fun cancelAuto(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(OBSERVER)
            cancelUniqueWork(PERIODIC)
        }
    }

    /** Bouton "Sauvegarder maintenant" : n'importe quel réseau, sans attendre la charge. */
    fun syncNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "media-manual",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MediaSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(TAG_MANUAL)
                .build(),
        )
    }

    /** Appelé par l'observateur à la fin de sa passe : la nouvelle tâche s'enchaîne après la courante. */
    fun rearmObserver(context: Context, s: SyncSettings) =
        enqueueObserver(context, s, ExistingWorkPolicy.APPEND_OR_REPLACE)

    private fun enqueueObserver(context: Context, s: SyncSettings, policy: ExistingWorkPolicy) {
        val c = constraints(s)
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(Duration.ofSeconds(30)) // regroupe une rafale de photos
            .setTriggerContentMaxDelay(Duration.ofMinutes(2))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            OBSERVER,
            policy,
            OneTimeWorkRequestBuilder<MediaSyncWorker>()
                .setConstraints(c)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(1))
                .addTag(TAG_OBSERVER)
                .build(),
        )
    }

    private fun constraints(s: SyncSettings) = Constraints.Builder()
        .setRequiredNetworkType(if (s.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .setRequiresCharging(s.chargingOnly)
}
