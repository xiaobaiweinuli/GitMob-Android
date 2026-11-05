package com.gitmob.app.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gitmob.app.R
import com.gitmob.app.data.model.AccountAuthMethod
import com.gitmob.app.data.model.GitHubAccount

@Composable
internal fun AccountIdentity(name: String?, login: String, avatarUrl: String?, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            AsyncImage(model = avatarUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name?.takeIf { it.isNotBlank() } ?: login, style = MaterialTheme.typography.titleMedium)
            Text("@$login", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun AccountOption(account: GitHubAccount, selected: Boolean, switching: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 76.dp).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AccountIdentity(account.name, account.login, account.avatarUrl, Modifier.fillMaxWidth())
            Text(
                stringResource(when {
                    account.needsReauthentication -> R.string.accounts_reauthenticate
                    account.authMethod == AccountAuthMethod.LEGACY -> R.string.accounts_legacy
                    account.authMethod == AccountAuthMethod.DEVICE_FLOW -> R.string.accounts_device_flow
                    else -> R.string.accounts_pat
                }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 64.dp),
            )
        }
        if (switching) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        else if (selected) Icon(Icons.Default.Check, contentDescription = stringResource(R.string.accounts_current), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun AccountAuthActions(enabled: Boolean, onPat: () -> Unit, onDevice: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onPat, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.accounts_pat), Modifier.padding(start = 6.dp))
        }
        OutlinedButton(onClick = onDevice, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Icon(Icons.Default.Computer, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.accounts_device_flow), Modifier.padding(start = 6.dp))
        }
    }
}
