package io.nekohasekai.sagernet.ui.compose

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R

@Composable
fun NetworkToolsScreen(
    onStunTest: () -> Unit,
    onSpeedTest: () -> Unit,
    onRuleSetMatch: () -> Unit,
    onCellularNetwork: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NetworkToolCard(
            titleRes = R.string.stun_test,
            summaryRes = R.string.stun_test_summary,
            actionRes = R.string.start,
            onClick = onStunTest,
        )
        NetworkToolCard(
            titleRes = R.string.speed_test,
            summaryRes = R.string.speed_test_summary,
            actionRes = R.string.start,
            onClick = onSpeedTest,
        )
        NetworkToolCard(
            titleRes = R.string.ruleset_match_title,
            summaryRes = R.string.ruleset_match_summary,
            actionRes = R.string.start,
            onClick = onRuleSetMatch,
        )
        NetworkToolCard(
            titleRes = R.string.cellular_network_title,
            summaryRes = R.string.cellular_network_summary,
            actionRes = R.string.cellular_network_open,
            onClick = onCellularNetwork,
        )
    }
}

@Composable
private fun NetworkToolCard(
    @StringRes titleRes: Int,
    @StringRes summaryRes: Int,
    @StringRes actionRes: Int,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .focusGroup()
            .tvFocusTarget(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(summaryRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
            TextButton(
                onClick = onClick,
                modifier = Modifier
                    .align(Alignment.End)
                    .tvFocusTarget()
                    .padding(top = 8.dp),
            ) {
                Text(stringResource(actionRes))
            }
        }
    }
}
