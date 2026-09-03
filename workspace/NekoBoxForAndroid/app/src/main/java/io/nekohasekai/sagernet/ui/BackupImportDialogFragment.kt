package io.nekohasekai.sagernet.ui

import android.content.DialogInterface
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.graphics.drawable.toDrawable
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import io.nekohasekai.sagernet.ui.compose.BackupImportDialog
import io.nekohasekai.sagernet.ui.compose.BackupImportSelection
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import io.nekohasekai.sagernet.utils.Theme
import java.io.File

/** A restore-options dialog that is recreated by FragmentManager after configuration changes. */
class BackupImportDialogFragment : DialogFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, Theme.getDialogTheme())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): ComposeView {
        var selection by mutableStateOf(BackupImportSelection())
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                NekoComposeTheme {
                    BackupImportDialog(
                        selection = selection,
                        hasProfiles = requireArguments().getBoolean(ARG_HAS_PROFILES),
                        hasRules = requireArguments().getBoolean(ARG_HAS_RULES),
                        hasSettings = requireArguments().getBoolean(ARG_HAS_SETTINGS),
                        showGitWarning = requireArguments().getBoolean(ARG_SHOW_GIT_WARNING),
                        onSelectionChanged = { selection = it },
                        onImport = {
                            parentFragmentManager.setFragmentResult(
                                RESULT_KEY,
                                bundleOf(
                                    RESULT_FILE to requireArguments().getString(ARG_FILE),
                                    RESULT_PROFILES to selection.profiles,
                                    RESULT_RULES to selection.rules,
                                    RESULT_SETTINGS to selection.settings,
                                ),
                            )
                            dismiss()
                        },
                        onCancel = {
                            deletePendingFile()
                            dismiss()
                        },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        }
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
