package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.ActivityCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.compose.CustomThemeColorRow
import io.nekohasekai.sagernet.ui.compose.CustomThemeScreen
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import io.nekohasekai.sagernet.utils.CustomTheme
import io.nekohasekai.sagernet.utils.CustomThemeLink
import io.nekohasekai.sagernet.utils.CustomThemePreview
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.ui.compose.showThemePickerDialog

class CustomThemeFragment : ToolbarFragment() {

    private lateinit var root: ComposeView
    private var revision by mutableIntStateOf(0)
    private lateinit var lightPalette: CustomTheme.Palette
    private lateinit var darkPalette: CustomTheme.Palette
    private lateinit var originalLightPalette: CustomTheme.Palette
    private lateinit var originalDarkPalette: CustomTheme.Palette
    private var dynamicColors = false
    private var originalDynamicColors = false
    private var headerPrimary = false
    private var originalHeaderPrimary = false
    private var statsBarPrimary = false
    private var originalStatsBarPrimary = false
    private var showDiscardDialog by mutableStateOf(false)

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            root = this
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        CustomTheme.ensureDefaults(requireContext())
        originalLightPalette = CustomTheme.lightPalette(requireContext())
        originalDarkPalette = CustomTheme.darkPalette(requireContext())
        originalDynamicColors = DataStore.customThemeDynamicColors
        originalHeaderPrimary = DataStore.customThemeHeaderPrimary
        originalStatsBarPrimary = DataStore.customThemeStatsBarPrimary
        lightPalette = originalLightPalette.copy()
        darkPalette = originalDarkPalette.copy()
        dynamicColors = originalDynamicColors
        headerPrimary = originalHeaderPrimary
        statsBarPrimary = originalStatsBarPrimary
        root.setContent {
            val colors = revision.let {
                val palette = currentDraftPalette()
                CustomTheme.colorSpecs.map { spec ->
                    CustomThemeColorRow(spec, palette.colors[spec.key] ?: Color.BLACK)
                }
            }
            NekoComposeTheme {
                CustomThemeScreen(
                    dynamicColors = dynamicColors,
                    headerPrimary = headerPrimary,
                    statsBarPrimary = statsBarPrimary,
                    colors = colors,
                    onClose = ::handleDiscardOrExit,
                    onApply = ::applyWithPreview,
                    onCopyTheme = ::showCopyThemeDialog,
                    onShareTheme = ::shareCustomTheme,
                    onImportTheme = ::importCustomThemeFromClipboard,
                    onDynamicColorsChanged = {
                        dynamicColors = it
                        render()
                    },
                    onHeaderPrimaryChanged = {
                        headerPrimary = it
                        render()
                    },
                    onStatsBarPrimaryChanged = {
                        statsBarPrimary = it
                        render()
                    },
                    onColorChanged = { spec, color ->
                        currentDraftPalette().colors[spec.key] = color
                        dynamicColors = false
                        render()
                    },
                    showDiscardDialog = showDiscardDialog,
                    onConfirmDiscard = ::discardAndExit,
                    onDismissDiscard = { showDiscardDialog = false },
                )
            }
        }
    }

    override fun onBackPressed(): Boolean {
        handleDiscardOrExit()
        return true
    }

    private fun render() {
        revision++
    }

    private fun showCopyThemeDialog() {
        requireContext().showThemePickerDialog(
            getString(R.string.copy_from_another_theme),
            includeCustom = false,
            onThemeSelected = { theme ->
            val copied = CustomTheme.copyFrom(requireContext(), theme)
            lightPalette = copied.first
            darkPalette = copied.second
            dynamicColors = theme == Theme.MATERIAL_YOU
            render()
            },
        )
    }

    private fun shareCustomTheme() {
        val link = CustomThemeLink.encode(draftState())
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, link),
                getString(R.string.share_custom_theme),
            ),
        )
    }

    private fun importCustomThemeFromClipboard() {
        val clipboard = SagerNet.getClipboardText()
        if (clipboard.isBlank()) {
            Toast.makeText(requireContext(), R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val candidates = CustomThemeLink.extractCandidates(clipboard)
        if (candidates.size != 1) {
            Toast.makeText(
                requireContext(),
                if (candidates.isEmpty()) R.string.custom_theme_link_not_found
                else R.string.multiple_custom_theme_links,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        (activity as? MainActivity)?.requestCustomThemeImport(
            candidates.single(),
            returnToInterface = true,
        )
    }

    private fun applyWithPreview() {
        val mainActivity = activity as? MainActivity ?: return
        if (CustomThemePreview.pending() != null) {
            Toast.makeText(requireContext(), R.string.custom_theme_preview_active, Toast.LENGTH_SHORT).show()
            return
        }
        CustomThemePreview.begin(requireContext(), draftState())
        MainActivity.openSettingsOnNextCreate()
        SettingsFragment.restoreInterfaceOnNextCreate()
        ActivityCompat.recreate(mainActivity)
    }

    private fun handleDiscardOrExit() {
        if (!isDirty()) {
            SettingsFragment.restoreInterfaceOnNextCreate()
            (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_settings)
            return
        }
        showDiscardDialog = true
    }

    private fun discardAndExit() {
        showDiscardDialog = false
        lightPalette = originalLightPalette.copy()
        darkPalette = originalDarkPalette.copy()
        dynamicColors = originalDynamicColors
        headerPrimary = originalHeaderPrimary
        statsBarPrimary = originalStatsBarPrimary
        SettingsFragment.restoreInterfaceOnNextCreate()
        (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_settings)
    }

    private fun isDirty(): Boolean {
        return lightPalette.colors != originalLightPalette.colors ||
                darkPalette.colors != originalDarkPalette.colors ||
                dynamicColors != originalDynamicColors ||
                headerPrimary != originalHeaderPrimary ||
                statsBarPrimary != originalStatsBarPrimary
    }

    private fun currentDraftPalette(): CustomTheme.Palette {
        return if (Theme.usingNightMode()) darkPalette else lightPalette
    }

    private fun draftState(): CustomTheme.State {
        return CustomTheme.State(
            light = lightPalette.copy(),
            dark = darkPalette.copy(),
            dynamicColors = dynamicColors,
            headerPrimary = headerPrimary,
            statsBarPrimary = statsBarPrimary,
        )
    }

}
