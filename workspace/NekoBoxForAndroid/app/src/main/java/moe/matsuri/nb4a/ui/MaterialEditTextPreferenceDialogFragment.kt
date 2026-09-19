package moe.matsuri.nb4a.ui

import android.app.Dialog
import android.os.Bundle
import android.view.WindowManager
import androidx.preference.EditTextPreference
import androidx.preference.EditTextPreferenceDialogFragmentCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MaterialEditTextPreferenceDialogFragment : EditTextPreferenceDialogFragmentCompat() {

    companion object {
        private const val ARG_KEY = "key"

        fun newInstance(key: String): MaterialEditTextPreferenceDialogFragment {
            return MaterialEditTextPreferenceDialogFragment().apply {
                arguments = Bundle(1).apply { putString(ARG_KEY, key) }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val preference = preference
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(preference.dialogTitle ?: preference.title)
            .setIcon(preference.dialogIcon)
            .setPositiveButton(preference.positiveButtonText ?: getString(android.R.string.ok), this)
            .setNegativeButton(preference.negativeButtonText ?: getString(android.R.string.cancel), this)

        val contentView = onCreateDialogView(requireContext())
        if (contentView != null) {
            onBindDialogView(contentView)
            builder.setView(contentView)
        } else {
            builder.setMessage(preference.dialogMessage)
        }

        onPrepareDialogBuilder(builder)

        val dialog = builder.create()
        if (needInputMethod()) {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            scheduleShowSoftInput()
        }
        return dialog
    }
}

fun PreferenceFragmentCompat.showMaterialEditTextPreferenceDialog(preference: Preference): Boolean {
    if (preference !is EditTextPreference) return false

    @Suppress("DEPRECATION")
    MaterialEditTextPreferenceDialogFragment.newInstance(preference.key).apply {
        setTargetFragment(this@showMaterialEditTextPreferenceDialog, 0)
    }.show(parentFragmentManager, "androidx.preference.PreferenceFragment.DIALOG")

    return true
}
