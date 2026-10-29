package app.haven.launcher.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.haven.launcher.R
import app.haven.launcher.data.AppCategory
import app.haven.launcher.data.AppItem
import app.haven.launcher.data.SettingsRepository

/**
 * An open folder: its apps in a grid, the name on top (tap to rename). Long-press an app for its
 * menu, which includes "Remove from folder".
 */
@Composable
fun FolderDialog(
    name: String,
    apps: List<AppItem>,
    onLaunch: (AppItem) -> Unit,
    onAppMenu: (AppItem) -> Unit,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(name) { mutableStateOf(name) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(vertical = 20.dp)) {
                if (editing) {
                    val save = {
                        onRename(draft)
                        editing = false
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { if (it.length <= SettingsRepository.MAX_LABEL) draft = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.folder_name)) },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    )
                } else {
                    Text(
                        name,
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.folder_rename)) { editing = true }
                            .padding(8.dp),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(12.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(if (apps.size <= 4) 2 else 4),
                    modifier = Modifier.heightIn(max = 420.dp).padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(apps, key = { it.key }) { app ->
                        val more = stringResource(R.string.more_options)
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .combinedClickable(
                                    role = Role.Button,
                                    onLongClickLabel = more,
                                    onLongClick = { onAppMenu(app) },
                                ) { onLaunch(app) }
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            AppIcon(app, 48.dp)
                            Spacer(Modifier.height(6.dp))
                            Text(app.label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** Choose which categories an app is in, or start a new one. */
@Composable
fun CategoryPickerDialog(
    app: AppItem,
    categories: List<AppCategory>,
    onToggle: (categoryId: String, inCategory: Boolean) -> Unit,
    onCreate: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.categories_for, app.label)) },
        text = {
            Column {
                if (categories.isEmpty()) {
                    Text(
                        stringResource(R.string.categories_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                categories.forEach { c ->
                    val checked = app.key in c.apps
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Checkbox) { onToggle(c.id, !checked) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Spacer(Modifier.padding(6.dp))
                        Text(c.name.ifBlank { stringResource(R.string.category_default) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { if (it.length <= SettingsRepository.MAX_LABEL) newName = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.category_new)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (newName.isNotBlank()) {
                            onCreate(newName.trim())
                            newName = ""
                        }
                    }),
                    trailingIcon = {
                        TextButton(enabled = newName.isNotBlank(), onClick = {
                            onCreate(newName.trim())
                            newName = ""
                        }) { Text(stringResource(R.string.action_add)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
    )
}
