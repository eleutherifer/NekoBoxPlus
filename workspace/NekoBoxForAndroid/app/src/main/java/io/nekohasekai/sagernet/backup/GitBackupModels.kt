package io.nekohasekai.sagernet.backup

import java.net.URI
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository

data class GitBackupConfig(
    val repositoryUrl: String,
    val username: String,
    val branch: String,
    val credential: String,
    val encryptionPassword: String,
)

data class GitRestorePoint(
    val commitId: String,
    val timestampMillis: Long,
    val message: String,
    val verifiedContainer: Boolean,
)

object GitBackupConfigValidator {
    fun validateHttpsUrl(value: String): String {
        val uri = runCatching { URI(value.trim()) }.getOrElse {
            throw IllegalArgumentException("Invalid repository URL")
        }
        require(uri.scheme.equals("https", ignoreCase = true)) { "Only HTTPS repositories are supported" }
        require(uri.host?.isNotBlank() == true) { "Repository host is missing" }
        require(uri.userInfo == null) { "Credentials must not be embedded in the repository URL" }
        require(uri.fragment == null) { "Repository URL must not contain a fragment" }
        return uri.toASCIIString()
    }

    fun validateBranch(value: String): String {
        val branch = value.trim()
        require(branch.isNotEmpty() && !branch.startsWith('-')) { "Invalid branch name" }
        require(Repository.isValidRefName("${Constants.R_HEADS}$branch")) { "Invalid branch name" }
        return branch
    }
}
