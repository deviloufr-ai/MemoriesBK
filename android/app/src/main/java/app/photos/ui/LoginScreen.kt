package app.photos.ui

import androidx.compose.ui.res.stringResource
import app.photos.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** Serveur familial, joignable depuis Internet (Tailscale Funnel) : rien à saisir pour les utilisateurs. */
const val DEFAULT_SERVER = "https://memoriesbk.tail03b1de.ts.net"

@Composable
fun LoginScreen(vm: GalleryViewModel) {
    val error by vm.error.collectAsState()
    var server by rememberSaveable { mutableStateOf(vm.syncSettings.value?.serverUrl ?: DEFAULT_SERVER) }
    var custom by rememberSaveable { mutableStateOf(server != DEFAULT_SERVER) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("MemoriesBK", style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.login_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            email, { email = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.login_id)) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), // pas de majuscule auto
        )
        OutlinedTextField(
            password, { password = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.password)) },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        // Autre serveur : caché par défaut, utile seulement pour un serveur de test ou une autre installation
        if (custom) OutlinedTextField(
            server, { server = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.server_address)) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = { vm.error.value = null; vm.login(server, email, password) },
            // http:// n'aboutit qu'en debug vers le réseau local : la config réseau release refuse le clair
            enabled = server.startsWith("http") && email.isNotBlank() && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.sign_in)) }
        if (!custom) TextButton(onClick = { custom = true }) { Text(stringResource(R.string.use_other_server)) }
    }
}
