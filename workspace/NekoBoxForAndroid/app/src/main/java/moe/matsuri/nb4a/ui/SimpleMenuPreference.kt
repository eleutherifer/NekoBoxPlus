/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package moe.matsuri.nb4a.ui

import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import io.nekohasekai.sagernet.R

/**
 * Material dialog-backed list preference used by protocol/settings selectors.
 */


open class SimpleMenuPreference
@JvmOverloads constructor(
    context: Context?,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.dropdownPreferenceStyle,
    defStyleRes: Int = 0
) : ListPreference(context!!, attrs, defStyleAttr, defStyleRes) {

    init {
        val staticSummary = attrs?.preferenceText(this.context, "summary")
        val useSimpleSummaryProvider = attrs?.preferenceBoolean("useSimpleSummaryProvider") == true
        if (useSimpleSummaryProvider && !staticSummary.isNullOrBlank()) {
            summaryProvider = StaticAndEntrySummaryProvider(staticSummary)
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        holder.findViewById(R.id.spinner)?.let {
            it.layoutParams.width = 0
        }

        holder.findViewById(android.R.id.title)?.let { view ->
            (view as TextView).apply {
                isSingleLine = false
                maxLines = 10
                ellipsize = null
            }
        }
    }

    override fun onClick() {
        val values = entryValues ?: return
        val labels = entries ?: return
        val checked = values.indexOf(value)

        lateinit var dialog: androidx.appcompat.app.AlertDialog
        val density = context.resources.displayMetrics.density
        val horizontalPadding = (24 * density).toInt()
        val verticalPadding = (8 * density).toInt()
        val controlSlot = (48 * density).toInt()
        val textStartMargin = (8 * density).toInt()
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, verticalPadding, 0, verticalPadding)
            labels.forEachIndexed { index, label ->
                val radioButton = MaterialRadioButton(context).apply {
                    isChecked = index == checked
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
                    addView(radioButton, LinearLayout.LayoutParams(controlSlot, controlSlot))
                    addView(TextView(context).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        maxLines = 3
                        ellipsize = TextUtils.TruncateAt.END
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18F)
                        text = label
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1F).apply {
                        marginStart = textStartMargin
                    })
                    setOnClickListener {
                        val newValue = values[index].toString()
                        if (callChangeListener(newValue)) {
                            value = newValue
                            notifyChanged()
                        }
                        dialog.dismiss()
                    }
                })
            }
        }

        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(ScrollView(context).apply { addView(list) })
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private class StaticAndEntrySummaryProvider(
        private val staticSummary: CharSequence
    ) : Preference.SummaryProvider<ListPreference> {
        override fun provideSummary(preference: ListPreference): CharSequence {
            val entry = preference.entry?.takeIf { it.isNotBlank() }
            return when {
                entry == null -> staticSummary
                staticSummary.isBlank() -> entry
                else -> TextUtils.concat(entry, "\n\n", staticSummary)
            }
        }
    }

    private fun AttributeSet.preferenceText(context: Context, name: String): CharSequence? {
        for (index in 0 until attributeCount) {
            if (getAttributeName(index) != name) continue
            val resourceId = getAttributeResourceValue(index, 0)
            return if (resourceId != 0) context.getText(resourceId) else getAttributeValue(index)
        }
        return null
    }

    private fun AttributeSet.preferenceBoolean(name: String): Boolean {
        for (index in 0 until attributeCount) {
            if (getAttributeName(index) == name) return getAttributeBooleanValue(index, false)
        }
        return false
    }
}
