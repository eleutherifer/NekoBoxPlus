package io.nekohasekai.sagernet.backup

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object BackupContainerCodec {
    private val magic = "NBPLUSBAK".toByteArray(Charsets.US_ASCII)
    private const val version = 1
    private const val saltSize = 16
    private const val nonceSize = 12
    private const val iterations = 120_000
    private const val keySize = 32

    fun encrypt(content: ByteArray, password: CharArray, random: SecureRandom = SecureRandom()): ByteArray {
        require(password.isNotEmpty()) { "Encryption password is empty" }
        val salt = ByteArray(saltSize).also(random::nextBytes)
        val nonce = ByteArray(nonceSize).also(random::nextBytes)
        val prefix = ByteArrayOutputStream().apply {
            write(magic)
            writeVarInt(version)
            write(salt)
            write(nonce)
        }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = deriveKey(password, salt)
        return try {
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(prefix)
            prefix + cipher.doFinal(content)
        } finally {
            key.fill(0)
        }
    }

    fun decrypt(container: ByteArray, password: CharArray): ByteArray {
        val parsed = parse(container)
        val key = deriveKey(password, parsed.salt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, parsed.nonce))
            cipher.updateAAD(container.copyOfRange(0, parsed.contentOffset))
            cipher.doFinal(container, parsed.contentOffset, container.size - parsed.contentOffset)
        } catch (error: AEADBadTagException) {
            throw BackupPasswordException(error)
        } finally {
            key.fill(0)
        }
    }

    fun isSupportedContainer(container: ByteArray): Boolean = runCatching {
        parse(container)
        true
    }.getOrDefault(false)

    private data class Parsed(val salt: ByteArray, val nonce: ByteArray, val contentOffset: Int)

    private fun parse(container: ByteArray): Parsed {
        if (container.size < magic.size + 1 + saltSize + nonceSize + 16) {
            throw InvalidBackupContainerException("Backup container is truncated")
        }
        if (!container.copyOfRange(0, magic.size).contentEquals(magic)) {
            throw InvalidBackupContainerException("Invalid backup header")
        }
        val (containerVersion, versionBytes) = readVarInt(container, magic.size)
        if (containerVersion != version) {
            throw UnsupportedBackupVersionException(containerVersion)
        }
        val saltOffset = magic.size + versionBytes
        val nonceOffset = saltOffset + saltSize
        val contentOffset = nonceOffset + nonceSize
        if (contentOffset + 16 > container.size) {
            throw InvalidBackupContainerException("Backup container is truncated")
        }
        return Parsed(
            container.copyOfRange(saltOffset, nonceOffset),
            container.copyOfRange(nonceOffset, contentOffset),
            contentOffset,
        )
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val passwordBytes = password.concatToString().toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(passwordBytes, "HmacSHA256"))
        passwordBytes.fill(0)
        val output = ByteArray(keySize)
        var block = 1
        var outputOffset = 0
        while (outputOffset < output.size) {
            mac.reset()
            mac.update(salt)
            mac.update(byteArrayOf(
                (block ushr 24).toByte(), (block ushr 16).toByte(),
                (block ushr 8).toByte(), block.toByte(),
            ))
            var u = mac.doFinal()
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (index in t.indices) t[index] = (t[index].toInt() xor u[index].toInt()).toByte()
            }
            val count = minOf(t.size, output.size - outputOffset)
            t.copyInto(output, outputOffset, 0, count)
            outputOffset += count
            block++
        }
        return output
    }

    private fun ByteArrayOutputStream.writeVarInt(value: Int) {
        var remaining = value
        while (true) {
            if (remaining and 0x7f.inv() == 0) {
                write(remaining)
                return
            }
            write((remaining and 0x7f) or 0x80)
            remaining = remaining ushr 7
        }
    }

    private fun readVarInt(bytes: ByteArray, offset: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var index = offset
        while (index < bytes.size && shift <= 28) {
            val value = bytes[index].toInt() and 0xff
            result = result or ((value and 0x7f) shl shift)
            index++
            if (value and 0x80 == 0) return result to (index - offset)
            shift += 7
        }
        throw InvalidBackupContainerException("Invalid backup version")
    }
}

class BackupPasswordException(cause: Throwable) : Exception("Incorrect encryption password or corrupted backup", cause)
class InvalidBackupContainerException(message: String) : Exception(message)
class UnsupportedBackupVersionException(val version: Int) : Exception("Unsupported backup version: $version")
