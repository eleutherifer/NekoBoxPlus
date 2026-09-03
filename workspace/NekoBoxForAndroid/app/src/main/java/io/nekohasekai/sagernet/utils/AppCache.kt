package io.nekohasekai.sagernet.utils

import java.io.File

object AppCache {
    fun clear(cacheDir: File) {
        clearDirFiles(cacheDir, skipFiles = setOf("neko.log"))

        val relativeCache = File(cacheDir.parentFile, "cache")
        if (relativeCache.exists() && relativeCache.isDirectory) {
            clearDirFiles(relativeCache)
        }
    }

    internal fun clearDirFiles(dir: File, skipFiles: Set<String> = emptySet()): Boolean {
        if (!dir.isDirectory) return false

        val children = dir.list() ?: return true
        for (child in children) {
            val childFile = File(dir, child)

            if (child == "neko.log") {
                try {
                    childFile.writeText("")
                    continue
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            if (child in skipFiles) continue

            if (childFile.isDirectory) {
                clearDirFiles(childFile, skipFiles)
            } else {
                childFile.delete()
            }
        }

        return true
    }
}
