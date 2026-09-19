package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.CustomDnsServerEntity
import io.nekohasekai.sagernet.database.CustomDnsServerStore
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutRouteItemBinding
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.widget.ListListener

class CustomDnsServersActivity : ThemedActivity(R.layout.layout_route) {

    companion object {
        const val EXTRA_SERVER_ID = "id"
    }

    private lateinit var listView: RecyclerView
    private lateinit var adapter: ServerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setTitle(R.string.dns_servers)
            setDisplayHomeAsUpEnabled(true)
        }

        listView = findViewById(R.id.route_list)
        ViewCompat.setOnApplyWindowInsetsListener(listView, ListListener)
        listView.layoutManager = FixedLinearLayoutManager(listView)
        adapter = ServerAdapter()
        listView.adapter = adapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.START) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                if (index == RecyclerView.NO_POSITION) return
                confirmDelete(adapter.servers[index]) {
                    adapter.servers.removeAt(index)
                    adapter.notifyItemRemoved(index)
                }
            }
        }).attachToRecyclerView(listView)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, R.id.action_new_route, Menu.NONE, R.string.add_dns_server)
            .setIcon(R.drawable.ic_baseline_add_24)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_new_route -> {
                startActivity(Intent(this, CustomDnsServerSettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        adapter.reload()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun confirmDelete(server: CustomDnsServerEntity, afterDelete: () -> Unit) {
        val used = SagerDatabase.rulesDao.dnsRulesUsingServer(server.tag).isNotEmpty()
        val needsConfirm = DataStore.confirmProfileDelete || used
        if (!needsConfirm) {
            deleteServer(server, afterDelete)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (used) R.string.custom_dns_server_delete_used_prompt else R.string.custom_dns_server_delete_prompt)
            .setPositiveButton(R.string.yes) { _, _ -> deleteServer(server, afterDelete) }
            .setNegativeButton(R.string.no) { _, _ -> adapter.reload() }
            .setOnCancelListener { adapter.reload() }
            .show()
    }

    private fun deleteServer(server: CustomDnsServerEntity, afterDelete: () -> Unit) {
        runOnDefaultDispatcher {
            CustomDnsServerStore.delete(server)
            listView.post(afterDelete)
        }
    }

    inner class ServerAdapter : RecyclerView.Adapter<ServerHolder>() {
        val servers = mutableListOf<CustomDnsServerEntity>()

        fun reload() {
            runOnDefaultDispatcher {
                val loaded = CustomDnsServerStore.allServers()
                listView.post {
                    servers.clear()
                    servers.addAll(loaded)
                    notifyDataSetChanged()
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerHolder {
            return ServerHolder(LayoutRouteItemBinding.inflate(layoutInflater, parent, false))
        }

        override fun getItemCount(): Int = servers.size

        override fun onBindViewHolder(holder: ServerHolder, position: Int) {
            holder.bind(servers[position])
        }
    }

    inner class ServerHolder(private val binding: LayoutRouteItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(server: CustomDnsServerEntity) {
            binding.profileName.text = server.tag
            binding.profileType.text = server.displaySummary()
            binding.routeOutbound.text = server.type.uppercase()
            binding.enable.isChecked = server.enabled
            binding.enable.setOnCheckedChangeListener { _, checked ->
                runOnDefaultDispatcher {
                    CustomDnsServerStore.save(server.copy(enabled = checked))
                }
            }
            binding.edit.setOnClickListener {
                startActivity(Intent(this@CustomDnsServersActivity, CustomDnsServerSettingsActivity::class.java).apply {
                    putExtra(EXTRA_SERVER_ID, server.id)
                })
            }
            binding.duplicate.setImageResource(R.drawable.ic_baseline_delete_24)
            TooltipCompat.setTooltipText(binding.duplicate, getString(R.string.delete))
            binding.duplicate.setOnClickListener {
                confirmDelete(server) { adapter.reload() }
            }
        }
    }
}
