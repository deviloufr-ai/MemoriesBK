package app.photos.ui

import androidx.compose.ui.res.stringResource
import app.photos.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.Elderly
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Face3
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.photos.data.FamilyRes
import app.photos.data.Member

private val ROLE_LABELS = mapOf(
    "papa" to R.string.role_papa, "maman" to R.string.role_maman, "enfant" to R.string.role_enfant,
    "grand-parent" to R.string.role_grand_parent, "autre" to R.string.role_autre,
)

private fun roleIcon(role: String?): ImageVector = when (role) {
    "papa" -> Icons.Outlined.Face
    "maman" -> Icons.Outlined.Face3
    "enfant" -> Icons.Outlined.ChildCare
    "grand-parent" -> Icons.Outlined.Elderly
    else -> Icons.Outlined.Person
}

/** Section « Famille » de l'onglet Gérer : membres, rôles, ajout d'un compte. */
fun LazyListScope.familySection(family: FamilyRes?, onEdit: (Member) -> Unit, onAdd: () -> Unit, onSharing: (Member) -> Unit) {
    val admin = family?.members?.any { it.me && it.isAdmin } == true
    item { SectionTitle(stringResource(R.string.section_family)) }
    item {
        Text(
            stringResource(R.string.family_help),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    family?.members?.forEach { m ->
        item(key = "member-${m.id}") {
            ListItem(
                leadingContent = { Icon(roleIcon(m.role), null) },
                headlineContent = { Text((m.displayName ?: m.email).let { if (m.me) stringResource(R.string.member_me, it) else it }) },
                supportingContent = {
                    Text(listOfNotNull(ROLE_LABELS[m.role]?.let { stringResource(it) }, m.email,
                        stringResource(R.string.admin).takeIf { m.isAdmin }, stringResource(R.string.limited_access).takeIf { m.restricted })
                        .joinToString(" • "))
                },
                trailingContent = if (admin && !m.me) {
                    { IconButton(onClick = { onSharing(m) }) { Icon(Icons.Outlined.Visibility, stringResource(R.string.sharing_button)) } }
                } else null,
                modifier = Modifier.clickable(onClickLabel = stringResource(R.string.edit)) { onEdit(m) },
            )
        }
    }
    if (admin) item {
        ListItem(
            leadingContent = { Icon(Icons.Outlined.PersonAdd, null, tint = MaterialTheme.colorScheme.primary) },
            headlineContent = { Text(stringResource(R.string.add_member), color = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.clickable(onClick = onAdd),
        )
    }
}

/**
 * Ajout ([member] null : e-mail + mot de passe demandés) ou modification du prénom et du rôle.
 * Le mot de passe est choisi ici et transmis au membre, qui se connecte avec sur son téléphone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemberDialog(
    member: Member?,
    roles: List<String>,
    onDismiss: () -> Unit,
    onSave: (name: String, role: String, email: String, password: String) -> Unit,
) {
    var name by remember { mutableStateOf(member?.displayName ?: "") }
    var role by remember { mutableStateOf(member?.role ?: "enfant") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val adding = member == null
    val valid = name.isNotBlank() && (!adding || (email.trim().length >= 3 && email.trim().none(Char::isWhitespace) && password.length >= 10))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (adding) stringResource(R.string.add_member) else stringResource(R.string.edit_member, "${member?.displayName ?: member?.email}")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.first_name)) }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    roles.forEach { r ->
                        FilterChip(selected = role == r, onClick = { role = r }, label = { Text(ROLE_LABELS[r]?.let { stringResource(it) } ?: r) })
                    }
                }
                if (adding) {
                    OutlinedTextField(email, { email = it }, label = { Text(stringResource(R.string.member_login_label)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.member_password_label)) },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(name, role, email, password) }) { Text(stringResource(if (adding) R.string.add else R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
