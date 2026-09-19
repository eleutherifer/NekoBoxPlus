package moe.matsuri.nb4a.ui

import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.preference.MultiSelectListPreference
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MaterialMultiSelectListPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.dialogPreferenceStyle,
    defStyleRes: Int = 0,
) : MultiSelectListPreference(context, attrs, defStyleAttr, defStyleRes) {

    override fun onClick() {
        val labels = entries ?: return
        val entryValues = entryValues ?: return
        val selected = values.orEmpty().toMutableSet()
        val checkBoxes = ArrayList<MaterialCheckBox>(labels.size)
        val density = context.resources.displayMetrics.density
        val horizontalPadding = (24 * density).toInt()
        val verticalPadding = (8 * density).toInt()
        val controlSlot = (48 * density).toInt()
        val textStartMargin = (16 * density).toInt()

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, verticalPadding, 0, verticalPadding)
            labels.forEachIndexed { index, label ->
                val value = entryValues[index].toString()
                val checkBox = MaterialCheckBox(context).apply {
                    isChecked = value in selected
                    isClickable = false
                    isFocusable = false
                }
                addView(LinearLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    orientation = LinearLayout.HORIZONTAL
                    minimumHeight = (56 * density).toInt()
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(horizontalPadding, 0, horizontalPadding, 0)
                    addView(checkBox, LinearLayout.LayoutParams(controlSlot, controlSlot))
                    addView(TextView(context).apply {
                        maxLines = 3
                        ellipsize = TextUtils.TruncateAt.END
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18F)
                        text = label
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1F).apply {
                        marginStart = textStartMargin
                    })
                    setOnClickListener {
                        checkBox.isChecked = !checkBox.isChecked
                        if (checkBox.isChecked) selected += value else selected -= value
                    }
                })
                checkBoxes += checkBox
            }
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(ScrollView(context).apply { addView(list) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newValues = checkBoxes.mapIndexedNotNull { index, checkBox ->
                    entryValues[index].toString().takeIf { checkBox.isChecked }
                }.toSet()
                if (callChangeListener(newValues)) {
                    values = newValues
                    notifyChanged()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
