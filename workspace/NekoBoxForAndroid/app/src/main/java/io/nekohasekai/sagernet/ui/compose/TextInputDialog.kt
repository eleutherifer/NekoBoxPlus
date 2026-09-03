package io.nekohasekai.sagernet.ui.compose

import android.content.Context
import android.view.ViewGroup
import android.view.Window
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.utils.Theme

internal fun Context.showComposeTextInputDialog(
    title: CharSequence,
    initialValue: String,
    positiveButton: CharSequence = getText(android.R.string.ok),
    negativeButton: CharSequence = getText(android.R.string.cancel),
    keyboardType: KeyboardType = KeyboardType.Text,
    maxLength: Int = Int.MAX_VALUE,
    password: Boolean = false,
    supportingText: CharSequence? = null,
    onPositive: (String) -> Unit,
    onCancel: () -> Unit = {},
): ComponentDialog {
    lateinit var dialog: ComponentDialog
    val content = ComposeView(this).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            NekoComposeTheme {
                var value by remember {
                    mutableStateOf(
                        TextFieldValue(
                            text = initialValue,
                            selection = TextRange(initialValue.length),
                        ),
                    )
                }
                val focusRequester = remember { FocusRequester() }
                val keyboardController = LocalSoftwareKeyboardController.current
                var passwordVisible by remember { mutableStateOf(false) }

                PreferenceDialogSurface(
                    title = title,
                    buttons = {
                        TextButton(onClick = dialog::cancel) {
                            Text(negativeButton.toString())
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            modifier = Modifier.widthIn(min = 64.dp),
                            onClick = {
                                dialog.dismiss()
                                onPositive(value.text)
                            },
                        ) {
                            Text(positiveButton.toString())
                        }
                    },
                ) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(
                                start = 24.dp,
                                top = 16.dp,
                                end = 24.dp,
                                bottom = 16.dp,
                            ),
                    ) {
                        OutlinedTextField(
                            value = value,
                            onValueChange = {
                                if (it.text.length <= maxLength) value = it
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                            visualTransformation = if (password && !passwordVisible) {
                                PasswordVisualTransformation()
                            } else {
                                VisualTransformation.None
                            },
                            singleLine = false,
                            minLines = 1,
                            maxLines = 8,
                            supportingText = supportingText?.let { message ->
                                { Text(message.toString()) }
                            },
                            trailingIcon = if (password) {
                                {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(
                                            painter = painterResource(
                                                if (passwordVisible) {
                                                    R.drawable.ic_baseline_visibility_off_24
                                                } else {
                                                    R.drawable.ic_baseline_visibility_24
                                                },
                                            ),
                                            contentDescription = stringResource(
                                                if (passwordVisible) {
                                                    R.string.hide_password
                                                } else {
                                                    R.string.show_password
                                                },
                                            ),
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
                LaunchedEffect(Unit) {
                    focusRequester.requestFocus()
                    keyboardController?.show()
                }
            }
        }
    }
    dialog = ComponentDialog(this, Theme.getDialogTheme()).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        setOnCancelListener { onCancel() }
    }
    dialog.showAsPreferenceDialog(this)
    dialog.enableComposeTextInput(alwaysVisible = true)
    return dialog
}
