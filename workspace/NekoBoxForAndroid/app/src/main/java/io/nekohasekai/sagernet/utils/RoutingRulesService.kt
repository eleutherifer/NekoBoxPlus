package io.nekohasekai.sagernet.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import moe.matsuri.nb4a.utils.NGUtil

class RoutingRulesService(private val context: Context, private val routingDir: String) {

    private val proxyPatterns: List<String> by lazy { load("routing/$routingDir/proxy.txt") }
    private val directPatterns: List<String> by lazy { load("routing/$routingDir/direct.txt") }
    private val browsersPatterns: List<String> by lazy { load("routing/browsers.txt") }

    private fun load(path: String): List<String> = try {
        NGUtil.readTextFromAssets(context, path).lines().filter { it.isNotBlank() }
    } catch (_: Exception) {
        emptyList()
    }

    private fun matches(packageName: String, patterns: List<String>): Boolean =
        patterns.any { pattern ->
            if (pattern.startsWith("regex:"))
                Regex(pattern.removePrefix("regex:")).containsMatchIn(packageName)
            else
                packageName == pattern
        }

    fun isProxy(packageName: String) = matches(packageName, proxyPatterns) || matches(packageName, browsersPatterns)
    fun isDirect(packageName: String) = matches(packageName, directPatterns)

    /**
     * Compute the set of package names that should be stored in DataStore.individual
     * given the current bypass mode.
     *
     * In normal mode (bypass=false): selected packages ARE proxied.
     * In bypass mode (bypass=true): selected packages are BYPASSED (not proxied).
     * direct.txt always takes precedence over proxy.txt.
     */
    fun computeProxiedPackages(
        applicationCache: Map<String, ApplicationInfo>,
        bypass: Boolean
    ): Set<String> {
        val selected = mutableSetOf<String>()
        for ((packageName, appInfo) in applicationCache) {
            val uid = appInfo.uid
            val isDirect = isDirect(packageName)
            val needProxy = isProxy(packageName) && !isDirect || uid == 1000

            if (needProxy) {
                if (!bypass) selected.add(packageName)
            } else {
                if (bypass) selected.add(packageName)
            }

            // direct.txt always wins: never proxy in normal mode, always bypass in bypass mode
            if (isDirect) {
                if (bypass) selected.add(packageName) else selected.remove(packageName)
            }
        }
        return selected
    }
}
