package io.nekohasekai.sagernet

import android.content.pm.PackageManager
import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Test

class AppIconStatePolicyTest {
    @Test
    fun defaultsToStandardIcon() {
        val states = AppIcon.entries.associateWith {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }

        assertEquals(AppIcon.NEKOBOX_PLUS, AppIconStatePolicy.current(states))
    }

    @Test
    fun resolvesExplicitlyEnabledAlternateIcon() {
        val states = AppIcon.entries.associateWith {
            if (it == AppIcon.HALLOWEEN) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
        }

        assertEquals(AppIcon.HALLOWEEN, AppIconStatePolicy.current(states))
    }

    @Test
    fun forcedModeIconsFollowDynamicIconInSelector() {
        assertEquals(
            listOf(AppIcon.NEKOBOX_PLUS, AppIcon.LIGHT_MODE, AppIcon.DARK_MODE),
            AppIcon.entries.take(3),
        )
    }

    @Test
    fun desiredStateEnablesOnlySelection() {
        val states = AppIconStatePolicy.desired(AppIcon.CYBERPUNK)

        assertEquals(
            listOf(AppIcon.CYBERPUNK),
            states.filterValues { it == PackageManager.COMPONENT_ENABLED_STATE_ENABLED }.keys.toList(),
        )
        assertEquals(AppIcon.entries.size - 1, states.count {
            it.value == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        })
    }

    @Test
    fun televisionForcesDynamicIcon() {
        assertEquals(
            AppIcon.NEKOBOX_PLUS,
            AppIconStatePolicy.selectionForDevice(AppIcon.HALLOWEEN, isTelevision = true),
        )
        assertEquals(
            AppIcon.HALLOWEEN,
            AppIconStatePolicy.selectionForDevice(AppIcon.HALLOWEEN, isTelevision = false),
        )
    }

    @Test
    fun previewUsesLightSystemModeWhenAppIsDark() {
        val appUiMode = Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_YES
        val systemUiMode = Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_NO

        assertEquals(
            systemUiMode,
            AppIconStatePolicy.previewUiMode(appUiMode, systemUiMode),
        )
    }

    @Test
    fun previewUsesDarkSystemModeWhenAppIsLight() {
        val appUiMode = Configuration.UI_MODE_TYPE_TELEVISION or Configuration.UI_MODE_NIGHT_NO
        val systemUiMode = Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_YES

        assertEquals(
            Configuration.UI_MODE_TYPE_TELEVISION or Configuration.UI_MODE_NIGHT_YES,
            AppIconStatePolicy.previewUiMode(appUiMode, systemUiMode),
        )
    }
}
