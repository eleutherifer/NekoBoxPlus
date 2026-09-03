package io.nekohasekai.sagernet.fmt.ssh

import com.google.common.io.BaseEncoding
import io.nekohasekai.sagernet.fmt.subscriptionLines
import io.nekohasekai.sagernet.fmt.subscriptionValue
import io.nekohasekai.sagernet.ktx.toLink
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private fun String.decodeUrlComponent(): String =
    URLDecoder.decode(replace("+", "%2B"), StandardCharsets.UTF_8.name())

private fun String.encodeUrlComponent(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")

private fun decodeBase64(value: String): String {
    val padded = value + "=".repeat((4 - value.length % 4) % 4)
    return String(BaseEncoding.base64().decode(padded), StandardCharsets.UTF_8)
}

private fun decodeBase64List(value: String): String =
    runCatching {
        value.split('-')
            .mapNotNull { it.takeIf(String::isNotEmpty)?.let(::decodeBase64) }
            .joinToString("\n")
    }.getOrElse {
        value.replace(',', '\n')
    }

private fun encodeBase64(value: String): String =
    BaseEncoding.base64().omitPadding().encode(value.toByteArray(StandardCharsets.UTF_8))

private fun URI.queryParameters(): Map<String, String> =
    rawQuery.orEmpty().split('&').mapNotNull { item ->
        if (item.isEmpty()) return@mapNotNull null
        val parts = item.split('=', limit = 2)
        parts[0].decodeUrlComponent() to parts.getOrElse(1) { "" }.decodeUrlComponent()
    }.toMap()

fun parseSSH(link: String): SSHBean {
    val uri = URI(link)
    require(uri.scheme.equals("ssh", ignoreCase = true)) { "Not an SSH link" }
    val host = uri.host ?: error("Missing SSH server")
    val query = uri.queryParameters()
    val userInfo = uri.rawUserInfo?.split(':', limit = 2).orEmpty()
    val privateKey = query["private_key"]?.takeIf { it.isNotEmpty() }?.let {
        runCatching { decodeBase64(it) }.getOrDefault(it)
    }.orEmpty()
    val passwordPresent = query.containsKey("password") || userInfo.size > 1

    return SSHBean().apply {
        serverAddress = host
        serverPort = uri.port.takeIf { it > 0 } ?: 22
        username = query["user"]
            ?: userInfo.firstOrNull()?.decodeUrlComponent()?.takeIf { it.isNotEmpty() }
            ?: "root"
        password = query["password"] ?: userInfo.getOrNull(1)?.decodeUrlComponent().orEmpty()
        this.privateKey = privateKey
        privateKeyPath = query["private_key_path"].orEmpty()
        privateKeyPassphrase = query["private_key_passphrase"].orEmpty()
        publicKey = decodeBase64List(query["host_key"].orEmpty())
        hostKeyAlgorithms = decodeBase64List(query["host_key_algorithms"].orEmpty())
        clientVersion = query["client_version"].orEmpty()
        cipher = query["cipher"].orEmpty().replace(',', '\n')
        mac = query["mac"].orEmpty().replace(',', '\n')
        kexAlgorithm = query["kex_algorithm"].orEmpty().replace(',', '\n')
        name = uri.rawFragment?.decodeUrlComponent().orEmpty()
        authType = when {
            privateKey.isNotEmpty() || privateKeyPath.isNotEmpty() -> SSHBean.AUTH_TYPE_PRIVATE_KEY
            passwordPresent -> SSHBean.AUTH_TYPE_PASSWORD
            else -> SSHBean.AUTH_TYPE_NONE
        }
        initializeDefaultValues()
    }
}

fun SSHBean.toUri(): String {
    val builder = HttpUrl.Builder()
        .scheme("http")
        .host(serverAddress)
        .port(serverPort.takeIf { it in 1..65535 } ?: 22)

    if (username.isNotEmpty()) builder.addQueryParameter("user", username)
    when (authType) {
        SSHBean.AUTH_TYPE_PASSWORD -> {
            if (password.isNotEmpty()) builder.addQueryParameter("password", password)
        }
        SSHBean.AUTH_TYPE_PRIVATE_KEY -> {
            if (privateKey.isNotEmpty()) {
                builder.addQueryParameter("private_key", encodeBase64(privateKey))
            }
            if (privateKeyPath.isNotEmpty()) {
                builder.addQueryParameter("private_key_path", privateKeyPath)
            }
            if (privateKeyPassphrase.isNotEmpty()) {
                builder.addQueryParameter("private_key_passphrase", privateKeyPassphrase)
            }
        }
    }
    publicKey.listByLineOrComma().takeIf { it.isNotEmpty() }?.let { keys ->
        builder.addQueryParameter("host_key", keys.joinToString("-") { encodeBase64(it) })
    }
    hostKeyAlgorithms.listByLineOrComma().takeIf { it.isNotEmpty() }?.let { algorithms ->
        builder.addQueryParameter(
            "host_key_algorithms",
            algorithms.joinToString("-") { encodeBase64(it) },
        )
    }
    if (clientVersion.isNotEmpty()) builder.addQueryParameter("client_version", clientVersion)
    if (cipher.isNotEmpty()) builder.addQueryParameter("cipher", cipher.replace('\n', ','))
    if (mac.isNotEmpty()) builder.addQueryParameter("mac", mac.replace('\n', ','))
    if (kexAlgorithm.isNotEmpty()) {
        builder.addQueryParameter("kex_algorithm", kexAlgorithm.replace('\n', ','))
    }
    if (name.isNotEmpty()) builder.encodedFragment(name.encodeUrlComponent())
    return builder.toLink("ssh", appendDefaultPort = false)
        .replaceFirst("/?", "?")
        .replaceFirst("/#", "#")
        .removeSuffix("/")
}

fun parseClashSSH(proxy: Map<String, Any?>): SSHBean = SSHBean().apply {
    name = proxy.subscriptionValue("name")?.toString() ?: ""
    serverAddress = proxy.subscriptionValue("server")?.toString().orEmpty()
    serverPort = proxy.subscriptionValue("port", "server-port")?.toString()?.toIntOrNull() ?: 22
    username = proxy.subscriptionValue("user", "username")?.toString() ?: "root"
    when {
        proxy.subscriptionValue("private-key") != null ||
                proxy.subscriptionValue("private-key-path") != null -> {
            authType = SSHBean.AUTH_TYPE_PRIVATE_KEY
            privateKey = proxy.subscriptionValue("private-key")?.toString().orEmpty()
            privateKeyPath = proxy.subscriptionValue("private-key-path")?.toString().orEmpty()
            privateKeyPassphrase =
                proxy.subscriptionValue("private-key-passphrase")?.toString().orEmpty()
        }
        proxy.subscriptionValue("password") != null -> {
            authType = SSHBean.AUTH_TYPE_PASSWORD
            password = proxy.subscriptionValue("password").toString()
        }
        else -> authType = SSHBean.AUTH_TYPE_NONE
    }
    publicKey = proxy.subscriptionValue("host-key").subscriptionLines()
    hostKeyAlgorithms = proxy.subscriptionValue("host-key-algorithms").subscriptionLines()
    clientVersion = proxy.subscriptionValue("client-version")?.toString().orEmpty()
    cipher = proxy.subscriptionValue("cipher").subscriptionLines()
    mac = proxy.subscriptionValue("mac").subscriptionLines()
    kexAlgorithm = proxy.subscriptionValue("kex-algorithm").subscriptionLines()
    initializeDefaultValues()
}

fun buildSingBoxOutboundSSHBean(bean: SSHBean): SingBoxOptions.Outbound_SSHOptions {
    return SingBoxOptions.Outbound_SSHOptions().apply {
        type = "ssh"
        server = bean.serverAddress
        server_port = bean.serverPort
        user = bean.username
        if (bean.publicKey.isNotBlank()) {
            host_key = bean.publicKey.listByLineOrComma()
        }
        host_key_algorithms = bean.hostKeyAlgorithms.takeIf { it.isNotBlank() }?.listByLineOrComma()
        client_version = bean.clientVersion.takeIf { it.isNotBlank() }
        cipher = bean.cipher.takeIf { it.isNotBlank() }?.listByLineOrComma()
        mac = bean.mac.takeIf { it.isNotBlank() }?.listByLineOrComma()
        kex_algorithm = bean.kexAlgorithm.takeIf { it.isNotBlank() }?.listByLineOrComma()
        when (bean.authType) {
            SSHBean.AUTH_TYPE_PRIVATE_KEY -> {
                private_key = bean.privateKey
                private_key_path = bean.privateKeyPath
                private_key_passphrase = bean.privateKeyPassphrase
            }
            SSHBean.AUTH_TYPE_PASSWORD -> {
                password = bean.password
            }
        }
    }
}
