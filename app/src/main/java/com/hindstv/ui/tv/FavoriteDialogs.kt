package com.hindstv.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hindstv.R
import com.hindstv.ui.ListEditUi
import com.hindstv.ui.ListPickerUi
import com.hindstv.ui.components.PillButton
import com.hindstv.ui.components.TvTextField
import com.hindstv.ui.components.requestFocusSoon
import com.hindstv.ui.theme.HindsColors
import com.hindstv.ui.theme.HindsShapes

/**
 * "Add to list": every favourites list with a tick when the channel is in it; Select toggles.
 * Ends with "New list" to make one on the spot. A Dialog is its own window, so focus can't wander
 * behind it and Back closes it.
 */
@Composable
fun ListPickerDialog(
    picker: ListPickerUi,
    onToggle: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onClose: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    var newName by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onClose) {
        DialogPanel {
            Text(stringResource(R.string.add_to_list), style = MaterialTheme.typography.labelMedium, color = HindsColors.Accent)
            Text(picker.channel.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LazyColumn(Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(picker.lists, key = { it.id }) { list ->
                    val inList = list.id in picker.memberOf
                    PillButton(
                        text = (if (inList) "✓  " else "＋  ") + list.name,
                        onClick = { onToggle(list.id) },
                        primary = inList,
                        modifier = if (list == picker.lists.first()) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvTextField(
                    label = stringResource(R.string.new_list),
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = stringResource(R.string.new_list_hint),
                    modifier = Modifier.weight(1f),
                )
                if (newName.isNotBlank()) {
                    PillButton(stringResource(R.string.create), onClick = {
                        onCreate(newName)
                        newName = ""
                    })
                }
            }
            PillButton(stringResource(R.string.done), onClick = onClose)
        }
    }
    LaunchedEffect(Unit) { firstFocus.requestFocusSoon() }
}

/** Rename or delete a favourites list (☰ on the list in the group column). */
@Composable
fun ListEditDialog(edit: ListEditUi, onRename: (String) -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    val firstFocus = remember { FocusRequester() }
    var name by remember { mutableStateOf(edit.name) }
    var confirmDelete by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose) {
        DialogPanel {
            Text(stringResource(R.string.edit_list), style = MaterialTheme.typography.labelMedium, color = HindsColors.Accent)
            TvTextField(
                label = stringResource(R.string.list_name),
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth().focusRequester(firstFocus),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(stringResource(R.string.save), onClick = { onRename(name) }, primary = true)
                PillButton(
                    stringResource(if (confirmDelete) R.string.delete_confirm else R.string.delete_list),
                    onClick = { if (confirmDelete) onDelete() else confirmDelete = true },
                )
                PillButton(stringResource(R.string.cancel), onClick = onClose)
            }
        }
    }
    LaunchedEffect(Unit) { firstFocus.requestFocusSoon() }
}

@Composable
private fun DialogPanel(content: @Composable () -> Unit) {
    Column(
        Modifier
            .width(520.dp)
            .background(HindsColors.Surface, HindsShapes.Large)
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) { content() }
}
