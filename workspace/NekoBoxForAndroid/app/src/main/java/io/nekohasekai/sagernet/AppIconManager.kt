package io.nekohasekai.sagernet

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources

enum class AppIcon(
    val aliasClassName: String,
    @StringRes val titleRes: Int,
    @DrawableRes val iconRes: Int,
) {
    NEKOBOX_PLUS(
        "io.nekohasekai.sagernet.launcher.NekoBoxPlus",
        R.string.app_icon_dynamic,
        R.mipmap.ic_launcher,
    ),
    LIGHT_MODE(
        "io.nekohasekai.sagernet.launcher.LightMode",
        R.string.app_icon_light_mode,
        R.mipmap.ic_launcher_light,
    ),
    DARK_MODE(
        "io.nekohasekai.sagernet.launcher.DarkMode",
        R.string.app_icon_dark_mode,
        R.mipmap.ic_launcher_dark,
    ),
    OLD_NEKOBOX_PLUS(
        "io.nekohasekai.sagernet.launcher.OldNekoBoxPlus",
        R.string.app_icon_old_nekobox_plus,
        R.mipmap.ic_launcher_old_nekobox_plus,
    ),
    NEKOBOX(
        "io.nekohasekai.sagernet.launcher.NekoBox",
        R.string.app_icon_nekobox,
        R.mipmap.ic_launcher_nekobox,
    ),
    MIDNIGHT(
        "io.nekohasekai.sagernet.launcher.Midnight",
        R.string.app_icon_midnight,
        R.mipmap.ic_launcher_midnight,
    ),
    HEAVENS(
        "io.nekohasekai.sagernet.launcher.Heavens",
        R.string.app_icon_heavens,
        R.mipmap.ic_launcher_heavens,
    ),
    HALLOWEEN(
        "io.nekohasekai.sagernet.launcher.Halloween",
        R.string.app_icon_halloween,
        R.mipmap.ic_launcher_halloween,
    ),
    CYBERPUNK(
        "io.nekohasekai.sagernet.launcher.Cyberpunk",
        R.string.app_icon_cyberpunk,
        R.mipmap.ic_launcher_cyberpunk,
    ),
    BLACK_WHITE(
        "io.nekohasekai.sagernet.launcher.BlackWhite",
        R.string.app_icon_black_white,
        R.mipmap.ic_launcher_black_white,
    ),
    PINK(
        "io.nekohasekai.sagernet.launcher.Pink",
        R.string.app_icon_pink,
        R.mipmap.ic_launcher_pink,
    ),
    DRUID(
        "io.nekohasekai.sagernet.launcher.Druid",
        R.string.app_icon_druid,
        R.mipmap.ic_launcher_druid,
    ),
    RED(
        "io.nekohasekai.sagernet.launcher.Red",
        R.string.app_icon_red,
        R.mipmap.ic_launcher_red,
    ),
    RUSSIAN(
        "io.nekohasekai.sagernet.launcher.Russian",
        R.string.app_icon_russian,
        R.mipmap.ic_launcher_russian,
    ),
    TEXT(
        "io.nekohasekai.sagernet.launcher.Text",
        R.string.app_icon_text,
        R.mipmap.ic_launcher_text,
    ),
}

internal object AppIconStatePolicy {
    fun current(states: Map<AppIcon, Int>): AppIcon {
        return AppIcon.entries.drop(1).firstOrNull {
            states[it] == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } ?: AppIcon.NEKOBOX_PLUS
    }

    fun desired(selected: AppIcon): Map<AppIcon, Int> = AppIcon.entries.associateWith {
        if (it == selected) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
    }

    fun selectionForDevice(selected: AppIcon, isTelevision: Boolean): AppIcon =
        if (isTelevision) AppIcon.NEKOBOX_PLUS else selected

    fun previewUiMode(appUiMode: Int, systemUiMode: Int): Int {
        return (appUiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (systemUiMode and Configuration.UI_MODE_NIGHT_MASK)
    }
}

object AppIconManager {
    fun current(context: Context): AppIcon {
        val packageManager = context.packageManager
        val states = AppIcon.entries.associateWith {
            packageManager.getComponentEnabledSetting(it.componentName(context))
        }
        return AppIconStatePolicy.current(states)
    }

    fun set(context: Context, selected: AppIcon) {
        val effectiveSelection = AppIconStatePolicy.selectionForDevice(
            selected,
            context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
                Configuration.UI_MODE_TYPE_TELEVISION,
        )
        if (current(context) == effectiveSelection) return

        val packageManager = context.packageManager
        val desiredStates = AppIconStatePolicy.desired(effectiveSelection)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.setComponentEnabledSettings(
                desiredStates.map { (icon, state) ->
                    PackageManager.ComponentEnabledSetting(
                        icon.componentName(context),
                        state,
                        PackageManager.DONT_KILL_APP,
                    )
                }
            )
            return
        }

        packageManager.setComponentEnabledSetting(
            effectiveSelection.componentName(context),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        desiredStates.forEach { (icon, state) ->
            if (icon == effectiveSelection) return@forEach
            packageManager.setComponentEnabledSetting(
                icon.componentName(context),
                state,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    fun loadIcon(context: Context, icon: AppIcon): Drawable? {
        val configuration = Configuration(context.resources.configuration).apply {
            uiMode = AppIconStatePolicy.previewUiMode(
                uiMode,
                Resources.getSystem().configuration.uiMode,
            )
        }
        val launcherContext = context.createConfigurationContext(configuration)
        return AppCompatResources.getDrawable(launcherContext, icon.iconRes)
    }

    private fun AppIcon.componentName(context: Context) =
        ComponentName(context.packageName, aliasClassName)
}
