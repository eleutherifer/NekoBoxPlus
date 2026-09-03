package io.nekohasekai.sagernet.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.RuleSetMatchUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RuleSetMatchScreen(
    state: RuleSetMatchUiState,
    onClose: () -> Unit,
    onSearch: (String) -> Unit,
    onCopy: (String) -> Unit,
    onCopyAll: () -> Unit,
    errorMessage: String?,
    onDismissError: () -> Unit,
) {
    val defaultDestination = stringResource(R.string.ruleset_match_default_destination)
    var destination by rememberSaveable { mutableStateOf(defaultDestination) }
    val focusManager = LocalFocusManager.current
    val startSearch = {
        if (!state.isRunning) {
            focusManager.clearFocus()
            onSearch(destination)
        }
    }

    BackHandler(onBack = onClose)
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ruleset_match_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            painterResource(R.drawable.baseline_arrow_back_24),
                            contentDescription = stringResource(R.string.mal_close),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                OutlinedTextField(
                    value = destination,
                    onValueChange = { destination = it },
                    enabled = !state.isRunning,
                    singleLine = true,
                    label = { Text(stringResource(R.string.ruleset_match_destination)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { startSearch() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onCopyAll,
                    enabled = !state.isRunning && state.results.isNotEmpty(),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_baseline_content_copy_24),
                        contentDescription = null,
                    )
                    Text(
                        stringResource(R.string.ruleset_match_copy_all),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Button(
                    onClick = startSearch,
                    enabled = !state.isRunning,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_baseline_play_arrow_24),
                        contentDescription = null,
                    )
                    Text(stringResource(R.string.start), modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (state.isRunning) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }
            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(16.dp),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(
                        items = state.results,
                        key = { index, entry -> "$index-$entry" },
                    ) { _, entry ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                                .clickable { onCopy(entry) },
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        ) {
                            Text(
                                text = entry,
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    errorMessage?.let {
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text(stringResource(R.string.error_title)) },
            text = { Text(it) },
            confirmButton = {
                TextButton(onClick = onDismissError) { Text(stringResource(android.R.string.ok)) }
            },
        )
    }
}
