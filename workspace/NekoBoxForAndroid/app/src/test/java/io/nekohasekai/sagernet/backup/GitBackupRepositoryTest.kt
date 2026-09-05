package io.nekohasekai.sagernet.backup

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.transport.UserAgent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Date

class GitBackupRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `uses required Git HTTP user agent`() {
        GitBackupRepository(temporaryFolder.newFolder("user-agent-cache"))
        assertEquals("git/2.55.0", UserAgent.get())
    }

    @Test
    fun `creates a missing branch once without a backup restore point`() {
        val remote = bareRepository()
        val repository = repository(remote)

        assertTrue(repository.ensureBranch(config(remote)))
        assertFalse(repository.ensureBranch(config(remote)))

        assertEquals(listOf("main"), repository.listBranches(config(remote)))
        assertTrue(repository.listRestorePoints(config(remote)).isEmpty())
        repository.backup(config(remote), encrypted("first"))
        assertEquals(1, repository.listRestorePoints(config(remote)).size)
    }

    @Test
    fun `backs up to branchless repository and restores old versions newest first`() {
        val remote = bareRepository()
        val repository = repository(remote)
        val first = encrypted("first")
        val second = encrypted("second")

        repository.backup(config(remote), first, Date(1_700_000_000_000))
        repository.backup(config(remote), second, Date(1_700_000_001_000))

        assertEquals(listOf("main"), repository.listBranches(config(remote)))
        val points = repository.listRestorePoints(config(remote))
        assertEquals(2, points.size)
        assertTrue(points.all { it.verifiedContainer })
        assertTrue(points.first().message.startsWith("NekoBox+ Backup - "))
        assertArrayEquals(second, repository.readBackup(config(remote), points[0].commitId))
        assertArrayEquals(first, repository.readBackup(config(remote), points[1].commitId))
    }

    @Test
    fun `recovers corrupt cache by cloning remote again`() {
        val remote = bareRepository()
        val repository = repository(remote)
        repository.backup(config(remote), encrypted("first"))
        File(repositoryCache(), ".git/HEAD").writeText("broken")

        repository.backup(config(remote), encrypted("second"))

        assertEquals(2, repository.listRestorePoints(config(remote)).size)
    }

    @Test
    fun `does not resurrect stale cached history after remote branch deletion`() {
        val remote = bareRepository()
        val repository = repository(remote)
        repository.backup(config(remote), encrypted("old"))
        Git.open(remote).use {
            val update = it.repository.updateRef("refs/heads/main")
            update.setForceUpdate(true)
            update.delete()
        }

        repository.backup(config(remote), encrypted("new"))

        val points = repository.listRestorePoints(config(remote))
        assertEquals(1, points.size)
        assertArrayEquals(
            encryptedContent("new"),
            BackupContainerCodec.decrypt(
                repository.readBackup(config(remote), points.single().commitId),
                "password".toCharArray(),
            ),
        )
    }

    @Test
    fun `compaction retains exactly requested backup versions`() {
        val remote = bareRepository()
        val repository = repository(remote)
        repeat(5) { index ->
            repository.backup(config(remote), encrypted("backup-$index"), Date(1_700_000_000_000 + index * 1000L))
        }

        val result = repository.compact(config(remote), 2)

        assertTrue(result.changed)
        assertEquals(2, result.retainedVersions)
        assertEquals(2, repository.listRestorePoints(config(remote)).size)
        val noChange = repository.compact(config(remote), 10)
        assertFalse(noChange.changed)
        assertEquals(2, noChange.retainedVersions)
    }

    @Test
    fun `backup replaces only backup file and preserves tracked files`() {
        val remote = bareRepository()
        val seedDirectory = temporaryFolder.newFolder("seed")
        val seed = Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(seedDirectory).call()
        seed.use {
            File(seedDirectory, "README.md").writeText("keep me")
            it.add().addFilepattern("README.md").call()
            it.commit().setMessage("Initial").setAuthor("Test", "test@example.com").setSign(false).call()
            it.branchRename().setNewName("main").call()
            it.push().setRemote("origin").setRefSpecs(
                org.eclipse.jgit.transport.RefSpec("refs/heads/main:refs/heads/main"),
            ).call()
        }
        val repository = repository(remote)

        repository.backup(config(remote), encrypted("backup"))

        val checkout = temporaryFolder.newFolder("checkout")
        Git.cloneRepository().setURI(remote.toURI().toString()).setBranch("main").setDirectory(checkout).call().use {
            assertEquals("keep me", File(checkout, "README.md").readText())
            assertTrue(File(checkout, GitBackupRepository.backupFileName).isFile)
            assertEquals("main", it.repository.branch)
        }
    }

    private fun encrypted(value: String): ByteArray {
        return BackupContainerCodec.encrypt(encryptedContent(value), "password".toCharArray())
    }

    private fun encryptedContent(value: String) = value.toByteArray()

    private fun bareRepository(): File {
        val directory = temporaryFolder.newFolder("remote-${System.nanoTime()}")
        Git.init().setBare(true).setDirectory(directory).call().close()
        return directory
    }

    private fun repository(remote: File): GitBackupRepository {
        return GitBackupRepository(repositoryCache())
    }

    private fun repositoryCache(): File = temporaryFolder.root.resolve("cache")

    private fun config(remote: File) = GitBackupConfig(
        remote.toURI().toString(),
        "",
        "main",
        "",
        "password",
    )
}
