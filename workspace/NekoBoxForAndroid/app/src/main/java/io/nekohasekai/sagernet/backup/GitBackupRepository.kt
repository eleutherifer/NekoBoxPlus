package io.nekohasekai.sagernet.backup

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefLeaseSpec
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.HttpTransport
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.transport.UserAgent
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GitBackupRepository(private val cacheDirectory: File) {
    companion object {
        const val backupFileName = "nekobox_plus_backup.bin"
        const val httpUserAgent = "git/2.55.0"
        private val backupMessage = Regex("""NekoBox\+ Backup - \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")
    }

    init {
        UserAgent.set(httpUserAgent)
        HttpTransport.setConnectionFactory(GitHttpConnectionFactory)
    }

    fun listBranches(config: GitBackupConfig): List<String> {
        return Git.lsRemoteRepository()
            .setRemote(config.repositoryUrl)
            .setCredentialsProvider(credentials(config))
            .setHeads(true)
            .call()
            .mapNotNull { it.name.removePrefix(Constants.R_HEADS).takeIf { name -> name != it.name } }
            .sorted()
    }

    /**
     * Ensures the selected branch exists without overwriting a branch that appeared remotely
     * while the configuration screen was open.
     *
     * @return true when this call created the branch, false when it already existed.
     */
    fun ensureBranch(config: GitBackupConfig): Boolean {
        if (config.branch in listBranches(config)) return false
        val git = synchronize(config, recreate = true)
        return git.use {
            if (config.branch in listBranches(config)) return false
            val now = Date()
            val identity = PersonIdent(
                "NekoBox+",
                "nekobox-plus@localhost",
                now,
                java.util.TimeZone.getDefault(),
            )
            it.commit()
                .setAllowEmpty(true)
                .setSign(false)
                .setAuthor(identity)
                .setCommitter(identity)
                .setMessage("NekoBox+ Initialize backup branch")
                .call()
            val update = it.push()
                .setRemote("origin")
                .setCredentialsProvider(credentials(config))
                .setRefSpecs(RefSpec("HEAD:${Constants.R_HEADS}${config.branch}"))
                .call()
                .flatMap { push -> push.remoteUpdates }
                .singleOrNull()
            if (update?.status?.isSuccessful == true) return true
            // Another client may have created the branch after our final check. In that case,
            // keep their branch and treat the requested state as satisfied.
            if (config.branch in listBranches(config)) {
                runCatching { synchronize(config, recreate = true).close() }
                return false
            }
            throw GitBackupException("Branch creation failed: ${update?.status ?: "no result"}")
        }
    }

    fun backup(config: GitBackupConfig, encryptedBackup: ByteArray, now: Date = Date()): String {
        var lastFailure: Exception? = null
        repeat(2) {
            try {
                val git = synchronize(config, recreate = it > 0)
                git.use {
                    File(cacheDirectory, backupFileName).writeBytes(encryptedBackup)
                    it.add().addFilepattern(backupFileName).call()
                    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)
                    val identity = PersonIdent("NekoBox+", "nekobox-plus@localhost", now, java.util.TimeZone.getDefault())
                    val commit = it.commit()
                        .setMessage("NekoBox+ Backup - $timestamp")
                        .setAuthor(identity)
                        .setCommitter(identity)
                        .setSign(false)
                        .call()
                    val result = it.push()
                        .setRemote("origin")
                        .setCredentialsProvider(credentials(config))
                        .setRefSpecs(RefSpec("HEAD:${Constants.R_HEADS}${config.branch}"))
                        .call()
                        .flatMap { push -> push.remoteUpdates }
                        .singleOrNull()
                    if (result?.status?.isSuccessful == true) return commit.name
                    throw GitBackupException("Push failed: ${result?.status ?: "no result"}")
                }
            } catch (error: Exception) {
                lastFailure = error
            }
        }
        throw GitBackupException("Unable to update the remote branch after resynchronizing", lastFailure)
    }

    fun listRestorePoints(config: GitBackupConfig): List<GitRestorePoint> {
        val git = synchronize(config)
        return git.use {
            val commits = firstParentHistory(it.repository)
            val inspected = runCatching {
                commits.mapNotNull { commit ->
                    readFile(it.repository, commit, backupFileName)?.let { bytes ->
                        if (BackupContainerCodec.isSupportedContainer(bytes)) {
                            commit.toRestorePoint(verified = true)
                        } else null
                    }
                }
            }
            inspected.getOrElse {
                commits.filter { commit -> backupMessage.matches(commit.fullMessage.trim()) }
                    .map { commit -> commit.toRestorePoint(verified = false) }
            }
        }
    }

    fun readBackup(config: GitBackupConfig, commitId: String): ByteArray {
        val git = synchronize(config)
        return git.use {
            val id = it.repository.resolve(commitId) ?: throw GitBackupException("Commit no longer exists")
            RevWalk(it.repository).use { walk ->
                val commit = walk.parseCommit(id)
                readFile(it.repository, commit, backupFileName)
                    ?: throw GitBackupException("The selected commit has no backup file")
            }
        }
    }

    fun compact(config: GitBackupConfig, versionsToKeep: Int): CompactResult {
        require(versionsToKeep > 0) { "Versions to keep must be positive" }
        val git = synchronize(config)
        return git.use {
            val repository = it.repository
            val oldHead = repository.resolve("refs/remotes/origin/${config.branch}")
                ?: repository.resolve(Constants.HEAD)
                ?: throw GitBackupException("Remote branch is empty")
            val history = firstParentHistory(repository)
            val eligibleIndexes = history.mapIndexedNotNull { index, commit ->
                readFile(repository, commit, backupFileName)
                    ?.takeIf(BackupContainerCodec::isSupportedContainer)
                    ?.let { index }
            }
            if (eligibleIndexes.size <= versionsToKeep) {
                return CompactResult(eligibleIndexes.size, changed = false)
            }
            val cutoffIndex = eligibleIndexes[versionsToKeep - 1]
            val retainedNewestFirst = history.take(cutoffIndex + 1)
            if (retainedNewestFirst.any { commit -> commit.parentCount > 1 }) {
                throw GitBackupException("Compaction of merge history is not supported")
            }

            var newParent: ObjectId? = null
            repository.newObjectInserter().use { inserter ->
                for (original in retainedNewestFirst.asReversed()) {
                    val builder = CommitBuilder().apply {
                        setTreeId(original.tree)
                        setAuthor(original.authorIdent)
                        setCommitter(original.committerIdent)
                        setMessage(original.fullMessage)
                        if (newParent != null) setParentId(newParent)
                    }
                    newParent = inserter.insert(builder)
                }
                inserter.flush()
            }
            val rewrittenHead = checkNotNull(newParent)
            val localRef = repository.updateRef("${Constants.R_HEADS}${config.branch}").apply {
                setNewObjectId(rewrittenHead)
                setExpectedOldObjectId(repository.resolve(Constants.HEAD))
                setForceUpdate(true)
            }
            if (localRef.update() !in setOf(RefUpdate.Result.FORCED, RefUpdate.Result.NEW, RefUpdate.Result.FAST_FORWARD)) {
                throw GitBackupException("Unable to update the local compacted history")
            }
            val update = it.push()
                .setRemote("origin")
                .setCredentialsProvider(credentials(config))
                .setForce(true)
                .setRefSpecs(RefSpec("${Constants.R_HEADS}${config.branch}:${Constants.R_HEADS}${config.branch}"))
                .setRefLeaseSpecs(RefLeaseSpec("${Constants.R_HEADS}${config.branch}", oldHead.name))
                .call()
                .flatMap { push -> push.remoteUpdates }
                .singleOrNull()
            if (update?.status?.isSuccessful != true) {
                runCatching { synchronize(config, recreate = true).close() }
                throw GitBackupException("Force-push was rejected: ${update?.status ?: "no result"}")
            }
            CompactResult(versionsToKeep, changed = true)
        }
    }

    fun clearCache() {
        if (cacheDirectory.exists() && !cacheDirectory.deleteRecursively()) {
            throw GitBackupException("Unable to clear the Git backup cache")
        }
    }

    private fun synchronize(config: GitBackupConfig, recreate: Boolean = false): Git {
        if (recreate) clearCache()
        if (!File(cacheDirectory, ".git").isDirectory) {
            clearCache()
            cacheDirectory.parentFile?.mkdirs()
            return cloneOrInitialize(config)
        }
        try {
            val git = Git.open(cacheDirectory)
            val origin = git.repository.config.getString("remote", "origin", "url")
            if (origin != config.repositoryUrl) {
                git.close()
                clearCache()
                return cloneOrInitialize(config)
            }
            syncBranch(git, config)
            return git
        } catch (error: Exception) {
            clearCache()
            return cloneOrInitialize(config)
        }
    }

    private fun cloneOrInitialize(config: GitBackupConfig): Git {
        return try {
            Git.cloneRepository()
                .setURI(config.repositoryUrl)
                .setDirectory(cacheDirectory)
                .setNoCheckout(true)
                .setCredentialsProvider(credentials(config))
                .call()
                .also { syncBranch(it, config) }
        } catch (error: Exception) {
            clearCache()
            cacheDirectory.mkdirs()
            val git = Git.init().setDirectory(cacheDirectory).call()
            git.remoteAdd().setName("origin").setUri(org.eclipse.jgit.transport.URIish(config.repositoryUrl)).call()
            try {
                syncBranch(git, config)
                git
            } catch (syncError: Exception) {
                git.close()
                clearCache()
                throw GitBackupException("Unable to clone repository", error)
            }
        }
    }

    private fun syncBranch(git: Git, config: GitBackupConfig) {
        val remoteExists = Git.lsRemoteRepository()
            .setRemote(config.repositoryUrl)
            .setCredentialsProvider(credentials(config))
            .setHeads(true)
            .call()
            .any { it.name == "${Constants.R_HEADS}${config.branch}" }
        if (remoteExists) {
            git.fetch()
                .setRemote("origin")
                .setCredentialsProvider(credentials(config))
                .setRefSpecs(RefSpec("+${Constants.R_HEADS}${config.branch}:refs/remotes/origin/${config.branch}"))
                .call()
        }
        if (!remoteExists && (
                git.repository.resolve("refs/remotes/origin/${config.branch}") != null ||
                    git.repository.resolve("${Constants.R_HEADS}${config.branch}") != null
                )
        ) {
            throw StaleLocalBranchException()
        }
        val remote = git.repository.resolve("refs/remotes/origin/${config.branch}")
        if (remote == null) {
            val result = git.repository.updateRef(Constants.HEAD)
                .link("${Constants.R_HEADS}${config.branch}")
            if (result !in setOf(RefUpdate.Result.NEW, RefUpdate.Result.NO_CHANGE, RefUpdate.Result.FORCED)) {
                throw GitBackupException("Unable to create branch ${config.branch}")
            }
            return
        }
        val local = git.repository.findRef("${Constants.R_HEADS}${config.branch}")
        git.checkout().apply {
            setName(config.branch)
            setForced(true)
            if (local == null) {
                setCreateBranch(true)
                setStartPoint(remote.name)
            }
        }.call()
        git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef(remote.name).call()
        git.clean().setCleanDirectories(true).setForce(true).call()
    }

    private fun firstParentHistory(repository: Repository): List<RevCommit> {
        val head = repository.resolve(Constants.HEAD) ?: return emptyList()
        return RevWalk(repository).use { walk ->
            val result = mutableListOf<RevCommit>()
            var current: RevCommit? = walk.parseCommit(head)
            while (current != null) {
                result += current
                current = if (current.parentCount == 0) null else walk.parseCommit(current.getParent(0))
            }
            result
        }
    }

    private fun readFile(repository: Repository, commit: RevCommit, path: String): ByteArray? {
        return TreeWalk.forPath(repository, path, commit.tree)?.use { tree ->
            if (tree.getFileMode(0) == FileMode.MISSING) null
            else repository.open(tree.getObjectId(0)).bytes
        }
    }

    private fun RevCommit.toRestorePoint(verified: Boolean) = GitRestorePoint(
        name,
        authorIdent.`when`.time,
        fullMessage.trim(),
        verified,
    )

    private fun credentials(config: GitBackupConfig): CredentialsProvider? {
        if (config.username.isBlank() && config.credential.isBlank()) return null
        return UsernamePasswordCredentialsProvider(config.username, config.credential)
    }
}

data class CompactResult(val retainedVersions: Int, val changed: Boolean)
class GitBackupException(message: String, cause: Throwable? = null) : Exception(message, cause)
private class StaleLocalBranchException : Exception()

private val org.eclipse.jgit.transport.RemoteRefUpdate.Status.isSuccessful: Boolean
    get() = this == org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK ||
        this == org.eclipse.jgit.transport.RemoteRefUpdate.Status.UP_TO_DATE
