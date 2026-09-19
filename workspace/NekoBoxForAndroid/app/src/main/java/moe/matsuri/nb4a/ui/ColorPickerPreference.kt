package moe.matsuri.nb4a.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.res.TypedArrayUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.setPadding
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.CustomTheme
import io.nekohasekai.sagernet.utils.Theme
import kotlin.math.roundToInt

class ColorPickerPreference
@JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = TypedArrayUtils.getAttr(
        context,
        androidx.preference.R.attr.editTextPreferenceStyle,
        android.R.attr.editTextPreferenceStyle
    )
) : Preference(
    context, attrs, defStyle
) {

    var inited = false

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val widgetFrame = holder.findViewById(android.R.id.widget_frame) as LinearLayout

        if (!inited) {
            inited = true

            widgetFrame.addView(
                getNekoImageViewAtColor(
                    context.getColorAttr(R.attr.colorPrimary),
                    48,
                    0
                )
            )
            widgetFrame.visibility = View.VISIBLE
        }
    }

    fun getNekoImageViewAtColor(color: Int, sizeDp: Int, paddingDp: Int): ImageView {
        // dp to pixel
        val factor = context.resources.displayMetrics.density
        val size = (sizeDp * factor).roundToInt()
        val paddingSize = (paddingDp * factor).roundToInt()

        return ImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams(size, size)
            setPadding(paddingSize)
            setImageDrawable(getNekoAtColor(resources, color))
        }
    }

    private fun getMaterialYouImageView(): ImageView {
        val factor = context.resources.displayMetrics.density
        val size = (64 * factor).roundToInt()
        val paddingSize = (16 * factor).roundToInt()
        val strokeSize = (2 * factor).roundToInt()

        return ImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams(size, size)
            setPadding(paddingSize)
            setImageResource(R.drawable.ic_baseline_color_lens_24)
            imageTintList = ColorStateList.valueOf(context.getColorAttr(R.attr.colorOnPrimary))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(context.getColorAttr(R.attr.colorPrimary))
                setStroke(strokeSize, context.getColorAttr(R.attr.colorOnSurfaceVariant))
            }
        }
    }

    private fun getCustomThemeView(sizeDp: Int): TextView {
        val factor = context.resources.displayMetrics.density
        val size = (sizeDp * factor).roundToInt()
        val strokeSize = (2 * factor).roundToInt()

        return TextView(context).apply {
            layoutParams = ViewGroup.LayoutParams(size, size)
            gravity = Gravity.CENTER
            text = "?"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28F)
            setTextColor(context.getColorAttr(R.attr.colorPrimary))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(context.getColorAttr(R.attr.colorSurfaceContainer))
                setStroke(strokeSize, context.getColorAttr(R.attr.colorPrimary))
            }
        }
    }

    fun getNekoAtColor(res: Resources, color: Int): Drawable {
        val neko = ResourcesCompat.getDrawable(
            res,
            R.drawable.ic_baseline_fiber_manual_record_24,
            null
        )!!
        DrawableCompat.setTint(neko.mutate(), color)
        return neko
    }

    override fun onClick() {
        super.onClick()

        showThemePicker(context, title, includeCustom = true) { themeId ->
            if (themeId == Theme.CUSTOM && !CustomTheme.isSupported) {
                Toast.makeText(context, R.string.custom_theme_unsupported, Toast.LENGTH_LONG).show()
                return@showThemePicker
            }
            persistInt(themeId)
            callChangeListener(themeId)
        }
    }

    companion object {
        fun showThemePicker(
            context: Context,
            title: CharSequence?,
            includeCustom: Boolean,
            onThemeSelected: (Int) -> Unit,
        ) {
            ColorPickerPreference(context).showThemePickerInternal(title, includeCustom, onThemeSelected)
        }
    }

    private fun showThemePickerInternal(
        title: CharSequence?,
        includeCustom: Boolean,
        onThemeSelected: (Int) -> Unit,
    ) {
        lateinit var dialog: AlertDialog

        val grid = GridLayout(context).apply {
            columnCount = 4

            val colors = context.resources.getIntArray(R.array.material_colors)
            var i = 0

            val dynamicView = getMaterialYouImageView().apply {
                setOnClickListener {
                    dialog.dismiss()
                    onThemeSelected(Theme.MATERIAL_YOU)
                }
            }
            addView(dynamicView)

            for (color in colors) {
                i++ //Theme.kt

                val themeId = i
                val view = getNekoImageViewAtColor(color, 64, 0).apply {
                    setOnClickListener {
                        dialog.dismiss()
                        onThemeSelected(themeId)
                    }
                }
                addView(view)
            }

            if (includeCustom) {
                addView(getCustomThemeView(64).apply {
                    alpha = if (CustomTheme.isSupported) 1F else 0.45F
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        tooltipText = context.getString(R.string.custom_theme)
                    }
                    setOnClickListener {
                        dialog.dismiss()
                        onThemeSelected(Theme.CUSTOM)
                    }
                })
            }

        }

        dialog = MaterialAlertDialogBuilder(context).setTitle(title)
            .setView(LinearLayout(context).apply {
                gravity = Gravity.CENTER
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                addView(grid)
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
