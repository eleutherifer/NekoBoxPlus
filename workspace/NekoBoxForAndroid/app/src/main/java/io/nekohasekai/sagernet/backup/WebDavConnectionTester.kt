package io.nekohasekai.sagernet.backup

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit

internal data class WebDavConnectionSettings(
    val server: String,
    val username: String,
    val password: String,
    val path: String,
)

internal sealed interface WebDavConnectionResult {
    data object Success : WebDavConnectionResult
    data class Failure(
        val reason: WebDavFailureReason,
        val responseCode: Int? = null,
        val detail: String? = null,
    ) : WebDavConnectionResult
}

internal enum class WebDavFailureReason {
    EmptyServer,
    Authentication,
    PermissionDenied,
    NotFound,
    ServerError,
    Connection,
    CreateDirectory,
    Other,
}

internal class WebDavConnectionTester(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build(),
) {
    fun test(settings: WebDavConnectionSettings): WebDavConnectionResult {
        if (settings.server.isBlank()) {
            return WebDavConnectionResult.Failure(WebDavFailureReason.EmptyServer)
        }
        val baseUrl = settings.server.toHttpUrlOrNull()
            ?: return WebDavConnectionResult.Failure(WebDavFailureReason.NotFound)
        val authorization = Credentials.basic(settings.username, settings.password)
        return try {
            val authRequest = Request.Builder()
                .url(baseUrl)
                .method("PROPFIND", null)
                .header("Authorization", authorization)
                .header("Depth", "0")
                .build()
            client.newCall(authRequest).execute().use { response ->
                classifyResponse(response.code)?.let { return it }
            }

            val normalizedPath = settings.path.trim('/')
            if (normalizedPath.isNotEmpty()) {
                val directoryUrl = buildDirectoryUrl(baseUrl, normalizedPath)
                val directoryRequest = Request.Builder()
                    .url(directoryUrl)
                    .method("MKCOL", null)
                    .header("Authorization", authorization)
                    .build()
                client.newCall(directoryRequest).execute().use { response ->
                    if (!response.isSuccessful && response.code != HTTP_METHOD_NOT_ALLOWED) {
                        return WebDavConnectionResult.Failure(
                            WebDavFailureReason.CreateDirectory,
                            responseCode = response.code,
                        )
                    }
                }
            }
            WebDavConnectionResult.Success
        } catch (error: Exception) {
            WebDavConnectionResult.Failure(
                WebDavFailureReason.Other,
                detail = error.message,
            )
        }
    }

    companion object {
        private const val HTTP_METHOD_NOT_ALLOWED = 405

        internal fun buildDirectoryUrl(baseUrl: HttpUrl, path: String): HttpUrl {
            return baseUrl.newBuilder().apply {
                path.split('/').filter { it.isNotEmpty() }.forEach(::addPathSegment)
            }.build()
        }

        internal fun classifyResponse(code: Int): WebDavConnectionResult.Failure? = when (code) {
            401 -> WebDavConnectionResult.Failure(WebDavFailureReason.Authentication, code)
            403 -> WebDavConnectionResult.Failure(WebDavFailureReason.PermissionDenied, code)
            404 -> WebDavConnectionResult.Failure(WebDavFailureReason.NotFound, code)
            in 500..599 -> WebDavConnectionResult.Failure(WebDavFailureReason.ServerError, code)
            in 200..299 -> null
            else -> WebDavConnectionResult.Failure(WebDavFailureReason.Connection, code)
        }
    }
}
