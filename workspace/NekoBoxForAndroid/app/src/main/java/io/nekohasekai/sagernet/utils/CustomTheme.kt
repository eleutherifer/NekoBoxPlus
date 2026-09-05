package io.nekohasekai.sagernet.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.ContextThemeWrapper
import androidx.annotation.AttrRes
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import com.google.android.material.color.ColorResourcesOverride
import com.google.android.material.color.DynamicColors
import com.google.android.material.R as MaterialR
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.getColorAttr
import org.json.JSONObject

object CustomTheme {

    const val CUSTOM_THEME_ID = 23
    private const val DEFAULT_SOURCE_THEME = Theme.PINK

    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    data class ColorSpec(
        val key: String,
        @StringRes val titleRes: Int,
        @StringRes val descriptionRes: Int,
        @AttrRes val attr: Int,
        @ColorRes val colorRes: Int,
    )

    data class Palette(val colors: MutableMap<String, Int>) {
        fun copy() = Palette(colors.toMutableMap())
    }

    data class State(
        val light: Palette,
        val dark: Palette,
        val dynamicColors: Boolean,
        val headerPrimary: Boolean,
        val statsBarPrimary: Boolean,
    )

    val colorSpecs = listOf(
        ColorSpec("primary", R.string.custom_theme_color_primary, R.string.custom_theme_color_primary_desc, R.attr.colorPrimary, R.color.custom_theme_primary),
        ColorSpec("onPrimary", R.string.custom_theme_color_on_primary, R.string.custom_theme_color_on_primary_desc, R.attr.colorOnPrimary, R.color.custom_theme_on_primary),
        ColorSpec("primaryContainer", R.string.custom_theme_color_primary_container, R.string.custom_theme_color_primary_container_desc, R.attr.colorPrimaryContainer, R.color.custom_theme_primary_container),
        ColorSpec("onPrimaryContainer", R.string.custom_theme_color_on_primary_container, R.string.custom_theme_color_on_primary_container_desc, R.attr.colorOnPrimaryContainer, R.color.custom_theme_on_primary_container),
        ColorSpec("secondary", R.string.custom_theme_color_secondary, R.string.custom_theme_color_secondary_desc, R.attr.colorSecondary, R.color.custom_theme_secondary),
        ColorSpec("onSecondary", R.string.custom_theme_color_on_secondary, R.string.custom_theme_color_on_secondary_desc, R.attr.colorOnSecondary, R.color.custom_theme_on_secondary),
        ColorSpec("secondaryContainer", R.string.custom_theme_color_secondary_container, R.string.custom_theme_color_secondary_container_desc, R.attr.colorSecondaryContainer, R.color.custom_theme_secondary_container),
        ColorSpec("onSecondaryContainer", R.string.custom_theme_color_on_secondary_container, R.string.custom_theme_color_on_secondary_container_desc, R.attr.colorOnSecondaryContainer, R.color.custom_theme_on_secondary_container),
        ColorSpec("surface", R.string.custom_theme_color_surface, R.string.custom_theme_color_surface_desc, R.attr.colorSurface, R.color.custom_theme_surface),
        ColorSpec("onSurface", R.string.custom_theme_color_on_surface, R.string.custom_theme_color_on_surface_desc, R.attr.colorOnSurface, R.color.custom_theme_on_surface),
        ColorSpec("surfaceVariant", R.string.custom_theme_color_surface_variant, R.string.custom_theme_color_surface_variant_desc, R.attr.colorSurfaceVariant, R.color.custom_theme_surface_variant),
        ColorSpec("onSurfaceVariant", R.string.custom_theme_color_on_surface_variant, R.string.custom_theme_color_on_surface_variant_desc, R.attr.colorOnSurfaceVariant, R.color.custom_theme_on_surface_variant),
        ColorSpec("surfaceContainer", R.string.custom_theme_color_surface_container, R.string.custom_theme_color_surface_container_desc, R.attr.colorSurfaceContainer, R.color.custom_theme_surface_container),
        ColorSpec("surfaceContainerHigh", R.string.custom_theme_color_surface_container_high, R.string.custom_theme_color_surface_container_high_desc, R.attr.colorSurfaceContainerHigh, R.color.custom_theme_surface_container_high),
    )

    fun ensureDefaults(context: Context) {
        if (DataStore.customThemeLight.isBlank()) {
            DataStore.customThemeLight = encode(resolvePalette(context, DEFAULT_SOURCE_THEME, false))
        }
        if (DataStore.customThemeDark.isBlank()) {
            DataStore.customThemeDark = encode(resolvePalette(context, DEFAULT_SOURCE_THEME, true))
        }
    }

    fun currentPalette(context: Context): Palette {
        ensureDefaults(context)
        return if (Theme.usingNightMode()) darkPalette(context) else lightPalette(context)
    }

    fun lightPalette(context: Context): Palette {
        ensureDefaults(context)
        return decode(DataStore.customThemeLight, resolvePalette(context, DEFAULT_SOURCE_THEME, false))
    }

    fun darkPalette(context: Context): Palette {
        ensureDefaults(context)
        return decode(DataStore.customThemeDark, resolvePalette(context, DEFAULT_SOURCE_THEME, true))
    }

    fun useDynamicColors(): Boolean {
        return DataStore.appTheme == CUSTOM_THEME_ID &&
                DataStore.customThemeDynamicColors &&
                DynamicColors.isDynamicColorAvailable()
    }

    fun save(
        light: Palette,
        dark: Palette,
        dynamicColors: Boolean,
        headerPrimary: Boolean,
        statsBarPrimary: Boolean,
    ) {
        DataStore.customThemeLight = encode(light)
        DataStore.customThemeDark = encode(dark)
        DataStore.customThemeDynamicColors = dynamicColors
        DataStore.customThemeHeaderPrimary = headerPrimary
        DataStore.customThemeStatsBarPrimary = statsBarPrimary
    }

    fun capture(context: Context): State {
        ensureDefaults(context)
        return State(
            light = lightPalette(context),
            dark = darkPalette(context),
            dynamicColors = DataStore.customThemeDynamicColors,
            headerPrimary = DataStore.customThemeHeaderPrimary,
            statsBarPrimary = DataStore.customThemeStatsBarPrimary,
        )
    }

    fun save(state: State) {
        save(
            state.light,
            state.dark,
            state.dynamicColors,
            state.headerPrimary,
            state.statsBarPrimary,
        )
    }

    fun copyFrom(context: Context, theme: Int): Pair<Palette, Palette> {
        return resolvePalette(context, theme, false) to resolvePalette(context, theme, true)
    }

    fun applyOverrideIfNeeded(context: Context) {
        if (!isSupported || DataStore.appTheme != CUSTOM_THEME_ID) return
        if (useDynamicColors()) return
        val override = ColorResourcesOverride.getInstance() ?: return
        override.applyIfPossible(context, resourceMap(currentPalette(context)))
    }

    fun resourceMap(palette: Palette): Map<Int, Int> {
        val colors = colorSpecs.associate { it.key to (palette.colors[it.key] ?: 0) }
        val primary = colors.getValue("primary")
        val onPrimary = colors.getValue("onPrimary")
        val primaryContainer = colors.getValue("primaryContainer")
        val onPrimaryContainer = colors.getValue("onPrimaryContainer")
        val secondary = colors.getValue("secondary")
        val onSecondary = colors.getValue("onSecondary")
        val secondaryContainer = colors.getValue("secondaryContainer")
        val onSecondaryContainer = colors.getValue("onSecondaryContainer")
        val surface = colors.getValue("surface")
        val onSurface = colors.getValue("onSurface")
        val surfaceVariant = colors.getValue("surfaceVariant")
        val onSurfaceVariant = colors.getValue("onSurfaceVariant")
        val surfaceContainer = colors.getValue("surfaceContainer")
        val surfaceContainerHigh = colors.getValue("surfaceContainerHigh")
        val dark = isDark(surface)
        val error = if (dark) 0xFFF2B8B5.toInt() else 0xFFB3261E.toInt()
        val onError = if (dark) 0xFF601410.toInt() else 0xFFFFFFFF.toInt()
        val errorContainer = if (dark) 0xFF8C1D18.toInt() else 0xFFF9DEDC.toInt()
        val onErrorContainer = if (dark) 0xFFF9DEDC.toInt() else 0xFF410E0B.toInt()

        return mutableMapOf<Int, Int>().apply {
            colorSpecs.forEach { spec -> put(spec.colorRes, colors.getValue(spec.key)) }

            put(MaterialR.color.material_personalized_color_primary, primary)
            put(MaterialR.color.material_personalized_color_on_primary, onPrimary)
            put(MaterialR.color.material_personalized_color_primary_container, primaryContainer)
            put(MaterialR.color.material_personalized_color_on_primary_container, onPrimaryContainer)
            put(MaterialR.color.material_personalized_color_primary_inverse, primary)

            put(MaterialR.color.material_personalized_color_secondary, secondary)
            put(MaterialR.color.material_personalized_color_on_secondary, onSecondary)
            put(MaterialR.color.material_personalized_color_secondary_container, secondaryContainer)
            put(MaterialR.color.material_personalized_color_on_secondary_container, onSecondaryContainer)

            put(MaterialR.color.material_personalized_color_tertiary, secondary)
            put(MaterialR.color.material_personalized_color_on_tertiary, onSecondary)
            put(MaterialR.color.material_personalized_color_tertiary_container, secondaryContainer)
            put(MaterialR.color.material_personalized_color_on_tertiary_container, onSecondaryContainer)

            put(MaterialR.color.material_personalized_color_background, surface)
            put(MaterialR.color.material_personalized_color_on_background, onSurface)
            put(MaterialR.color.material_personalized_color_surface, surface)
            put(MaterialR.color.material_personalized_color_on_surface, onSurface)
            put(MaterialR.color.material_personalized_color_surface_variant, surfaceVariant)
            put(MaterialR.color.material_personalized_color_on_surface_variant, onSurfaceVariant)
            put(MaterialR.color.material_personalized_color_surface_bright, surface)
            put(MaterialR.color.material_personalized_color_surface_dim, surface)
            put(MaterialR.color.material_personalized_color_surface_container, surfaceContainer)
            put(MaterialR.color.material_personalized_color_surface_container_high, surfaceContainerHigh)
            put(MaterialR.color.material_personalized_color_surface_container_highest, surfaceContainerHigh)
            put(MaterialR.color.material_personalized_color_surface_container_low, surfaceContainer)
            put(MaterialR.color.material_personalized_color_surface_container_lowest, surface)
            put(MaterialR.color.material_personalized_color_surface_inverse, onSurface)
            put(MaterialR.color.material_personalized_color_on_surface_inverse, surface)

            put(MaterialR.color.material_personalized_color_outline, onSurfaceVariant)
            put(MaterialR.color.material_personalized_color_outline_variant, surfaceVariant)

            put(MaterialR.color.material_personalized_color_error, error)
            put(MaterialR.color.material_personalized_color_on_error, onError)
            put(MaterialR.color.material_personalized_color_error_container, errorContainer)
            put(MaterialR.color.material_personalized_color_on_error_container, onErrorContainer)

            put(MaterialR.color.material_personalized_color_control_activated, primary)
            put(MaterialR.color.material_personalized_color_control_normal, onSurfaceVariant)
            put(MaterialR.color.material_personalized_color_control_highlight, primary)
            put(MaterialR.color.material_personalized_color_text_hint_foreground_inverse, surfaceVariant)
            put(MaterialR.color.material_personalized_color_text_primary_inverse, surface)
            put(MaterialR.color.material_personalized_color_text_primary_inverse_disable_only, surface)
            put(MaterialR.color.material_personalized_color_text_secondary_and_tertiary_inverse, surfaceVariant)
            put(MaterialR.color.material_personalized_color_text_secondary_and_tertiary_inverse_disabled, surfaceVariant)
        }.filterValues { it != 0 }
    }

    private fun resolvePalette(context: Context, theme: Int, night: Boolean): Palette {
        val configuration = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val themed = ContextThemeWrapper(context.createConfigurationContext(configuration), Theme.getTheme(theme))
        return Palette(colorSpecs.associate { it.key to themed.getColorAttr(it.attr) }.toMutableMap())
    }

    internal fun stateToJson(state: State): JSONObject {
        return JSONObject().apply {
            put("light", paletteToJson(state.light))
            put("dark", paletteToJson(state.dark))
            put("dynamicColors", state.dynamicColors)
            put("headerPrimary", state.headerPrimary)
            put("statsBarPrimary", state.statsBarPrimary)
        }
    }

    internal fun stateFromJson(json: JSONObject): State {
        return State(
            light = paletteFromJson(json.getJSONObject("light")),
            dark = paletteFromJson(json.getJSONObject("dark")),
            dynamicColors = json.getBoolean("dynamicColors"),
            headerPrimary = json.getBoolean("headerPrimary"),
            statsBarPrimary = json.getBoolean("statsBarPrimary"),
        )
    }

    private fun encode(palette: Palette): String {
        return paletteToJson(palette).toString()
    }

    private fun paletteToJson(palette: Palette): JSONObject {
        return JSONObject().apply {
            colorSpecs.forEach { spec ->
                palette.colors[spec.key]?.let { put(spec.key, it) }
            }
        }
    }

    private fun paletteFromJson(json: JSONObject): Palette {
        return Palette(colorSpecs.associate { spec ->
            spec.key to json.getInt(spec.key)
        }.toMutableMap())
    }

    private fun decode(value: String, fallback: Palette): Palette {
        if (value.isBlank()) return fallback.copy()
        val json = runCatching { JSONObject(value) }.getOrNull() ?: return fallback.copy()
        return fallback.copy().apply {
            colorSpecs.forEach { spec ->
                if (json.has(spec.key)) colors[spec.key] = json.optInt(spec.key)
            }
        }
    }

    private fun isDark(color: Int): Boolean {
        val red = (color shr 16) and 0xff
        val green = (color shr 8) and 0xff
        val blue = color and 0xff
        return red * 0.299 + green * 0.587 + blue * 0.114 < 128
    }
}
