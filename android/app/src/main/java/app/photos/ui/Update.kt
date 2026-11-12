package app.photos.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import app.photos.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

// L'hôte est remplacé par ServerInterceptor (serveur local ou Internet)
private const val BASE = "http://server/"

private fun installedVersion(context: Context): Long =
    PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

private fun get(okHttp: OkHttpClient, path: String) =
    okHttp.newCall(Request.Builder().url(BASE + path).build()).execute().also {
        if (!it.isSuccessful) { it.close(); throw IOException("HTTP ${it.code}") }
    }

/**
 * Premier lancement : présentation des onglets. Après une mise à jour : les nouveautés, une seule fois.
 * Mémorisé localement (dernière version vue) ; rien n'est envoyé au serveur.
 */
@Composable
fun WelcomeOrWhatsNew() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    val installed = remember { installedVersion(context) }
    val seen = remember { prefs.getLong("seen_version", -1L) }
    var show by remember { mutableStateOf(seen < installed) }
    if (!show) return
    val welcome = seen < 0
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(if (welcome) R.string.welcome_title else R.string.whats_new_title)) },
        // Premier lancement de cette fonction : aussi les nouveautés (les utilisateurs actuels n'ont encore rien « vu »)
        text = {
            Text(if (welcome) stringResource(R.string.welcome_text) + "\n\n" + stringResource(R.string.whats_new_text)
            else stringResource(R.string.whats_new_text))
        },
        confirmButton = {
            TextButton(onClick = { prefs.edit().putLong("seen_version", installed).apply(); show = false }) {
                Text(stringResource(R.string.ok))
            }
        },
    )
}

/** Au lancement : si le serveur publie un APK plus récent, propose de l'installer. */
@Composable
fun UpdateChecker(okHttp: OkHttpClient, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var available by remember { mutableStateOf<Long?>(null) }
    var downloading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val latest = withContext(Dispatchers.IO) {
            runCatching { get(okHttp, "MemoriesBK.version").use { it.body!!.string().trim().toLong() } }.getOrNull()
        }
        if (latest != null && latest > installedVersion(context)) available = latest
    }

    if (available == null) return
    AlertDialog(
        onDismissRequest = { if (!downloading) available = null },
        title = { Text(stringResource(R.string.update_title)) },
        text = { Text(stringResource(if (downloading) R.string.update_downloading else R.string.update_text)) },
        confirmButton = {
            TextButton(enabled = !downloading, onClick = {
                // Android 8+ : autorisation « sources inconnues » par app, demandée une seule fois
                if (!context.packageManager.canRequestPackageInstalls()) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                    return@TextButton
                }
                downloading = true
                scope.launch {
                    try {
                        val apk = File(context.cacheDir, "updates/MemoriesBK.apk").apply { parentFile!!.mkdirs() }
                        withContext(Dispatchers.IO) {
                            get(okHttp, "MemoriesBK.apk").use { r -> apk.outputStream().use { r.body!!.byteStream().copyTo(it) } }
                        }
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
                        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                        available = null
                    } catch (e: IOException) {
                        onError(context.getString(R.string.update_failed, e.message))
                    } finally {
                        downloading = false
                    }
                }
            }) { Text(stringResource(R.string.update_install)) }
        },
        dismissButton = { TextButton(enabled = !downloading, onClick = { available = null }) { Text(stringResource(R.string.update_later)) } },
    )
}
