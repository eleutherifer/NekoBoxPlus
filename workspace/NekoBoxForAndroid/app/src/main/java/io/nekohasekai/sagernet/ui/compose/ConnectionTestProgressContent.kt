package io.nekohasekai.sagernet.ui.compose

import android.content.Context
import android.view.ViewGroup
import android.view.Window
import androidx.activity.ComponentDialog
import androidx.annotation.ColorInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.utils.Theme

internal data class ConnectionTestProgressUiState(
    val progress: String = "0 / 0",
    val profileName: String = "",
    val protocol: String = "",
    @param:ColorInt val protocolColor: Int? = null,
    val status: String = "",
    @param:ColorInt val statusColor: Int? = null,
)

internal fun Context.showConnectionTestProgressDialog(
    state: @Composable () -> ConnectionTestProgressUiState,
    onMinimize: () -> Unit,
    onCancel: () -> Unit,
): ComponentDialog {
    lateinit var dialog: ComponentDialog
    val content = ComposeView(this).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            NekoComposeTheme {
                PreferenceDialogSurface(
                    title = null,
                    showButtons = true,
                    buttons = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = onCancel) {
                                Text(getString(android.R.string.cancel))
                            }
                            Spacer(Modifier.size(8.dp))
                            TextButton(onClick = onMinimize) {
                                Text(getString(R.string.minimize))
                            }
                        }
                    },
                ) {
                    ConnectionTestProgressContent(state())
                }
            }
        }
    }
    return ComponentDialog(this, Theme.getDialogTheme()).also { created ->
        dialog = created
        created.requestWindowFeature(Window.FEATURE_NO_TITLE)
        created.setCancelable(false)
        created.setCanceledOnTouchOutside(false)
        created.setContentView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        created.prepareAsPreferenceDialog(this)
        created.show()
    }
}

@Composable
internal fun ConnectionTestProgressContent(state: ConnectionTestProgressUiState) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .widthIn(min = 280.dp)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        CircularProgressIndicator(Modifier.size(36.dp))
        if (state.profileName.isNotEmpty()) {
            Text(
                text = state.profileName,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )
        }
        if (state.protocol.isNotEmpty() || state.status.isNotEmpty()) {
            Text(
                text = buildAnnotatedString {
                    state.protocolColor?.let { color ->
                        withStyle(SpanStyle(color = Color(color))) { append(state.protocol) }
                    } ?: append(state.protocol)
                    if (state.protocol.isNotEmpty() && state.status.isNotEmpty()) append(' ')
                    state.statusColor?.let { color ->
                        withStyle(SpanStyle(color = Color(color))) { append(state.status) }
                    } ?: append(state.status)
                },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }
        Text(
            text = state.progress,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
    }
}
