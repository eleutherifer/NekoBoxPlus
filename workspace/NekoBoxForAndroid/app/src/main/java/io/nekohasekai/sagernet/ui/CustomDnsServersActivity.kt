package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.CustomDnsServerEntity
import io.nekohasekai.sagernet.database.CustomDnsServerStore
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.compose.CustomDnsServersScreen
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme

class CustomDnsServersActivity : ThemedActivity() {

    companion object {
        const val EXTRA_SERVER_ID = "id"
    }

    private var servers by mutableStateOf(emptyList<CustomDnsServerEntity>())
    private var pendingDelete by mutableStateOf<CustomDnsServerEntity?>(null)
    private var deletePrompt by mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NekoComposeTheme {
                CustomDnsServersScreen(
                    servers = servers,
                    deletePrompt = deletePrompt,
                    onClose = ::finish,
                    onAdd = {
                        startActivity(Intent(this, CustomDnsServerSettingsActivity::class.java))
                    },
                    onEnabledChange = ::setEnabled,
                    onEdit = ::edit,
                    onDelete = ::confirmDelete,
                    onConfirmDelete = ::deletePending,
                    onDismissDelete = ::dismissDelete,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        runOnDefaultDispatcher {
            val loaded = CustomDnsServerStore.allServers()
            runOnUiThread { servers = loaded }
        }
    }

    private fun setEnabled(server: CustomDnsServerEntity, enabled: Boolean) {
        val updated = server.copy(enabled = enabled)
        servers = servers.map { if (it.id == updated.id) updated else it }
        runOnDefaultDispatcher { CustomDnsServerStore.save(updated) }
    }

    private fun edit(server: CustomDnsServerEntity) {
        startActivity(
            Intent(this, CustomDnsServerSettingsActivity::class.java).apply {
                putExtra(EXTRA_SERVER_ID, server.id)
            },
        )
    }

    private fun confirmDelete(server: CustomDnsServerEntity) {
        val used = SagerDatabase.rulesDao.dnsRulesUsingServer(server.tag).isNotEmpty()
        if (!DataStore.confirmProfileDelete && !used) {
            delete(server)
            return
        }
        pendingDelete = server
        deletePrompt = if (used) {
            R.string.custom_dns_server_delete_used_prompt
        } else {
            R.string.custom_dns_server_delete_prompt
        }
    }

    private fun dismissDelete() {
        pendingDelete = null
        deletePrompt = null
    }

    private fun deletePending() {
        pendingDelete?.let(::delete)
        dismissDelete()
    }

    private fun delete(server: CustomDnsServerEntity) {
        servers = servers.filterNot { it.id == server.id }
        runOnDefaultDispatcher { CustomDnsServerStore.delete(server) }
    }
}
