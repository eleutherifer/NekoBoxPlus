package io.nekohasekai.sagernet.fmt.ssh

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class SSHFmtTest {

    @Test
    fun fetchedHostKeyMapsUnchangedToOutbound() {
        val hostKey =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHIIB8jW2Dr0a+3z9kfGmORturBpV1wvN+6S+eGIUAC+"
        val outbound = buildSingBoxOutboundSSHBean(
            SSHBean().apply {
                initializeDefaultValues()
                serverAddress = "example.com"
                serverPort = 22
                publicKey = hostKey
            },
        )

        assertEquals(listOf(hostKey), outbound.host_key)
    }

    @Test
    fun parsesAndExportsThroneLink() {
        val privateKey = "-----BEGIN PRIVATE KEY-----\nkey+/=\n-----END PRIVATE KEY-----"
        val hostKeys = listOf(
            "ssh-ed25519 AAAA+/first",
            "ssh-rsa BBBB+/second",
        )
        val algorithms = listOf("ssh-ed25519", "rsa-sha2-512")
        fun encode(value: String) = Base64.getEncoder().withoutPadding()
            .encodeToString(value.toByteArray())
        val link = "ssh://example.com:2222" +
                "?user=user%2Bname&private_key=${encode(privateKey)}" +
                "&private_key_passphrase=pass%2Bword" +
                "&host_key=${hostKeys.joinToString("-") { encode(it) }}" +
                "&host_key_algorithms=${algorithms.joinToString("-") { encode(it) }}" +
                "&client_version=SSH-2.0-test#My%20server"

        val bean = parseSSH(link)

        assertEquals("example.com", bean.serverAddress)
        assertEquals(2222, bean.serverPort)
        assertEquals("user+name", bean.username)
        assertEquals(SSHBean.AUTH_TYPE_PRIVATE_KEY, bean.authType)
        assertEquals(privateKey, bean.privateKey)
        assertEquals("pass+word", bean.privateKeyPassphrase)
        assertEquals(hostKeys.joinToString("\n"), bean.publicKey)
        assertEquals(algorithms.joinToString("\n"), bean.hostKeyAlgorithms)
        assertEquals("SSH-2.0-test", bean.clientVersion)
        assertEquals("My server", bean.name)

        val reparsed = parseSSH(bean.toUri())
        assertEquals(bean.serverAddress, reparsed.serverAddress)
        assertEquals(bean.serverPort, reparsed.serverPort)
        assertEquals(bean.username, reparsed.username)
        assertEquals(bean.authType, reparsed.authType)
        assertEquals(bean.privateKey, reparsed.privateKey)
        assertEquals(bean.privateKeyPassphrase, reparsed.privateKeyPassphrase)
        assertEquals(bean.publicKey, reparsed.publicKey)
        assertEquals(bean.hostKeyAlgorithms, reparsed.hostKeyAlgorithms)
        assertEquals(bean.clientVersion, reparsed.clientVersion)
        assertEquals(bean.name, reparsed.name)
    }

    @Test
    fun parsesConventionalSshUserInfo() {
        val bean = parseSSH("ssh://alice:p%2Bass@[2001:db8::1]:2200#IPv6")

        assertEquals("2001:db8::1", bean.serverAddress)
        assertEquals(2200, bean.serverPort)
        assertEquals("alice", bean.username)
        assertEquals("p+ass", bean.password)
        assertEquals(SSHBean.AUTH_TYPE_PASSWORD, bean.authType)
    }
}
