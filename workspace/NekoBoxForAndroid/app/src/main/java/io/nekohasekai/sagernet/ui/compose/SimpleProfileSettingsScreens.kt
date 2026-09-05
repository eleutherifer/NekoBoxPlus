package io.nekohasekai.sagernet.ui.compose

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.profile.ConfigEditActivity

@Composable
internal fun ConfigProfileSettingsScreen(isOutboundOnlyKey: String) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val store = DataStore.profileCacheStore
    val notSet = stringResource(R.string.not_set)
    val nameTitle = stringResource(R.string.profile_name)
    var name by remember { mutableStateOf(DataStore.profileName) }
    var outboundOnly by remember { mutableStateOf(store.getBoolean(isOutboundOnlyKey, false)) }
    var config by remember { mutableStateOf(DataStore.serverConfig) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) config = DataStore.serverConfig
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
        item {
            ProfileActionRow(R.drawable.ic_social_emoji_symbols, R.string.profile_name,
                name.ifBlank { notSet }) {
                context.showComposeTextInputDialog(nameTitle, name, onPositive = {
                    name = it
                    DataStore.profileName = it
                })
            }
        }
        item {
            ProfileSwitchRow(R.drawable.ic_baseline_import_contacts_24,
                R.string.is_outbound_only, outboundOnly) {
                outboundOnly = it
                store.putBoolean(isOutboundOnlyKey, it)
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_baseline_layers_24, R.string.custom_config,
                config.ifBlank { notSet }) {
                context.startActivity(Intent(context, ConfigEditActivity::class.java).apply {
                    putExtra("key", Key.SERVER_CONFIG)
                })
            }
        }
    }
}

@Composable
internal fun DirectProfileSettingsScreen() {
    val context = LocalContext.current
    val title = stringResource(R.string.profile_name)
    val notSet = stringResource(R.string.not_set)
    var name by remember { mutableStateOf(DataStore.profileName) }
    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
        item {
            ProfileActionRow(R.drawable.ic_social_emoji_symbols, R.string.profile_name,
                name.ifBlank { notSet }) {
                context.showComposeTextInputDialog(
                    title = title,
                    initialValue = name,
                    onPositive = { name = it; DataStore.profileName = it },
                )
            }
        }
        item { DirectOutboundNote() }
        item { SharedDialOptions() }
    }
}

@Composable
internal fun ByeDPIProfileSettingsScreen() {
    val context = LocalContext.current
    val nameTitle = stringResource(R.string.profile_name)
    val strategyTitle = stringResource(R.string.byedpi_cli_strategy)
    val notSet = stringResource(R.string.not_set)
    val store = DataStore.profileCacheStore
    var name by remember { mutableStateOf(DataStore.profileName) }
    var strategy by remember { mutableStateOf(store.getString("byeDpiCliStrategy").orEmpty()) }
    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
        item {
            ProfileActionRow(R.drawable.ic_social_emoji_symbols, R.string.profile_name,
                name.ifBlank { notSet }) {
                context.showComposeTextInputDialog(nameTitle, name, onPositive = {
                    name = it
                    DataStore.profileName = it
                })
            }
        }
        item { ProfileCategory(R.string.action_byedpi) }
        item {
            ProfileActionRow(R.drawable.ic_baseline_tune_24, R.string.byedpi_cli_strategy,
                strategy.ifBlank { notSet }) {
                context.showComposeTextInputDialog(strategyTitle, strategy, onPositive = {
                    strategy = it
                    store.putString("byeDpiCliStrategy", it)
                })
            }
        }
        item { SharedDialOptions() }
    }
}

@Composable
private fun DirectOutboundNote() {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterStart) {
            Icon(
                painter = painterResource(R.drawable.ic_baseline_info_24),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.direct_outbound_note),
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
