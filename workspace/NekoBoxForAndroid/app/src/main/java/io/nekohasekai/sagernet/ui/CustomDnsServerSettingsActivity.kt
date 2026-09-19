package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.setPadding
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.CustomDnsServerEntity
import io.nekohasekai.sagernet.database.CustomDnsServerStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.TAG_DIRECT
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.fmt.normalizeCustomDnsDetour
import io.nekohasekai.sagernet.fmt.validateCustomDnsServer
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher

class CustomDnsServerSettingsActivity : ThemedActivity() {

    private val dnsTypes = listOf("udp", "tcp", "tls", "quic", "https", "h3", "local")
    private var editingId = 0L
    private lateinit var server: CustomDnsServerEntity
    private lateinit var content: LinearLayout
    private lateinit var typeSpinner: Spinner
    private lateinit var domainResolverSpinner: Spinner
    private lateinit var detourSpinner: Spinner
    private lateinit var enabledSwitch: MaterialSwitch
    private val fields = linkedMapOf<String, EditText>()
    private val rows = linkedMapOf<String, View>()
    private val domainResolverValues = listOf("dns-direct", "dns-remote")
    private val detourValues = listOf(TAG_DIRECT, TAG_PROXY)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editingId = intent.getLongExtra(CustomDnsServersActivity.EXTRA_SERVER_ID, 0L)
        server = CustomDnsServerStore.getById(editingId) ?: CustomDnsServerEntity()
        buildLayout()
        bindServer()
        updateVisibleFields()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, R.id.action_apply, Menu.NONE, R.string.apply)
            .setIcon(R.drawable.ic_action_done)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        if (editingId != 0L) {
            menu.add(Menu.NONE, R.id.action_delete, Menu.NONE, R.string.delete)
                .setIcon(R.drawable.ic_baseline_delete_24)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_apply -> {
                save()
                true
            }
            R.id.action_delete -> {
                confirmDelete()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
        }
        val actionBarHeight = theme.obtainStyledAttributes(intArrayOf(android.R.attr.actionBarSize)).let {
            val height = it.getDimensionPixelSize(0, (56 * resources.displayMetrics.density).toInt())
            it.recycle()
            height
        }
        val appbar = AppBarLayout(this).apply {
            id = R.id.appbar
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                actionBarHeight,
            )
        }
        val toolbar = MaterialToolbar(this).apply {
            id = R.id.toolbar
            setTitle(R.string.dns_servers)
            layoutParams = AppBarLayout.LayoutParams(
                AppBarLayout.LayoutParams.MATCH_PARENT,
                AppBarLayout.LayoutParams.MATCH_PARENT,
            )
        }
        appbar.addView(toolbar)
        root.addView(appbar)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * resources.displayMetrics.density).toInt())
        }
        val scrollView = ScrollView(this).apply {
            clipToPadding = false
            addView(content)
        }
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            appbar.setPadding(0, statusTop, 0, 0)
            appbar.layoutParams = appbar.layoutParams.apply {
                height = actionBarHeight + statusTop
            }
            scrollView.setPadding(0, 0, 0, navBottom)
            insets
        }
        setContentView(root)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        addTextField("tag", getString(R.string.dns_server_name))
        addTypeField()
        enabledSwitch = MaterialSwitch(this).apply { text = getString(R.string.enable) }
        content.addView(enabledSwitch)
        addTextField("server", getString(R.string.server_address))
        addTextField("serverPort", getString(R.string.server_port))
        addTextField("path", "Path")
        addTextField("method", "HTTP method")
        addTextField("headers", "Headers")
        addDomainResolverField()
        addTextField("domainStrategy", getString(R.string.domain_strategy))
        addTextField("rewriteTtl", getString(R.string.dns_rewrite_ttl))
        addTextField("clientSubnet", getString(R.string.dns_client_subnet))
        addDetourField()
        addTextField("bindInterface", "Bind interface")
        addTextField("inet4BindAddress", "IPv4 bind address")
        addTextField("inet6BindAddress", "IPv6 bind address")
        addTextField("connectTimeout", "Connect timeout")
        addTextField("udpFragment", "UDP fragment (true/false)")
        addTextField("tlsServerName", "TLS server name")
        addTextField("tlsAlpn", "TLS ALPN")
        addTextField("tlsCertificates", "TLS certificates")
    }

    private fun addTypeField() {
        val row = fieldRow(getString(R.string.dns_server_type))
        typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@CustomDnsServerSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                dnsTypes,
            )
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    updateVisibleFields()
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        }
        (row as LinearLayout).addView(typeSpinner)
        content.addView(row)
    }

    private fun addDomainResolverField() {
        val row = fieldRow(getString(R.string.domain_resolver))
        domainResolverSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@CustomDnsServerSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.dns_route_direct), getString(R.string.dns_route_remote)),
            )
        }
        (row as LinearLayout).addView(domainResolverSpinner)
        rows["domainResolver"] = row
        content.addView(row)
    }

    private fun addDetourField() {
        val row = fieldRow(getString(R.string.custom_dns_detour))
        detourSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@CustomDnsServerSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    getString(R.string.dns_route_direct),
                    getString(R.string.custom_dns_detour_proxy),
                ),
            )
        }
        (row as LinearLayout).addView(detourSpinner)
        rows["detour"] = row
        content.addView(row)
    }

    private fun addTextField(key: String, label: String) {
        val row = fieldRow(label)
        val editText = EditText(this).apply {
            isSingleLine = key !in setOf("headers", "tlsCertificates")
        }
        (row as LinearLayout).addView(editText)
        fields[key] = editText
        rows[key] = row
        content.addView(row)
    }

    private fun fieldRow(label: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val vertical = (6 * resources.displayMetrics.density).toInt()
            setPadding(0, vertical, 0, vertical)
            addView(TextView(this@CustomDnsServerSettingsActivity).apply { text = label })
        }
    }

    private fun bindServer() {
        fields["tag"]!!.setText(server.tag)
        typeSpinner.setSelection(dnsTypes.indexOf(server.type).takeIf { it >= 0 } ?: 0)
        enabledSwitch.isChecked = server.enabled
        fields["server"]!!.setText(server.server)
        fields["serverPort"]!!.setText(server.serverPort.takeIf { it > 0 }?.toString().orEmpty())
        fields["path"]!!.setText(server.path)
        fields["method"]!!.setText(server.method)
        fields["headers"]!!.setText(server.headers)
        domainResolverSpinner.setSelection(
            domainResolverValues.indexOf(server.domainResolver).takeIf { it >= 0 } ?: 0,
        )
        fields["domainStrategy"]!!.setText(server.domainStrategy)
        fields["rewriteTtl"]!!.setText(server.rewriteTtl.takeIf { it > 0 }?.toString().orEmpty())
        fields["clientSubnet"]!!.setText(server.clientSubnet)
        detourSpinner.setSelection(detourValues.indexOf(normalizeCustomDnsDetour(server.detour)))
        fields["bindInterface"]!!.setText(server.bindInterface)
        fields["inet4BindAddress"]!!.setText(server.inet4BindAddress)
        fields["inet6BindAddress"]!!.setText(server.inet6BindAddress)
        fields["connectTimeout"]!!.setText(server.connectTimeout.takeIf { it > 0 }?.toString().orEmpty())
        fields["udpFragment"]!!.setText(server.udpFragment)
        fields["tlsServerName"]!!.setText(server.tlsServerName)
        fields["tlsAlpn"]!!.setText(server.tlsAlpn)
        fields["tlsCertificates"]!!.setText(server.tlsCertificates)
    }

    private fun updateVisibleFields() {
        if (!::typeSpinner.isInitialized) return
        val type = dnsTypes[typeSpinner.selectedItemPosition]
        val remote = type != "local"
        val https = type == "https" || type == "h3"
        val tls = type in setOf("tls", "quic", "https", "h3")
        rows["server"]?.visibility = if (remote) View.VISIBLE else View.GONE
        rows["serverPort"]?.visibility = if (remote) View.VISIBLE else View.GONE
        rows["path"]?.visibility = if (https) View.VISIBLE else View.GONE
        rows["method"]?.visibility = if (https) View.VISIBLE else View.GONE
        rows["headers"]?.visibility = if (https) View.VISIBLE else View.GONE
        rows["domainResolver"]?.visibility = if (remote) View.VISIBLE else View.GONE
        rows["domainStrategy"]?.visibility = if (remote) View.VISIBLE else View.GONE
        rows["rewriteTtl"]?.visibility = if (remote) View.VISIBLE else View.GONE
        rows["clientSubnet"]?.visibility = if (remote) View.VISIBLE else View.GONE
        rows["udpFragment"]?.visibility = if (remote) View.VISIBLE else View.GONE
        rows["tlsServerName"]?.visibility = if (tls) View.VISIBLE else View.GONE
        rows["tlsAlpn"]?.visibility = if (tls) View.VISIBLE else View.GONE
        rows["tlsCertificates"]?.visibility = if (tls) View.VISIBLE else View.GONE
    }

    private fun readServer(): CustomDnsServerEntity {
        val type = dnsTypes[typeSpinner.selectedItemPosition]
        return server.copy(
            tag = fields["tag"]!!.text.toString().trim(),
            type = type,
            enabled = enabledSwitch.isChecked,
            server = fields["server"]!!.text.toString().trim(),
            serverPort = fields["serverPort"]!!.text.toString().toIntOrNull() ?: 0,
            path = fields["path"]!!.text.toString().trim(),
            method = fields["method"]!!.text.toString().trim(),
            headers = fields["headers"]!!.text.toString(),
            domainResolver = if (type == "local") "" else domainResolverValues[domainResolverSpinner.selectedItemPosition],
            domainStrategy = fields["domainStrategy"]!!.text.toString().trim(),
            rewriteTtl = fields["rewriteTtl"]!!.text.toString().toIntOrNull() ?: 0,
            clientSubnet = fields["clientSubnet"]!!.text.toString().trim(),
            detour = detourValues[detourSpinner.selectedItemPosition],
            bindInterface = fields["bindInterface"]!!.text.toString().trim(),
            inet4BindAddress = fields["inet4BindAddress"]!!.text.toString().trim(),
            inet6BindAddress = fields["inet6BindAddress"]!!.text.toString().trim(),
            connectTimeout = fields["connectTimeout"]!!.text.toString().toLongOrNull() ?: 0L,
            udpFragment = fields["udpFragment"]!!.text.toString().trim(),
            tlsServerName = fields["tlsServerName"]!!.text.toString().trim(),
            tlsAlpn = fields["tlsAlpn"]!!.text.toString(),
            tlsCertificates = fields["tlsCertificates"]!!.text.toString(),
        )
    }

    private fun save() {
        val candidate = readServer()
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
        MaterialAlertDialogBuilder(this)
            .setTitle(if (used) R.string.custom_dns_server_delete_used_prompt else R.string.custom_dns_server_delete_prompt)
            .setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    CustomDnsServerStore.delete(server)
                    finish()
                }
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }
}
