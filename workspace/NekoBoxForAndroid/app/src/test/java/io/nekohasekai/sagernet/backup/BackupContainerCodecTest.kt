package io.nekohasekai.sagernet.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupContainerCodecTest {
    private val password = "correct horse battery staple".toCharArray()

    @Test
    fun `encrypted backup has supported header and round trips`() {
        val content = """{"message":"Привет, NekoBox+ 🐈"}""".toByteArray()
        val encrypted = BackupContainerCodec.encrypt(content, password)

        assertTrue(encrypted.copyOfRange(0, 9).contentEquals("NBPLUSBAK".toByteArray()))
        assertTrue(BackupContainerCodec.isSupportedContainer(encrypted))
        assertArrayEquals(content, BackupContainerCodec.decrypt(encrypted, password))
    }

    @Test
    fun `same content produces different encrypted containers`() {
        val content = "content".toByteArray()

        val first = BackupContainerCodec.encrypt(content, password)
        val second = BackupContainerCodec.encrypt(content, password)

        assertFalse(first.contentEquals(second))
    }

    @Test(expected = BackupPasswordException::class)
    fun `wrong password is rejected`() {
        val encrypted = BackupContainerCodec.encrypt("secret".toByteArray(), password)
        BackupContainerCodec.decrypt(encrypted, "wrong password".toCharArray())
    }

    @Test(expected = BackupPasswordException::class)
    fun `ciphertext tampering is rejected`() {
        val encrypted = BackupContainerCodec.encrypt("secret".toByteArray(), password)
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        BackupContainerCodec.decrypt(encrypted, password)
    }

    @Test
    fun `invalid and truncated containers are not eligible`() {
        assertFalse(BackupContainerCodec.isSupportedContainer("NBPLUSBAK".toByteArray()))
        assertFalse(BackupContainerCodec.isSupportedContainer(ByteArray(64)))
    }
}
