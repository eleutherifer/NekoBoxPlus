package io.nekohasekai.sagernet.ui

import java.util.Locale

internal enum class ProfileFileImportKind {
    ZIP,
    IMAGE,
    TEXT,
}

internal object ProfileFileImportPolicy {
    private val imageExtensions = setOf(
        "avif",
        "bmp",
        "gif",
        "heic",
        "heif",
        "jpeg",
        "jpg",
        "png",
        "webp",
    )

    fun classify(fileName: String?, mimeType: String?): ProfileFileImportKind {
        val extension = fileName
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase(Locale.ROOT)

        return when {
            extension == "zip" -> ProfileFileImportKind.ZIP
            mimeType?.substringBefore(';')?.trim()?.startsWith("image/", ignoreCase = true) == true -> {
                ProfileFileImportKind.IMAGE
            }
            extension in imageExtensions -> ProfileFileImportKind.IMAGE
            else -> ProfileFileImportKind.TEXT
        }
    }
}
