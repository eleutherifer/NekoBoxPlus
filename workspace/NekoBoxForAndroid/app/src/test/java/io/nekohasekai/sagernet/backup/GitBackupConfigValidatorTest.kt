package io.nekohasekai.sagernet.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class GitBackupConfigValidatorTest {
    @Test
    fun `accepts ordinary HTTPS repository`() {
        assertEquals(
            "https://example.com/user/repository.git",
            GitBackupConfigValidator.validateHttpsUrl(" https://example.com/user/repository.git "),
        )
    }

    @Test
    fun `rejects insecure SSH and embedded credentials`() {
        assertRejected { GitBackupConfigValidator.validateHttpsUrl("http://example.com/repo.git") }
        assertRejected { GitBackupConfigValidator.validateHttpsUrl("ssh://git@example.com/repo.git") }
        assertRejected { GitBackupConfigValidator.validateHttpsUrl("https://token@example.com/repo.git") }
    }

    @Test
    fun `validates branch names`() {
        assertEquals("backup/main", GitBackupConfigValidator.validateBranch(" backup/main "))
        assertRejected { GitBackupConfigValidator.validateBranch("../main") }
        assertRejected { GitBackupConfigValidator.validateBranch("bad branch") }
        assertRejected { GitBackupConfigValidator.validateBranch("main.lock") }
        assertRejected { GitBackupConfigValidator.validateBranch("backup//main") }
    }

    private fun assertRejected(block: () -> Unit) {
        check(runCatching(block).exceptionOrNull() is IllegalArgumentException)
    }
}
