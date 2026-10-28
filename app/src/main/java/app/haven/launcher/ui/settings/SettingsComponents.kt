package app.haven.launcher.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.haven.launcher.R

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        Modifier
            .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 8.dp)
            .semantics { heading() },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun RowText(title: String, summary: String?, enabled: Boolean, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
        )
        if (summary != null) {
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f),
            )
        }
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, summary: String? = null, enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowText(title, summary, enabled, Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        // The whole row is the toggle; the switch is just its visual.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun ClickRow(title: String, summary: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowText(title, summary, enabled, Modifier.weight(1f))
    }
}

/** A setting with a few named options; picks in a dialog and shows the current choice as summary. */
@Composable
fun <T> ChoiceRow(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    ClickRow(title, options.firstOrNull { it.first == selected }?.second, enabled) { open = true }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                LazyColumn {
                    items(options, key = { it.second }) { (value, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = value == selected, role = Role.RadioButton) {
                                    onSelect(value)
                                    open = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = value == selected, onClick = null)
                            Spacer(Modifier.width(16.dp))
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Slider that only commits on release, so dragging doesn't rewrite settings 60 times a second. */
@Composable
fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.padding(horizontal = 24.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(format(local), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            valueRange = range,
            steps = steps,
        )
    }
}

/** Read-only row for facts (version, fixed gestures). Not focusable as a button. */
@Composable
fun InfoRow(title: String, summary: String) {
    RowText(title, summary, enabled = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp))
}
