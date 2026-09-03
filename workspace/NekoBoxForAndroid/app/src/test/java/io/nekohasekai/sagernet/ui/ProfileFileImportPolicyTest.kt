package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileFileImportPolicyTest {
    @Test
    fun `recognizes image MIME types`() {
        assertEquals(
            ProfileFileImportKind.IMAGE,
            ProfileFileImportPolicy.classify("shared-file", "image/png"),
        )
        assertEquals(
            ProfileFileImportKind.IMAGE,
            ProfileFileImportPolicy.classify("shared-file", "IMAGE/JPEG; charset=binary"),
        )
    }

    @Test
    fun `uses image extension when MIME type is unavailable or generic`() {
        assertEquals(
            ProfileFileImportKind.IMAGE,
            ProfileFileImportPolicy.classify("qr-code.PNG", null),
        )
        assertEquals(
            ProfileFileImportKind.IMAGE,
            ProfileFileImportPolicy.classify("qr-code.webp", "application/octet-stream"),
        )
    }

    @Test
    fun `zip extension takes precedence over MIME type`() {
        assertEquals(
            ProfileFileImportKind.ZIP,
            ProfileFileImportPolicy.classify("profiles.ZIP", "image/png"),
        )
    }

    @Test
    fun `keeps ordinary configuration files on text path`() {
        assertEquals(
            ProfileFileImportKind.TEXT,
            ProfileFileImportPolicy.classify("profiles.yaml", "application/yaml"),
        )
        assertEquals(
            ProfileFileImportKind.TEXT,
            ProfileFileImportPolicy.classify(null, null),
        )
    }
}
