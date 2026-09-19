package io.nekohasekai.sagernet.fmt.ssh

import io.nekohasekai.sagernet.ktx.toLink
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

private fun String.decodeUrlComponent(): String =
    URLDecoder.decode(replace("+", "%2B"), StandardCharsets.UTF_8.name())

private fun String.encodeUrlComponent(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")

private fun decodeBase64(value: String): String =
    String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)

private fun encodeBase64(value: String): String =
    Base64.getEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

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
    val privateKey = query["private_key"]?.takeIf { it.isNotEmpty() }?.let(::decodeBase64).orEmpty()
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
        publicKey = query["host_key"].orEmpty().split('-')
            .mapNotNull { it.takeIf(String::isNotEmpty)?.let(::decodeBase64) }
            .joinToString("\n")
        hostKeyAlgorithms = query["host_key_algorithms"].orEmpty().split('-')
            .mapNotNull { it.takeIf(String::isNotEmpty)?.let(::decodeBase64) }
            .joinToString("\n")
        clientVersion = query["client_version"].orEmpty()
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
    if (name.isNotEmpty()) builder.encodedFragment(name.encodeUrlComponent())
    return builder.toLink("ssh", appendDefaultPort = false)
        .replaceFirst("/?", "?")
        .replaceFirst("/#", "#")
        .removeSuffix("/")
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
        if (bean.hostKeyAlgorithms.isNotBlank()) {
            host_key_algorithms = bean.hostKeyAlgorithms.listByLineOrComma()
        }
        if (bean.clientVersion.isNotBlank()) {
            client_version = bean.clientVersion
        }
    }
}
