package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.CustomDnsServerEntity
import io.nekohasekai.sagernet.database.CustomDnsServerStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.TAG_DIRECT
import io.nekohasekai.sagernet.fmt.normalizeCustomDnsDetour
import io.nekohasekai.sagernet.fmt.validateCustomDnsServer
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.compose.CustomDnsServerSettingsScreen
import io.nekohasekai.sagernet.ui.compose.CustomDnsServerSettingsUiState
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme

class CustomDnsServerSettingsActivity : ThemedActivity() {

    private var editingId = 0L
    private lateinit var server: CustomDnsServerEntity
    private var state by mutableStateOf(CustomDnsServerSettingsUiState())
    private var deletePrompt by mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editingId = intent.getLongExtra(CustomDnsServersActivity.EXTRA_SERVER_ID, 0L)
        server = CustomDnsServerStore.getById(editingId) ?: CustomDnsServerEntity()
        state = CustomDnsServerSettingsUiState(
            tag = server.tag,
            type = server.type,
            enabled = server.enabled,
            server = server.server,
            serverPort = server.serverPort.takeIf { it > 0 }?.toString().orEmpty(),
            path = server.path,
            method = server.method,
            headers = server.headers,
            domainResolver = server.domainResolver.takeIf { it == "dns-remote" } ?: "dns-direct",
            domainStrategy = server.domainStrategy,
            rewriteTtl = server.rewriteTtl.takeIf { it > 0 }?.toString().orEmpty(),
            clientSubnet = server.clientSubnet,
            detour = normalizeCustomDnsDetour(server.detour).takeIf { it == "proxy" } ?: TAG_DIRECT,
            bindInterface = server.bindInterface,
            inet4BindAddress = server.inet4BindAddress,
            inet6BindAddress = server.inet6BindAddress,
            connectTimeout = server.connectTimeout.takeIf { it > 0 }?.toString().orEmpty(),
            udpFragment = server.udpFragment,
            tlsServerName = server.tlsServerName,
            tlsAlpn = server.tlsAlpn,
            tlsCertificates = server.tlsCertificates,
        )
        setContent {
            NekoComposeTheme {
                CustomDnsServerSettingsScreen(
                    state = state,
                    canDelete = editingId != 0L,
                    deletePrompt = deletePrompt,
                    onStateChange = { state = it },
                    onClose = ::finish,
                    onSave = ::save,
                    onDelete = ::confirmDelete,
                    onConfirmDelete = ::delete,
                    onDismissDelete = { deletePrompt = null },
                )
            }
        }
    }

    private fun candidate(): CustomDnsServerEntity {
        return server.copy(
            tag = state.tag.trim(),
            type = state.type,
            enabled = state.enabled,
            server = state.server.trim(),
            serverPort = state.serverPort.toIntOrNull() ?: 0,
            path = state.path.trim(),
            method = state.method.trim(),
            headers = state.headers,
            domainResolver = if (state.type == "local") "" else state.domainResolver,
            domainStrategy = state.domainStrategy.trim(),
            rewriteTtl = state.rewriteTtl.toIntOrNull() ?: 0,
            clientSubnet = state.clientSubnet.trim(),
            detour = state.detour,
            bindInterface = state.bindInterface.trim(),
            inet4BindAddress = state.inet4BindAddress.trim(),
            inet6BindAddress = state.inet6BindAddress.trim(),
            connectTimeout = state.connectTimeout.toLongOrNull() ?: 0L,
            udpFragment = state.udpFragment.trim(),
            tlsServerName = state.tlsServerName.trim(),
            tlsAlpn = state.tlsAlpn,
            tlsCertificates = state.tlsCertificates,
        )
    }

    private fun save() {
        val candidate = candidate()
        val error = validateCustomDnsServer(candidate, CustomDnsServerStore.allServers())
        if (error != null) {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            return
        }
        runOnDefaultDispatcher {
            CustomDnsServerStore.save(candidate)
            finish()
        }
    }

    private fun confirmDelete() {
        val used = SagerDatabase.rulesDao.dnsRulesUsingServer(server.tag).isNotEmpty()
        deletePrompt = if (used) {
            R.string.custom_dns_server_delete_used_prompt
        } else {
            R.string.custom_dns_server_delete_prompt
        }
    }

    private fun delete() {
        deletePrompt = null
        runOnDefaultDispatcher {
            CustomDnsServerStore.delete(server)
            finish()
        }
    }
}
