package io.nekohasekai.sagernet.ui

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutImportBinding
import java.io.File

/** A restore-options dialog that is recreated by FragmentManager after configuration changes. */
class BackupImportDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = LayoutImportBinding.inflate(layoutInflater)
        binding.backupConfigurations.isVisible = requireArguments().getBoolean(ARG_HAS_PROFILES)
        binding.backupRules.isVisible = requireArguments().getBoolean(ARG_HAS_RULES)
        binding.backupSettings.isVisible = requireArguments().getBoolean(ARG_HAS_SETTINGS)

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.backup_import)
            .apply {
                if (requireArguments().getBoolean(ARG_SHOW_GIT_WARNING)) {
                    setMessage(R.string.git_destructive_restore_warning)
                }
            }
            .setView(binding.root)
            .setPositiveButton(R.string.backup_import) { _, _ ->
                parentFragmentManager.setFragmentResult(
                    RESULT_KEY,
                    bundleOf(
                        RESULT_FILE to requireArguments().getString(ARG_FILE),
                        RESULT_PROFILES to binding.backupConfigurations.isChecked,
                        RESULT_RULES to binding.backupRules.isChecked,
                        RESULT_SETTINGS to binding.backupSettings.isChecked,
                    ),
                )
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> deletePendingFile() }
            .create()
    }

    override fun onCancel(dialog: DialogInterface) {
        deletePendingFile()
        super.onCancel(dialog)
    }

    private fun deletePendingFile() {
        requireArguments().getString(ARG_FILE)?.let { File(it).delete() }
    }

    companion object {
        const val TAG = "backup_import_options"
        const val RESULT_KEY = "backup_import_options_result"
        const val RESULT_FILE = "file"
        const val RESULT_PROFILES = "profiles"
        const val RESULT_RULES = "rules"
        const val RESULT_SETTINGS = "settings"

        private const val ARG_FILE = "file"
        private const val ARG_HAS_PROFILES = "has_profiles"
        private const val ARG_HAS_RULES = "has_rules"
        private const val ARG_HAS_SETTINGS = "has_settings"
        private const val ARG_SHOW_GIT_WARNING = "show_git_warning"

        fun newInstance(
            file: String,
            hasProfiles: Boolean,
            hasRules: Boolean,
            hasSettings: Boolean,
            showGitWarning: Boolean,
        ) = BackupImportDialogFragment().apply {
            arguments = bundleOf(
                ARG_FILE to file,
                ARG_HAS_PROFILES to hasProfiles,
                ARG_HAS_RULES to hasRules,
                ARG_HAS_SETTINGS to hasSettings,
                ARG_SHOW_GIT_WARNING to showGitWarning,
            )
        }
    }
}
