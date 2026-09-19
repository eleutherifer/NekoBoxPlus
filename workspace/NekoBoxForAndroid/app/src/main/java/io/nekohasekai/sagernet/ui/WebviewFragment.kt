package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.EditText
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutWebviewBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.widget.ListListener
import libcore.Libcore
import moe.matsuri.nb4a.utils.WebViewUtil
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

// Fragment必须有一个无参public的构造函数，否则在数据恢复的时候，会报crash

class WebviewFragment : ToolbarFragment(R.layout.layout_webview), Toolbar.OnMenuItemClickListener {

    private val dashboardClient = OkHttpClient()
    private val dashboardUnixClient by lazy {
        OkHttpClient.Builder()
            .dns(DashboardDns)
            .socketFactory(UnixDomainSocketFactory(DataStore.clashApiSocketPath))
            .build()
    }
    private val dashboardStreamingUnixClient by lazy {
        OkHttpClient.Builder()
            .dns(DashboardDns)
            .socketFactory(UnixDomainSocketFactory(DataStore.clashApiSocketPath))
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
    private val dashboardWebSocketUnixClient by lazy {
        OkHttpClient.Builder()
            .dns(DashboardDns)
            .socketFactory(UnixDomainSocketFactory(DataStore.clashApiSocketPath))
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var binding: LayoutWebviewBinding? = null
    private var mWebView: WebView? = null
    private var dashboardClosed = false
    private var dashboardEndpointSeeded = false
    private lateinit var dashboardSignature: DashboardSignature
    private var dashboardLoadAttempts = 0
    private var pendingDashboardLoad: Runnable? = null
    private var pendingDashboardLoadUrl: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // layout
        toolbar.setTitle(R.string.menu_dashboard)
        toolbar.inflateMenu(R.menu.yacd_menu)
        toolbar.menu.findItem(R.id.action_set_url)?.isVisible = !DataStore.hideClashApi
        toolbar.setOnMenuItemClickListener(this)

        binding = LayoutWebviewBinding.bind(view)
        val binding = binding!!
        ViewCompat.setOnApplyWindowInsetsListener(binding.root, ListListener)
        dashboardSignature = DashboardSignature.current()

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        applyServiceState(DataStore.serviceState)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureDashboardWebView() {
        val webView = mWebView ?: return
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.javaScriptEnabled = true
        if (DataStore.hideClashApi) {
            webView.addJavascriptInterface(DashboardBridge(), "nb4aClashApi")
            installDashboardServiceWorkerClient()
        } else {
            uninstallDashboardServiceWorkerClient()
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val message = "Dashboard console ${consoleMessage.messageLevel()}: " +
                    "${consoleMessage.message()} at ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}"
                when (consoleMessage.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> Logs.e(message)
                    ConsoleMessage.MessageLevel.WARNING -> Logs.w(message)
                    ConsoleMessage.MessageLevel.LOG,
                    ConsoleMessage.MessageLevel.TIP -> Logs.i(message)
                    ConsoleMessage.MessageLevel.DEBUG -> Logs.d(message)
                    else -> Logs.i(message)
                }
                return true
            }
        }
        webView.webViewClient = if (DataStore.hideClashApi) object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                WebViewUtil.onReceivedError(view, request, error)
            }

            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? {
                return interceptDashboardRequest(request) ?: super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding?.noActiveConnections?.visibility = View.GONE
                view?.visibility = View.VISIBLE
            }
        } else object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                WebViewUtil.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true && shouldRetryDashboardLoad(request.url)) {
                    scheduleDashboardLoadRetry()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                dashboardLoadAttempts = 0
                binding?.noActiveConnections?.visibility = View.GONE
                view?.visibility = View.VISIBLE
                seedDashboardEndpoint(url)
            }
        }
    }

    private fun obtainDashboardWebView(container: ViewGroup, signature: DashboardSignature): WebView {
        val layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        val retained = retainedDashboardWebView
        if (retained != null && retainedDashboardSignature == signature) {
            (retained.parent as? ViewGroup)?.removeView(retained)
            container.addView(retained, 0, layoutParams)
            return retained
        }

        destroyRetainedDashboardWebView()
        retainedDashboardSignature = signature
        retainedDashboardEndpointSeeded = false
        retainedDashboardLoaded = false
        return WebView(requireContext()).also {
            it.visibility = View.INVISIBLE
            retainedDashboardWebView = it
            container.addView(it, 0, layoutParams)
        }
    }

    fun applyServiceState(state: BaseService.State) {
        if (dashboardClosed) return
        if (state.started) {
            showDashboard()
        } else {
            showNoActiveConnections()
        }
    }

    private fun showDashboard() {
        val binding = binding ?: return
        binding.noActiveConnections.visibility = View.GONE
        ensureDashboardWebView()
        loadPanel(DataStore.resolvedYacdURL(), resetAttempts = true)
    }

    private fun ensureDashboardWebView(): WebView {
        val binding = checkNotNull(binding)
        val currentSignature = DashboardSignature.current()
        val currentWebView = mWebView
        if (currentWebView != null && dashboardSignature == currentSignature) {
            currentWebView.resumeTimers()
            currentWebView.onResume()
            return currentWebView
        }

        dashboardSignature = currentSignature
        mWebView = obtainDashboardWebView(binding.webviewContainer, dashboardSignature)
        val webView = checkNotNull(mWebView)
        webView.resumeTimers()
        webView.onResume()
        dashboardEndpointSeeded = retainedDashboardEndpointSeeded
        configureDashboardWebView()
        return webView
    }

    private fun showNoActiveConnections() {
        cancelDashboardLoadRetry()
        uninstallDashboardServiceWorkerClient()
        pendingDashboardLoadUrl = null
        dashboardLoadAttempts = 0
        destroyRetainedDashboardWebView(cleanStorage = false)
        mWebView = null
        binding?.noActiveConnections?.visibility = View.VISIBLE
    }

    @SuppressLint("CheckResult")
    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_set_url -> {
                val view = EditText(context).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    setText(DataStore.resolvedYacdURL())
                }
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.set_panel_url)
                    .setView(view)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        DataStore.yacdURL = view.text.toString()
                        retainedDashboardLoaded = false
                        if (DataStore.serviceState.started) {
                            ensureDashboardWebView()
                            loadPanel(DataStore.resolvedYacdURL(), resetAttempts = true)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            R.id.close -> {
                destroyDashboardAndNavigate(cleanStorage = false)
            }
            R.id.action_cleanup -> {
                confirmDashboardCleanup()
            }
        }
        return true
    }

    override fun onDestroyView() {
        if (!dashboardClosed) {
            suspendDashboard()
        }
        cancelDashboardLoadRetry()
        binding = null
        super.onDestroyView()
    }

    private fun suspendDashboard() {
        mWebView?.let { webView ->
            webView.evaluateJavascript(DASHBOARD_SUSPEND_SCRIPT) {
                retainedDashboardLoaded = false
                dashboardWebSockets.values.forEach { it.cancel() }
                dashboardWebSockets.clear()
                webView.onPause()
                webView.pauseTimers()
                (webView.parent as? ViewGroup)?.removeView(webView)
            }
        }
    }

    private fun confirmDashboardCleanup() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.webview_cleanup_confirmation)
            .setPositiveButton(R.string.yes) { _, _ ->
                destroyDashboardAndNavigate(cleanStorage = true, cleanupPanelFiles = true)
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun destroyDashboardAndNavigate(cleanStorage: Boolean, cleanupPanelFiles: Boolean = false) {
        dashboardClosed = true
        cancelDashboardLoadRetry()
        uninstallDashboardServiceWorkerClient()
        if (cleanupPanelFiles) {
            SagerNet.stopService()
        }
        val webView = mWebView
        if (cleanStorage && webView != null) {
            webView.evaluateJavascript(DASHBOARD_CLEANUP_SCRIPT) {
                mainHandler.postDelayed({
                    destroyRetainedDashboardWebView(cleanStorage = true)
                    mWebView = null
                    cleanupPanelFilesAndNavigate(cleanupPanelFiles)
                }, 500)
            }
            return
        }
        destroyRetainedDashboardWebView(cleanStorage = false)
        mWebView = null
        cleanupPanelFilesAndNavigate(cleanupPanelFiles)
    }

    private fun cleanupPanelFilesAndNavigate(cleanupPanelFiles: Boolean) {
        if (!cleanupPanelFiles) {
            navigateToDefaultMainFragment()
            return
        }
        runOnDefaultDispatcher {
            waitForDashboardServiceStop()
            cleanupSingBoxPanelFiles()
            runCatching {
                Libcore.resetPanelAssets()
            }.onFailure {
                Logs.w("Failed to restore bundled sing-box panel assets", it)
            }
            mainHandler.post {
                navigateToDefaultMainFragment()
            }
        }
    }

    private fun waitForDashboardServiceStop() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (DataStore.serviceState.started && System.nanoTime() < deadline) {
            Thread.sleep(100)
        }
    }

    private fun navigateToDefaultMainFragment() {
        (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_configuration)
    }

    private fun cleanupSingBoxPanelFiles() {
        val app = SagerNet.application
        val dataDir = File(app.applicationInfo.dataDir)
        val cleanupTargets = listOf(
            File(app.filesDir, "metacubexd"),
            File(app.filesDir, "metacubexd.version.txt"),
            File(app.filesDir, "yacd"),
            File(app.filesDir, "yacd.version.txt"),
            File(app.cacheDir, DataStore.CLASH_API_SOCKET_NAME),
            File(app.cacheDir, "WebView"),
            File(dataDir, "app_webview/Default/Service Worker"),
            File(dataDir, "app_webview/Default/Local Storage"),
            File(dataDir, "app_webview/Default/Session Storage"),
            File(dataDir, "app_webview/Default/IndexedDB"),
            File(dataDir, "app_webview/Default/WebStorage"),
            File(dataDir, "app_webview/Default/CacheStorage"),
            File(dataDir, "app_webview/Default/blob_storage"),
            File(dataDir, "app_webview/Default/shared_proto_db"),
            File(dataDir, "app_webview/Default/Cookies"),
            File(dataDir, "app_webview/Default/Cookies-journal"),
        )
        cleanupTargets.forEach { target ->
            runCatching {
                if (target.isDirectory) {
                    target.deleteRecursively()
                } else {
                    target.delete()
                }
            }.onFailure {
                Logs.w("Failed to remove sing-box panel file: ${target.absolutePath}", it)
            }
        }

        listOf(
            app.cacheDir,
            app.filesDir,
            app.noBackupFilesDir,
        ).forEach { root ->
            if (!root.exists()) return@forEach
            runCatching {
                root.walkTopDown()
                    .filter { it.name.startsWith("external-ui.zip") }
                    .forEach { it.deleteRecursively() }
            }.onFailure {
                Logs.w("Failed to remove sing-box panel temp files in ${root.absolutePath}", it)
            }
        }
    }

    private fun loadPanel(url: String, resetAttempts: Boolean = false) {
        if (DataStore.yacdURL != url) {
            DataStore.yacdURL = url
        }
        val webView = mWebView ?: return
        if (!DataStore.serviceState.started) {
            showNoActiveConnections()
            return
        }
        if (resetAttempts || pendingDashboardLoadUrl != url) {
            dashboardLoadAttempts = 0
        }
        pendingDashboardLoadUrl = url
        cancelDashboardLoadRetry()
        if (retainedDashboardLoaded && webView.url != null) {
            webView.visibility = View.VISIBLE
            return
        }
        retainedDashboardLoaded = true
        dashboardLoadAttempts++
        webView.visibility = View.INVISIBLE
        webView.loadUrl(url, dashboardHeaders(Uri.parse(url)))
    }

    private fun shouldRetryDashboardLoad(uri: Uri?): Boolean {
        uri ?: return false
        val host = uri.host ?: return false
        val isDashboardHost = host == DataStore.CLASH_API_DASHBOARD_HOST ||
            host == DataStore.CLASH_API_HOST ||
            host == "localhost"
        return isDashboardHost && uri.port == DataStore.CLASH_API_PORT
    }

    private fun scheduleDashboardLoadRetry() {
        if (!DataStore.serviceState.started || dashboardLoadAttempts >= DASHBOARD_LOAD_ATTEMPTS) return
        retainedDashboardLoaded = false
        val url = pendingDashboardLoadUrl ?: DataStore.resolvedYacdURL()
        cancelDashboardLoadRetry()
        val retry = Runnable {
            pendingDashboardLoad = null
            if (!dashboardClosed && DataStore.serviceState.started && binding != null) {
                ensureDashboardWebView()
                loadPanel(url)
            }
        }
        pendingDashboardLoad = retry
        mainHandler.postDelayed(retry, DASHBOARD_LOAD_RETRY_DELAY_MS)
    }

    private fun cancelDashboardLoadRetry() {
        pendingDashboardLoad?.let { mainHandler.removeCallbacks(it) }
        pendingDashboardLoad = null
    }

    private fun seedDashboardEndpoint(url: String?) {
        if (dashboardEndpointSeeded) return
        val uri = Uri.parse(url ?: return)
        val headers = dashboardHeaders(uri)
        if (headers.isEmpty() || uri.path?.startsWith("/ui") != true) return
        dashboardEndpointSeeded = true
        retainedDashboardEndpointSeeded = true

        val endpointUrl = Uri.Builder()
            .scheme("http")
            .encodedAuthority("${uri.host}:${DataStore.CLASH_API_PORT}")
            .build()
            .toString()
        val endpoint = JSONObject()
            .put("id", "nb4a-loopback")
            .put("url", endpointUrl)
            .put("secret", DataStore.clashApiSecret)
        val script = """
            (function() {
              try {
                var endpoint = ${endpoint};
                ${dashboardStorageSeedScript()}
                var previousSelected = localStorage.getItem('selectedEndpoint');
                var endpoints = JSON.parse(localStorage.getItem('endpointList') || '[]');
                if (!Array.isArray(endpoints)) endpoints = [];
                endpoints = endpoints.filter(function(item) {
                  return item && item.id !== endpoint.id && item.url !== endpoint.url;
                });
                endpoints.unshift(endpoint);
                var serializedEndpoints = JSON.stringify(endpoints);
                var changed = previousSelected !== endpoint.id ||
                  localStorage.getItem('endpointList') !== serializedEndpoints;
                localStorage.setItem('selectedEndpoint', endpoint.id);
                localStorage.setItem('endpointList', serializedEndpoints);
                if (changed && (location.pathname.endsWith('/setup') || location.pathname.endsWith('/ui/'))) {
                  location.reload();
                }
              } catch (e) {
                console.error('Failed to seed NB4A dashboard endpoint', e);
              }
            })();
        """.trimIndent()
        mWebView?.evaluateJavascript(script, null)
    }

    private fun installDashboardServiceWorkerClient() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
            override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                return interceptDashboardRequest(request)
            }
        })
    }

    private fun uninstallDashboardServiceWorkerClient() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
            override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = null
        })
    }

    private fun interceptDashboardRequest(request: WebResourceRequest?): WebResourceResponse? {
        if (!DataStore.hideClashApi) return null
        request ?: return null
        val url = normalizeDashboardUri(request.url ?: return null)
        val headers = dashboardHeaders(url)
        if (headers.isEmpty()) return null
        if (request.method != "GET" && request.method != "HEAD") return null

        val proxiedRequest = Request.Builder()
            .url(url.toString())
            .method(request.method, null)
            .apply {
                request.requestHeaders.forEach { (name, value) ->
                    if (!name.equals("Authorization", ignoreCase = true)) {
                        header(name, value)
                    }
                }
                headers.forEach { (name, value) -> header(name, value) }
            }
            .build()

        return try {
            val response = executeDashboardRequestWithRetries(dashboardHttpClient(url), proxiedRequest)
            if (response.code in 300..399) {
                response.close()
                return null
            }
            if (DataStore.hideClashApi && isDashboardHtml(url, response)) {
                return injectDashboardBridge(response)
            }
            val body = response.body
            if (body == null) {
                response.close()
                null
            } else {
                val responseHeaders = response.headers.toMultimap().mapValues { it.value.joinToString(", ") }
                val (mimeType, encoding) = webResourceContentType(response)
                WebResourceResponse(
                    mimeType,
                    encoding,
                    response.code,
                    response.message,
                    responseHeaders,
                    body.byteStream()
                )
            }
        } catch (e: IOException) {
            if (DataStore.hideClashApi) {
                Logs.e("Failed to proxy hidden Clash API request via UDS: ${url}", e)
                emptyDashboardResponse(502, "Bad Gateway")
            } else {
                null
            }
        }
    }

    private fun dashboardHeaders(uri: Uri): Map<String, String> {
        val host = uri.host ?: return emptyMap()
        val isLoopbackHost = if (DataStore.hideClashApi) {
            isHiddenDashboardHost(host)
        } else {
            host == DataStore.CLASH_API_HOST || host == "localhost"
        }
        if (!isLoopbackHost || uri.port != DataStore.CLASH_API_PORT) return emptyMap()
        return mapOf("Authorization" to "Bearer ${DataStore.clashApiSecret}")
    }

    private fun normalizeDashboardUri(uri: Uri): Uri {
        if (!DataStore.hideClashApi) return uri
        val host = uri.host ?: return uri
        if (!isHiddenDashboardHost(host) || uri.port != DataStore.CLASH_API_PORT) return uri
        return uri.buildUpon()
            .encodedAuthority("${DataStore.CLASH_API_DASHBOARD_HOST}:${DataStore.CLASH_API_PORT}")
            .build()
    }

    private fun isHiddenDashboardHost(host: String): Boolean {
        return host == DataStore.CLASH_API_DASHBOARD_HOST ||
            host == DataStore.CLASH_API_HOST ||
            host == "localhost"
    }

    private fun dashboardHttpClient(uri: Uri): OkHttpClient {
        return when {
            !DataStore.hideClashApi -> dashboardClient
            isDashboardStreamingEndpoint(uri) -> dashboardStreamingUnixClient
            else -> dashboardUnixClient
        }
    }

    private fun executeDashboardRequestWithRetries(client: OkHttpClient, request: Request): Response {
        var lastError: IOException? = null
        repeat(DASHBOARD_LOAD_ATTEMPTS) { attempt ->
            try {
                return client.newCall(request).execute()
            } catch (e: IOException) {
                lastError = e
                if (!DataStore.serviceState.started || attempt == DASHBOARD_LOAD_ATTEMPTS - 1) {
                    throw e
                }
                Thread.sleep(DASHBOARD_LOAD_RETRY_DELAY_MS)
            }
        }
        throw lastError ?: IOException("Dashboard request failed")
    }

    private fun isDashboardStreamingEndpoint(uri: Uri): Boolean {
        return when (uri.path) {
            "/traffic", "/memory", "/logs" -> true
            else -> false
        }
    }

    private fun emptyDashboardResponse(
        statusCode: Int = 204,
        reasonPhrase: String = "No Content",
    ): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            statusCode,
            reasonPhrase,
            emptyMap(),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    private fun isDashboardHtml(uri: Uri, response: Response): Boolean {
        val path = uri.path ?: return false
        val contentType = response.body?.contentType()
        val isDashboardPath = path == "/ui/" || path == "/ui/setup"
        return isDashboardPath && contentType?.subtype?.contains("html", ignoreCase = true) == true
    }

    private fun injectDashboardBridge(response: Response): WebResourceResponse? {
        val body = response.body ?: return null
        return response.use {
            val html = body.string().injectDashboardBridge(dashboardEndpointSeedScript(reload = false))
            val responseHeaders = response.headers.toMultimap().mapValues { it.value.joinToString(", ") }
            WebResourceResponse(
                "text/html",
                body.contentType()?.charset()?.name() ?: "UTF-8",
                response.code,
                response.message,
                responseHeaders,
                ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
            )
        }
    }

    private fun webResourceContentType(response: Response): Pair<String?, String?> {
        val contentType = response.body?.contentType()
        val mimeType = contentType?.let { "${it.type}/${it.subtype}" }
        val encoding = contentType?.charset()?.name()
        return mimeType to encoding
    }

    private fun String.injectDashboardBridge(): String {
        return injectDashboardBridge("")
    }

    private fun String.injectDashboardBridge(seedScript: String): String {
        return replace("</head>", seedScript + DASHBOARD_BRIDGE_SCRIPT + "</head>")
    }

    private fun dashboardEndpointSeedScript(reload: Boolean): String {
        val endpointUrl = Uri.Builder()
            .scheme("http")
            .encodedAuthority("${DataStore.CLASH_API_HOST}:${DataStore.CLASH_API_PORT}")
            .build()
            .toString()
        val endpoint = JSONObject()
            .put("id", "nb4a-loopback")
            .put("url", endpointUrl)
            .put("secret", DataStore.clashApiSecret)
        return """
            <script>
            (function() {
              try {
                var endpoint = ${endpoint};
                ${dashboardStorageSeedScript()}
                var previousSelected = localStorage.getItem('selectedEndpoint');
                var endpoints = JSON.parse(localStorage.getItem('endpointList') || '[]');
                if (!Array.isArray(endpoints)) endpoints = [];
                endpoints = endpoints.filter(function(item) {
                  return item && item.id !== endpoint.id && item.url !== endpoint.url;
                });
                endpoints.unshift(endpoint);
                var serializedEndpoints = JSON.stringify(endpoints);
                var changed = previousSelected !== endpoint.id ||
                  localStorage.getItem('endpointList') !== serializedEndpoints;
                localStorage.setItem('selectedEndpoint', endpoint.id);
                localStorage.setItem('endpointList', serializedEndpoints);
                if (${reload} && changed && (location.pathname.endsWith('/setup') || location.pathname.endsWith('/ui/'))) {
                  location.reload();
                }
              } catch (e) {
                console.error('Failed to seed NB4A dashboard endpoint', e);
              }
            })();
            </script>
        """.trimIndent()
    }

    private fun dashboardStorageSeedScript(): String {
        return """
            var retentionRaw = localStorage.getItem('traffic_data_retention');
            var retention = null;
            try {
              retention = retentionRaw === null ? null : JSON.parse(retentionRaw);
            } catch (e) {
              retention = null;
            }
            if (retention === null || retention === -1 || retention > ${DATA_USAGE_RETENTION_MS}) {
              localStorage.setItem('traffic_data_retention', JSON.stringify(${DATA_USAGE_RETENTION_MS}));
            }
        """.trimIndent()
    }

    inner class DashboardBridge {

        @JavascriptInterface
        fun request(method: String, url: String, headersJson: String?, body: String?): String {
            val uri = normalizeDashboardUri(Uri.parse(url))
            val authHeaders = dashboardHeaders(uri)
            if (!DataStore.hideClashApi || authHeaders.isEmpty()) {
                return JSONObject()
                    .put("status", 403)
                    .put("message", "Forbidden")
                    .put("headers", JSONObject())
                    .put("body", "")
                    .toString()
            }

            val headers = JSONObject(headersJson ?: "{}")
            val contentType = headers.optString("content-type")
                .takeIf { it.isNotBlank() }
                ?.toMediaTypeOrNull()
            val requestBody = when (method.uppercase()) {
                "GET", "HEAD" -> null
                else -> (body ?: "").toRequestBody(contentType)
            }
            val request = Request.Builder()
                .url(uri.toString())
                .method(method, requestBody)
                .apply {
                    for (name in headers.keys()) {
                        if (!name.equals("Authorization", ignoreCase = true)) {
                            header(name, headers.optString(name))
                        }
                    }
                    authHeaders.forEach { (name, value) -> header(name, value) }
                }
                .build()

            return try {
                executeDashboardRequestWithRetries(dashboardUnixClient, request).use { response ->
                    val responseHeaders = JSONObject()
                    for ((name, values) in response.headers.toMultimap()) {
                        responseHeaders.put(name, values.joinToString(", "))
                    }
                    JSONObject()
                        .put("status", response.code)
                        .put("message", response.message)
                        .put("headers", responseHeaders)
                        .put("body", response.body?.string().orEmpty())
                        .toString()
                }
            } catch (e: IOException) {
                JSONObject()
                    .put("status", 502)
                    .put("message", e.message ?: "Bad Gateway")
                    .put("headers", JSONObject())
                    .put("body", "")
                    .toString()
            }
        }

        @JavascriptInterface
        fun openWebSocket(id: String, url: String) {
            if (!DataStore.hideClashApi || dashboardClosed) {
                emitDashboardWebSocketEvent(id, "error", JSONObject().put("message", "Forbidden"))
                emitDashboardWebSocketEvent(id, "close", JSONObject())
                return
            }
            val uri = normalizeDashboardUri(Uri.parse(url))
            val authHeaders = dashboardHeaders(uri)
            if (authHeaders.isEmpty()) {
                emitDashboardWebSocketEvent(id, "error", JSONObject().put("message", "Forbidden"))
                emitDashboardWebSocketEvent(id, "close", JSONObject())
                return
            }

            val request = Request.Builder()
                .url(uri.toString())
                .apply {
                    authHeaders.forEach { (name, value) -> header(name, value) }
                }
                .build()
            val socket = dashboardWebSocketUnixClient.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        emitDashboardWebSocketEvent(id, "open", JSONObject())
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        emitDashboardWebSocketEvent(id, "message", JSONObject().put("data", text))
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        dashboardWebSockets.remove(id)
                        emitDashboardWebSocketEvent(
                            id,
                            "close",
                            JSONObject()
                                .put("code", code)
                                .put("reason", reason)
                        )
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        dashboardWebSockets.remove(id)
                        emitDashboardWebSocketEvent(
                            id,
                            "error",
                            JSONObject().put("message", t.message ?: "WebSocket failure")
                        )
                        emitDashboardWebSocketEvent(
                            id,
                            "close",
                            JSONObject()
                                .put("code", response?.code ?: 1006)
                                .put("reason", response?.message.orEmpty())
                        )
                    }
                }
            )
            dashboardWebSockets.put(id, socket)?.cancel()
        }

        @JavascriptInterface
        fun closeWebSocket(id: String) {
            dashboardWebSockets.remove(id)?.close(1000, "closed")
        }
    }

    private fun emitDashboardWebSocketEvent(id: String, type: String, payload: JSONObject) {
        if (dashboardClosed || mWebView == null) return
        mainHandler.post {
            val webView = mWebView
            if (dashboardClosed || webView == null) return@post
            val event = JSONObject()
                .put("type", type)
                .put("payload", payload)
            webView.evaluateJavascript(
                "window.__nb4aClashApiSocketEvent && window.__nb4aClashApiSocketEvent(" +
                    "${JSONObject.quote(id)}, $event);",
                null
            )
        }
    }

    private class UnixDomainSocketFactory(private val path: String) : SocketFactory() {

        override fun createSocket(): Socket = UnixDomainSocket(path)

        override fun createSocket(host: String?, port: Int): Socket {
            return createSocket().apply {
                connect(InetSocketAddress.createUnresolved(host ?: DataStore.CLASH_API_HOST, port))
            }
        }

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket {
            return createSocket(host, port)
        }

        override fun createSocket(host: InetAddress?, port: Int): Socket {
            return createSocket().apply {
                connect(InetSocketAddress(host ?: InetAddress.getByName(DataStore.CLASH_API_HOST), port))
            }
        }

        override fun createSocket(
            address: InetAddress?,
            port: Int,
            localAddress: InetAddress?,
            localPort: Int,
        ): Socket {
            return createSocket(address, port)
        }
    }

    private class UnixDomainSocket(private val path: String) : Socket() {

        private var socket: LocalSocket? = null
        private var closed = false
        private var connected = false
        private var soTimeout = 0

        override fun connect(endpoint: SocketAddress?) {
            connect(endpoint, 0)
        }

        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            if (closed) throw IOException("socket is closed")
            if (connected) throw IOException("socket is already connected")
            val localSocket = LocalSocket()
            try {
                localSocket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
                if (soTimeout > 0) {
                    localSocket.soTimeout = soTimeout
                }
                socket = localSocket
                connected = true
            } catch (e: IOException) {
                localSocket.close()
                throw e
            }
        }

        override fun getInputStream(): InputStream {
            return checkNotNull(socket) { "socket is not connected" }.inputStream
        }

        override fun getOutputStream(): OutputStream {
            return checkNotNull(socket) { "socket is not connected" }.outputStream
        }

        override fun close() {
            closed = true
            socket?.close()
        }

        override fun shutdownInput() {
            socket?.shutdownInput()
        }

        override fun shutdownOutput() {
            socket?.shutdownOutput()
        }

        override fun setSoTimeout(timeout: Int) {
            soTimeout = timeout
            socket?.soTimeout = timeout
        }

        override fun getSoTimeout(): Int = soTimeout

        override fun setTcpNoDelay(on: Boolean) = Unit

        override fun getTcpNoDelay(): Boolean = true

        override fun setKeepAlive(on: Boolean) = Unit

        override fun getKeepAlive(): Boolean = false

        override fun isConnected(): Boolean = connected

        override fun isClosed(): Boolean = closed

        override fun getRemoteSocketAddress(): SocketAddress {
            return InetSocketAddress.createUnresolved(File(path).name, DataStore.CLASH_API_PORT)
        }

        override fun getLocalSocketAddress(): SocketAddress {
            return InetSocketAddress.createUnresolved(DataStore.CLASH_API_DASHBOARD_HOST, 0)
        }
    }

    private data class DashboardSignature(
        val hidden: Boolean,
        val url: String,
        val secret: String,
        val socketPath: String,
    ) {
        companion object {
            fun current(): DashboardSignature {
                return DashboardSignature(
                    hidden = DataStore.hideClashApi,
                    url = DataStore.resolvedYacdURL(),
                    secret = DataStore.clashApiSecret,
                    socketPath = DataStore.clashApiSocketPath,
                )
            }
        }
    }

    companion object {
        private const val DATA_USAGE_RETENTION_MS = 86_400_000L
        private const val DASHBOARD_LOAD_ATTEMPTS = 3
        private const val DASHBOARD_LOAD_RETRY_DELAY_MS = 500L

        private var retainedDashboardWebView: WebView? = null
        private var retainedDashboardSignature: DashboardSignature? = null
        private var retainedDashboardEndpointSeeded = false
        private var retainedDashboardLoaded = false
        private val dashboardWebSockets = ConcurrentHashMap<String, WebSocket>()
        private val DashboardDns = Dns { hostname ->
            if (hostname == DataStore.CLASH_API_DASHBOARD_HOST ||
                hostname == DataStore.CLASH_API_HOST ||
                hostname == "localhost"
            ) {
                listOf(InetAddress.getByName(DataStore.CLASH_API_HOST))
            } else {
                Dns.SYSTEM.lookup(hostname)
            }
        }

        private fun destroyRetainedDashboardWebView(cleanStorage: Boolean = false) {
            dashboardWebSockets.values.forEach { it.cancel() }
            dashboardWebSockets.clear()
            retainedDashboardWebView?.let { webView ->
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.stopLoading()
                clearDashboardWebStorage(webView, cleanStorage)
                webView.removeAllViews()
                webView.destroy()
            }
            retainedDashboardWebView = null
            retainedDashboardSignature = null
            retainedDashboardEndpointSeeded = false
            retainedDashboardLoaded = false
        }

        private fun clearDashboardWebStorage(webView: WebView, cleanStorage: Boolean) {
            webView.clearCache(cleanStorage)
            if (!cleanStorage) return

            webView.clearFormData()
            WebStorage.getInstance().deleteOrigin("http://${DataStore.CLASH_API_DASHBOARD_HOST}:${DataStore.CLASH_API_PORT}")
            WebStorage.getInstance().deleteOrigin("http://${DataStore.CLASH_API_HOST}:${DataStore.CLASH_API_PORT}")
            WebStorage.getInstance().deleteOrigin("http://localhost:${DataStore.CLASH_API_PORT}")
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }

        private const val DASHBOARD_SUSPEND_SCRIPT = """
(function() {
  try {
    window.dispatchEvent(new Event('pagehide'));
    localStorage.setItem('__nb4a_dashboard_suspend', String(Date.now()));
    localStorage.removeItem('__nb4a_dashboard_suspend');
  } catch (e) {}
})();
"""

        private const val DASHBOARD_CLEANUP_SCRIPT = """
(function() {
  try {
    localStorage.clear();
    sessionStorage.clear();
  } catch (e) {}
  try {
    if (window.caches) {
      caches.keys().then(function(keys) {
        keys.forEach(function(key) { caches.delete(key); });
      });
    }
  } catch (e) {}
  try {
    if (navigator.serviceWorker) {
      navigator.serviceWorker.getRegistrations().then(function(registrations) {
        registrations.forEach(function(registration) { registration.unregister(); });
      });
    }
  } catch (e) {}
  try {
    if (window.indexedDB && indexedDB.databases) {
      indexedDB.databases().then(function(databases) {
        databases.forEach(function(database) {
          if (database && database.name) indexedDB.deleteDatabase(database.name);
        });
      });
    }
  } catch (e) {}
})();
"""

        private const val DASHBOARD_BRIDGE_SCRIPT = """
<script>
(function() {
  if (window.__nb4aClashApiInstalled || !window.nb4aClashApi) return;
  window.__nb4aClashApiInstalled = true;
  var originalFetch = window.fetch.bind(window);
  var NativeRequest = window.Request;
  function normalizeDashboardUrl(url) {
    if ((url.hostname === 'core' || url.hostname === '127.0.0.1' || url.hostname === 'localhost') &&
        url.port === '9090') {
      var normalized = new URL(url.href);
      normalized.hostname = 'core';
      return normalized;
    }
    return null;
  }
  function normalizeRequestInput(input, init) {
    var request = input instanceof NativeRequest ? input : null;
    var target = normalizeDashboardUrl(new URL(request ? request.url : String(input), window.location.href));
    if (!target) return null;
    if (request) {
      var normalized = new NativeRequest(target.href, request);
      return init ? new NativeRequest(normalized, init) : normalized;
    }
    return new NativeRequest(target.href, init);
  }
  function collectHeaders(headers) {
    var result = {};
    new Headers(headers || {}).forEach(function(value, name) {
      result[name] = value;
    });
    return result;
  }
  function isStreamingEndpoint(url) {
    return url.pathname === '/traffic' || url.pathname === '/memory' || url.pathname === '/logs';
  }
  function DashboardRequest(input, init) {
    var request = normalizeRequestInput(input, init);
    if (request) return request;
    return new NativeRequest(input, init);
  }
  DashboardRequest.prototype = NativeRequest.prototype;
  Object.getOwnPropertyNames(NativeRequest).forEach(function(name) {
    if (!(name in DashboardRequest)) {
      try {
        Object.defineProperty(DashboardRequest, name, Object.getOwnPropertyDescriptor(NativeRequest, name));
      } catch (e) {}
    }
  });
  window.Request = DashboardRequest;
  window.fetch = function(input, init) {
    var request = input instanceof NativeRequest ? input : null;
    var normalizedRequest = normalizeRequestInput(input, init);
    if (!normalizedRequest) return originalFetch(input, init);
    var target = new URL(normalizedRequest.url);
    var method = normalizedRequest.method || 'GET';
    method = method.toUpperCase();
    if (method === 'GET' || method === 'HEAD') {
      return originalFetch(normalizedRequest);
    }
    var headers = request ? collectHeaders(request.headers) : {};
    if (init && init.headers) {
      var initHeaders = collectHeaders(init.headers);
      Object.keys(initHeaders).forEach(function(name) { headers[name] = initHeaders[name]; });
    }
    var bodyPromise = init && 'body' in init
      ? Promise.resolve(init.body == null ? '' : String(init.body))
      : (request ? request.clone().text() : Promise.resolve(''));
    return bodyPromise.then(function(body) {
      var raw = window.nb4aClashApi.request(method, target.href, JSON.stringify(headers), body);
      var result = JSON.parse(raw);
      var responseBody = result.status === 204 || result.status === 304 ? null : (result.body || '');
      return new Response(responseBody, {
        status: result.status || 502,
        statusText: result.message || '',
        headers: result.headers || {}
      });
    });
  };
  var NativeWebSocket = window.WebSocket;
  var dashboardSockets = {};
  window.__nb4aClashApiSocketEvent = function(id, event) {
    var socket = dashboardSockets[id];
    if (!socket) return;
    var payload = event && event.payload || {};
    var type = event && event.type;
    if (type === 'open') {
      socket.readyState = DashboardSocket.OPEN;
      socket.__emit('open', {});
      return;
    }
    if (type === 'message') {
      socket.__emit('message', { data: payload.data || '' });
      return;
    }
    if (type === 'error') {
      socket.__emit('error', { message: payload.message || 'WebSocket error' });
      return;
    }
    if (type === 'close') {
      delete dashboardSockets[id];
      socket.readyState = DashboardSocket.CLOSED;
      socket.__emit('close', {
        code: payload.code || 1000,
        reason: payload.reason || '',
        wasClean: (payload.code || 1000) === 1000
      });
    }
  };
  function DashboardSocket(url, protocols) {
    var target = normalizeDashboardUrl(new URL(String(url), window.location.href));
    if (!target) {
      return protocols === undefined ? new NativeWebSocket(url) : new NativeWebSocket(url, protocols);
    }
    var listeners = {};
    var self = this;
    var id = Math.random().toString(36).slice(2) + Date.now().toString(36);
    dashboardSockets[id] = this;
    this.readyState = DashboardSocket.CONNECTING;
    this.url = target.href;
    this.protocol = '';
    this.extensions = '';
    this.binaryType = 'blob';
    this.addEventListener = function(type, listener) {
      (listeners[type] || (listeners[type] = [])).push(listener);
    };
    this.removeEventListener = function(type, listener) {
      var list = listeners[type] || [];
      var index = list.indexOf(listener);
      if (index >= 0) list.splice(index, 1);
    };
    this.__emit = function(type, event) {
      event = event || {};
      event.type = type;
      event.target = self;
      if (typeof self['on' + type] === 'function') self['on' + type](event);
      (listeners[type] || []).slice().forEach(function(listener) { listener.call(self, event); });
    };
    this.close = function() {
      if (self.readyState === DashboardSocket.CLOSING || self.readyState === DashboardSocket.CLOSED) return;
      self.readyState = DashboardSocket.CLOSING;
      window.nb4aClashApi.closeWebSocket(id);
    };
    this.send = function() {};
    setTimeout(function() { window.nb4aClashApi.openWebSocket(id, target.href); }, 0);
  }
  DashboardSocket.CONNECTING = NativeWebSocket.CONNECTING;
  DashboardSocket.OPEN = NativeWebSocket.OPEN;
  DashboardSocket.CLOSING = NativeWebSocket.CLOSING;
  DashboardSocket.CLOSED = NativeWebSocket.CLOSED;
  window.WebSocket = DashboardSocket;
})();
</script>
"""
    }
}
