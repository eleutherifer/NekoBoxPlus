package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.AppIcon
import io.nekohasekai.sagernet.AppIconManager
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.getColorAttr

class AppIconPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle,
    defStyleRes: Int = 0,
) : Preference(context, attrs, defStyleAttr, defStyleRes) {

    init {
        isPersistent = false
        refreshIcon()
    }

    override fun onAttached() {
        super.onAttached()
        refreshIcon()
    }

    override fun onClick() {
        val selectedIcon = AppIconManager.current(context)
        val density = context.resources.displayMetrics.density
        val horizontalPadding = (24 * density).toInt()
        val iconSize = (56 * density).toInt()
        val textStartMargin = (12 * density).toInt()
        val selectedColor = context.getColorAttr(R.attr.selectedColorPrimary)
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
        }

        lateinit var dialog: androidx.appcompat.app.AlertDialog
        AppIcon.entries.forEach { appIcon ->
            val isCurrent = appIcon == selectedIcon
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(horizontalPadding, (4 * density).toInt(), horizontalPadding, (4 * density).toInt())
                addView(ImageView(context).apply {
                    setImageDrawable(AppIconManager.loadIcon(context, appIcon))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(iconSize, iconSize))
                addView(TextView(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    maxLines = 2
                    setText(appIcon.titleRes)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18F)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1F).apply {
                    marginStart = textStartMargin
                })
            }
            list.addView(FrameLayout(context).apply {
                minimumHeight = (64 * density).toInt()
                isSelected = isCurrent
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    stateDescription = if (isCurrent) context.getString(R.string.app_icon_selected) else null
                }
                setBackgroundColor(if (isCurrent) selectedColor else Color.TRANSPARENT)
                foreground = selectableItemBackground()
                addView(content, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
                setOnClickListener {
                    if (!isCurrent) {
                        AppIconManager.set(context, appIcon)
                        setIcon(AppIconManager.loadIcon(context, appIcon))
                        notifyChanged()
                    }
                    dialog.dismiss()
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }

        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(ScrollView(context).apply { addView(list) })
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshIcon() {
        setIcon(AppIconManager.loadIcon(context, AppIconManager.current(context)))
    }

    private fun selectableItemBackground(): android.graphics.drawable.Drawable? {
        val outValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        return AppCompatResources.getDrawable(context, outValue.resourceId)
    }
}
