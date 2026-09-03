package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role

@Composable
internal fun SingleChoicePreferenceDialogContent(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
) {
    PreferenceChoiceList {
        labels.forEachIndexed { index, label ->
            PreferenceChoiceRow(
                modifier = Modifier.tvFocusTarget().selectable(
                    selected = index == selectedIndex,
                    role = Role.RadioButton,
                    onClick = { onSelected(index) },
                ),
                label = label,
                controlSpacing = 8.dp,
                content = {
                    RadioButton(
                        selected = index == selectedIndex,
                        onClick = null,
                    )
                },
            )
        }
    }
}

@Composable
internal fun MultiChoicePreferenceDialogContent(
    labels: List<String>,
    values: List<String>,
    selected: Set<String>,
    onSelectionChanged: (Set<String>) -> Unit,
) {
    PreferenceChoiceList {
        labels.forEachIndexed { index, label ->
            val value = values[index]
            val checked = value in selected
            PreferenceChoiceRow(
                modifier = Modifier.tvFocusTarget().toggleable(
                    value = checked,
                    role = Role.Checkbox,
                    onValueChange = {
                        onSelectionChanged(
                            if (checked) selected - value else selected + value,
                        )
                    },
                ),
                label = label,
                controlSpacing = 16.dp,
                content = { Checkbox(checked = checked, onCheckedChange = null) },
            )
        }
    }
}

@Composable
private fun PreferenceChoiceList(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        content = { content() },
    )
}

@Composable
private fun PreferenceChoiceRow(
    label: String,
    controlSpacing: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
        Spacer(Modifier.width(controlSpacing))
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 18.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
