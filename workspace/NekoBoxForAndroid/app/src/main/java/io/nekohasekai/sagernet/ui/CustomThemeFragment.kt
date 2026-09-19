package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.graphics.createBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.CustomTheme
import io.nekohasekai.sagernet.utils.CustomThemeLink
import io.nekohasekai.sagernet.utils.CustomThemePreview
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.widget.ListListener
import moe.matsuri.nb4a.ui.ColorPickerPreference
import java.util.Locale
import kotlin.math.roundToInt

class CustomThemeFragment : ToolbarFragment() {

    private lateinit var root: LinearLayout
    private lateinit var rows: LinearLayout
    private lateinit var statsSwitch: MaterialSwitch
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

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        return LinearLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                id = R.id.appbar
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(context.getColorAttr(R.attr.colorSurfaceContainer))
                addView(Toolbar(context).apply {
                    id = R.id.toolbar
                    setTitleTextColor(context.getColorAttr(R.attr.colorOnSurface))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(ScrollView(context).apply {
                clipToPadding = false
                ViewCompat.setOnApplyWindowInsetsListener(this, ListListener)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(24))
                    root = this
                }, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1F))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.configure_custom_theme)
        toolbar.setNavigationIcon(R.drawable.ic_navigation_close)
        toolbar.setNavigationOnClickListener { handleDiscardOrExit() }
        toolbar.menu.add(Menu.NONE, MENU_APPLY, Menu.NONE, R.string.apply).apply {
            setIcon(R.drawable.ic_baseline_save_24)
            setShowAsAction(MenuItemCompat.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                MENU_DISCARD -> {
                    handleDiscardOrExit()
                    true
                }
                MENU_APPLY -> {
                    applyWithPreview()
                    true
                }
                else -> false
            }
        }

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
        render()
    }

    override fun onBackPressed(): Boolean {
        handleDiscardOrExit()
        return true
    }

    private fun render() {
        root.removeAllViews()
        root.addView(sectionTitle(getString(R.string.custom_theme_base_settings)))
        root.addView(actionRow(getString(R.string.copy_from_another_theme), null) {
            showCopyThemeDialog()
        })
        root.addView(actionRow(getString(R.string.share_custom_theme), null) {
            shareCustomTheme()
        })
        root.addView(actionRow(getString(R.string.import_custom_theme), null) {
            importCustomThemeFromClipboard()
        })
        root.addView(switchRow(getString(R.string.use_dynamic_colors), dynamicColors) {
            dynamicColors = it
        })
        root.addView(switchRow(getString(R.string.apply_primary_color_to_app_header), headerPrimary) {
            headerPrimary = it
        })
        statsSwitch = switchRow(getString(R.string.apply_primary_color_to_stats_bar), statsBarPrimary) {
            statsBarPrimary = it
        }
        root.addView(statsSwitch)
        root.addView(sectionTitle(getString(R.string.custom_theme_colors)))
        rows = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(rows)
        renderColorRows()
    }

    private fun renderColorRows() {
        rows.removeAllViews()
        val palette = currentDraftPalette()
        CustomTheme.colorSpecs.forEach { spec ->
            rows.addView(actionRow(getString(spec.titleRes), getString(spec.descriptionRes)) {
                showColorDialog(spec, palette.colors[spec.key] ?: Color.BLACK)
            }.apply {
                addView(colorDot(palette.colors[spec.key] ?: Color.BLACK), LinearLayout.LayoutParams(dp(28), dp(28)))
            })
        }
    }

    private fun showCopyThemeDialog() {
        ColorPickerPreference.showThemePicker(
            requireContext(),
            getString(R.string.copy_from_another_theme),
            includeCustom = false,
        ) { theme ->
            val copied = CustomTheme.copyFrom(requireContext(), theme)
            lightPalette = copied.first
            darkPalette = copied.second
            dynamicColors = theme == Theme.MATERIAL_YOU
            render()
        }
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

    private fun showColorDialog(spec: CustomTheme.ColorSpec, originalColor: Int) {
        var selectedColor = originalColor.withFullAlpha()
        val preview = View(requireContext()).apply {
            background = colorPreviewDrawable(selectedColor)
        }
        val hex = TextView(requireContext()).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14F)
            setTextColor(requireContext().getColorAttr(R.attr.colorOnSurfaceVariant))
        }
        val wheel = ColorWheelView(requireContext()).apply {
            setColor(selectedColor)
            onColorChanged = { color ->
                selectedColor = color.withFullAlpha()
                preview.background = colorPreviewDrawable(selectedColor)
                hex.text = "#${colorToHex(selectedColor).drop(2)}"
            }
        }
        val brightnessSlider = Slider(requireContext()).apply {
            valueFrom = 0F
            valueTo = 1F
            stepSize = 0F
            value = wheel.value
            rotation = -90F
            addOnChangeListener { _, value, _ ->
                wheel.setValue(value)
            }
            setLabelFormatter { value ->
                "${(value * 100).roundToInt()}%"
            }
        }

        fun updateLabels() {
            preview.background = colorPreviewDrawable(selectedColor)
            hex.text = "#${colorToHex(selectedColor).drop(2)}"
        }

        val sliderSlot = FrameLayout(requireContext()).apply {
            clipChildren = false
            clipToPadding = false
            setPadding(dp(6), 0, dp(6), 0)
            addView(brightnessSlider, FrameLayout.LayoutParams(
                dp(260),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
        }
        val wheelSliderRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            setPadding(dp(8), 0, dp(8), 0)
            addView(wheel, LinearLayout.LayoutParams(dp(260), dp(260)))
            addView(sliderSlot, LinearLayout.LayoutParams(dp(56), dp(260)).apply {
                leftMargin = dp(12)
            })
        }
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(8), 0, 0)

            addView(wheelSliderRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(12)
            })

            val previewSize = dp(44)
            addView(preview, LinearLayout.LayoutParams(previewSize, previewSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(8)
            })
            addView(hex, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))

            doOnLayout {
                val rowHorizontalPadding = wheelSliderRow.paddingLeft + wheelSliderRow.paddingRight
                val sliderSlotWidth = dp(56)
                val gap = dp(12)
                val availableWidth = width - paddingLeft - paddingRight
                val maxWheelByWidth = availableWidth - rowHorizontalPadding - sliderSlotWidth - gap
                val maxWheelByHeight = (resources.displayMetrics.heightPixels * 0.42F).toInt()
                val wheelSize = minOf(dp(260), maxWheelByWidth, maxWheelByHeight)
                    .coerceAtLeast(dp(160))

                wheel.layoutParams = LinearLayout.LayoutParams(wheelSize, wheelSize)
                sliderSlot.layoutParams = LinearLayout.LayoutParams(sliderSlotWidth, wheelSize).apply {
                    leftMargin = gap
                }
                brightnessSlider.layoutParams = FrameLayout.LayoutParams(
                    wheelSize,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                )
                wheelSliderRow.requestLayout()
            }
        }
        updateLabels()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(spec.titleRes)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val palette = currentDraftPalette()
                palette.colors[spec.key] = selectedColor
                dynamicColors = false
                render()
            }
            .show()
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
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.discard)
            .setMessage(R.string.discard_custom_theme_message)
            .setNegativeButton(R.string.no, null)
            .setPositiveButton(R.string.yes) { _, _ ->
                lightPalette = originalLightPalette.copy()
                darkPalette = originalDarkPalette.copy()
                dynamicColors = originalDynamicColors
                headerPrimary = originalHeaderPrimary
                statsBarPrimary = originalStatsBarPrimary
                SettingsFragment.restoreInterfaceOnNextCreate()
                (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_settings)
            }
            .show()
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

    private fun sectionTitle(text: String): TextView {
        return TextView(requireContext()).apply {
            setText(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14F)
            setTextColor(requireContext().getColorAttr(R.attr.colorPrimary))
            setPadding(0, dp(18), 0, dp(8))
        }
    }

    private fun switchRow(text: String, checked: Boolean, onChanged: (Boolean) -> Unit): MaterialSwitch {
        return MaterialSwitch(requireContext()).apply {
            this.text = text
            isChecked = checked
            setPadding(0, dp(12), 0, dp(12))
            setOnCheckedChangeListener { _, value -> onChanged(value) }
        }
    }

    private fun actionRow(title: String, summary: String?, onClick: () -> Unit): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(0, dp(8), 0, dp(8))
            background = selectableItemBackground()
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16F)
                    setTextColor(context.getColorAttr(R.attr.colorOnSurface))
                })
                if (!summary.isNullOrBlank()) {
                    addView(TextView(context).apply {
                        text = summary
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13F)
                        setTextColor(context.getColorAttr(R.attr.colorOnSurfaceVariant))
                    })
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1F))
            setOnClickListener { onClick() }
        }
    }

    private fun colorDot(color: Int): View {
        return View(requireContext()).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(dp(1), requireContext().getColorAttr(R.attr.colorOnSurfaceVariant))
            }
        }
    }

    private fun colorPreviewDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(1), requireContext().getColorAttr(R.attr.colorOnSurfaceVariant))
        }
    }

    private fun selectableItemBackground(): android.graphics.drawable.Drawable? {
        val outValue = TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        return AppCompatResources.getDrawable(requireContext(), outValue.resourceId)
    }

    private fun colorToHex(color: Int): String {
        return String.format(Locale.ROOT, "%08X", color)
    }

    private fun Int.withFullAlpha(): Int {
        return Color.rgb(Color.red(this), Color.green(this), Color.blue(this))
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private object MenuItemCompat {
        const val SHOW_AS_ACTION_ALWAYS = 2
    }

    companion object {
        private const val MENU_DISCARD = 1
        private const val MENU_APPLY = 2
    }

    private class ColorWheelView(context: android.content.Context) : View(context) {
        var onColorChanged: ((Int) -> Unit)? = null
        val value: Float
            get() = hsvValue

        private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 3F
            color = Color.WHITE
        }
        private val markerShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 5F
            color = 0x66000000
        }
        private var wheelBitmap: Bitmap? = null
        private var hue = 0F
        private var saturation = 0F
        private var hsvValue = 1F

        fun setColor(color: Int) {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            hue = hsv[0]
            saturation = hsv[1]
            hsvValue = hsv[2]
            rebuildWheelBitmap()
            invalidate()
        }

        fun setValue(value: Float) {
            hsvValue = value.coerceIn(0F, 1F)
            rebuildWheelBitmap()
            invalidate()
            notifyColorChanged()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            rebuildWheelBitmap()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val bitmap = wheelBitmap ?: return
            canvas.drawBitmap(bitmap, 0F, 0F, wheelPaint)

            val radius = wheelRadius()
            val centerX = width / 2F
            val centerY = height / 2F
            val angle = Math.toRadians(hue.toDouble())
            val markerRadius = radius * saturation
            val markerX = centerX + kotlin.math.cos(angle).toFloat() * markerRadius
            val markerY = centerY + kotlin.math.sin(angle).toFloat() * markerRadius
            canvas.drawCircle(markerX, markerY, dp(8).toFloat(), markerShadowPaint)
            canvas.drawCircle(markerX, markerY, dp(8).toFloat(), markerPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action != MotionEvent.ACTION_DOWN &&
                event.action != MotionEvent.ACTION_MOVE &&
                event.action != MotionEvent.ACTION_UP
            ) return true
            parent?.requestDisallowInterceptTouchEvent(true)
            updateFromTouch(event.x, event.y)
            if (event.action == MotionEvent.ACTION_UP) performClick()
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        private fun updateFromTouch(x: Float, y: Float) {
            val centerX = width / 2F
            val centerY = height / 2F
            val dx = x - centerX
            val dy = y - centerY
            val radius = wheelRadius()
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            saturation = (distance / radius).coerceIn(0F, 1F)
            hue = ((Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat() + 360F) % 360F)
            invalidate()
            notifyColorChanged()
        }

        private fun notifyColorChanged() {
            onColorChanged?.invoke(Color.HSVToColor(floatArrayOf(hue, saturation, hsvValue)))
        }

        private fun rebuildWheelBitmap() {
            if (width <= 0 || height <= 0) return
            wheelBitmap?.recycle()
            wheelBitmap = createWheelBitmap(width, height)
        }

        private fun createWheelBitmap(width: Int, height: Int): Bitmap {
            val bitmap = createBitmap(width, height)
            val pixels = IntArray(width * height)
            val centerX = width / 2F
            val centerY = height / 2F
            val radius = wheelRadius()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val dx = x - centerX
                    val dy = y - centerY
                    val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                    val index = y * width + x
                    if (distance <= radius) {
                        val pixelHue = (Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat() + 360F) % 360F
                        val pixelSaturation = (distance / radius).coerceIn(0F, 1F)
                        pixels[index] = Color.HSVToColor(floatArrayOf(pixelHue, pixelSaturation, hsvValue))
                    } else {
                        pixels[index] = Color.TRANSPARENT
                    }
                }
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            return bitmap
        }

        private fun wheelRadius(): Float {
            return (kotlin.math.min(width, height) / 2F) - dp(10)
        }

        private fun dp(value: Int): Int {
            return (value * resources.displayMetrics.density).toInt()
        }
    }
}
