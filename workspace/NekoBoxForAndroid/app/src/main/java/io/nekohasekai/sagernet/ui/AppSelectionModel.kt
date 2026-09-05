package io.nekohasekai.sagernet.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.utils.PackageCache

internal data class SelectableApp(
    val packageName: String,
    val name: String,
    val uid: Int,
    val isSystem: Boolean,
)

internal class AppSelectionModel(
    initialPackages: String,
    private val persist: (String) -> Unit,
) {
    var apps by mutableStateOf<List<SelectableApp>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var query by mutableStateOf("")
    var showSystemApps by mutableStateOf(false)

    private var packageList = initialPackages
    private var selectedUids by mutableStateOf<Set<Int>>(emptySet())

    val visibleApps: List<SelectableApp>
        get() = AppSelectionLogic.filter(apps, query, showSystemApps)

    fun isSelected(app: SelectableApp): Boolean = app.uid in selectedUids

    fun load(loadedApps: List<SelectableApp>) {
        selectedUids = AppSelectionLogic.selectedUids(loadedApps, packageList)
        apps = AppSelectionLogic.sort(loadedApps, selectedUids)
        loading = false
    }

    fun failLoading() {
        apps = emptyList()
        selectedUids = emptySet()
        loading = false
    }

    fun toggle(app: SelectableApp) {
        selectedUids = if (app.uid in selectedUids) {
            selectedUids - app.uid
        } else {
            selectedUids + app.uid
        }
        persistCanonicalSelection()
    }

    fun invert() {
        selectedUids = AppSelectionLogic.invertUids(apps, selectedUids)
        persistCanonicalSelection()
        sortSelectedFirst()
    }

    fun clear() {
        selectedUids = emptySet()
        persistCanonicalSelection()
        sortSelectedFirst()
    }

    fun importPackages(importedPackages: String) {
        packageList = importedPackages
        selectedUids = AppSelectionLogic.selectedUids(apps, importedPackages)
        persist(importedPackages)
    }

    fun replaceSelection(packageNames: Collection<String>) {
        val packageSet = packageNames.toSet()
        selectedUids = apps.asSequence()
            .filter { it.packageName in packageSet }
            .mapTo(mutableSetOf()) { it.uid }
        persistCanonicalSelection()
        sortSelectedFirst()
    }

    fun exportPackages(): String = packageList

    private fun sortSelectedFirst() {
        apps = AppSelectionLogic.sort(apps, selectedUids)
    }

    private fun persistCanonicalSelection() {
        packageList = apps.asSequence()
            .filter { it.uid in selectedUids }
            .joinToString("\n") { it.packageName }
        persist(packageList)
    }
}

internal object AppSelectionLogic {
    fun selectedUids(apps: List<SelectableApp>, packageList: String): Set<Int> {
        val packageNames = packageList.lineSequence().filter { it.isNotEmpty() }.toHashSet()
        return apps.asSequence()
            .filter { it.packageName in packageNames }
            .mapTo(mutableSetOf()) { it.uid }
    }

    fun invertUids(apps: List<SelectableApp>, selectedUids: Set<Int>): Set<Int> {
        return apps.asSequence().map { it.uid }.distinct().filterTo(mutableSetOf()) {
            it !in selectedUids
        }
    }

    fun sort(apps: List<SelectableApp>, selectedUids: Set<Int>): List<SelectableApp> {
        return apps.sortedWith(compareBy({ it.uid !in selectedUids }, { it.name }))
    }

    fun filter(
        apps: List<SelectableApp>,
        query: String,
        showSystemApps: Boolean,
    ): List<SelectableApp> {
        return apps.filter { app ->
            (showSystemApps || !app.isSystem) && (
                query.isEmpty() ||
                    app.name.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true) ||
                    app.uid.toString().contains(query)
                )
        }
    }
}

internal fun loadSelectableApps(packageManager: PackageManager): List<SelectableApp> {
    PackageCache.reload()
    return PackageCache.installedApps.asSequence()
        .filter { (packageName, _) -> packageName != BuildConfig.APPLICATION_ID }
        .map { (packageName, appInfo) ->
            SelectableApp(
                packageName = packageName,
                name = appInfo.loadLabel(packageManager).toString(),
                uid = appInfo.uid,
                isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            )
        }
        .toList()
}
