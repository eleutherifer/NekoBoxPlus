package io.nekohasekai.sagernet.utils

import android.util.Base64
import io.nekohasekai.sagernet.CertProvider
import io.nekohasekai.sagernet.database.DataStore
import libcore.Libcore
import libcore.StringIterator
import java.security.KeyStore

fun loadRootCACerts(provider: Int = DataStore.certProvider) {
    var certOption = Libcore.CertMozilla
    var certList: StringIterator? = null
    when (provider) {
        CertProvider.SYSTEM -> {
            certOption = Libcore.CertGoOrigin
        }

        CertProvider.MOZILLA -> {
            certOption = Libcore.CertMozilla
        }

        CertProvider.SYSTEM_AND_USER -> {
            certOption = Libcore.CertWithUserTrust
            certList = loadCertFromAndroidStore().toStringIterator()
        }

        CertProvider.CHROME -> {
            certOption = Libcore.CertChrome
        }
    }
    Libcore.updateRootCACerts(certOption, certList)
}

private fun loadCertFromAndroidStore(): List<String> {
    val certificates = mutableListOf<String>()
    val keyStore = KeyStore.getInstance("AndroidCAStore")
    keyStore.load(null, null)
    val aliases = keyStore.aliases()
    while (aliases.hasMoreElements()) {
        val cert = keyStore.getCertificate(aliases.nextElement())
        certificates.add(
            "-----BEGIN CERTIFICATE-----\n" +
                Base64.encodeToString(cert.encoded, Base64.NO_WRAP) +
                "\n-----END CERTIFICATE-----",
        )
    }
    return certificates
}

private fun Iterable<String>.toStringIterator(): StringIterator {
    val values = toList()
    return object : StringIterator {
        private val iterator = values.iterator()

        override fun hasNext(): Boolean = iterator.hasNext()

        override fun next(): String = iterator.next()

        override fun length(): Int = values.size
    }
}
