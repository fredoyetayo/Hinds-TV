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
internal fun DialogPanel(content: @Composable () -> Unit) {
    Column(
        Modifier
            .width(520.dp)
            .background(HindsColors.Surface, HindsShapes.Large)
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) { content() }
}

/** ☰ on one of the provider's groups: pin it to the top of the list, or hide it. */
@Composable
fun GroupMenuDialog(
    menu: com.hindstv.ui.GroupMenuUi,
    onTogglePin: (String) -> Unit,
    onHide: (String) -> Unit,
    onClose: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose) {
        DialogPanel {
            Text(stringResource(R.string.group_menu_title), style = MaterialTheme.typography.labelMedium, color = HindsColors.Accent)
            Text(menu.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            PillButton(
                stringResource(if (menu.pinned) R.string.group_unpin else R.string.group_pin),
                onClick = { onTogglePin(menu.groupId) },
                primary = true,
                modifier = Modifier.focusRequester(firstFocus),
            )
            PillButton(stringResource(R.string.group_hide), onClick = { onHide(menu.groupId) })
            Text(stringResource(R.string.group_hide_hint), style = MaterialTheme.typography.bodySmall, color = HindsColors.Muted)
            PillButton(stringResource(R.string.cancel), onClick = onClose)
        }
    }
    LaunchedEffect(Unit) { firstFocus.requestFocusSoon() }
}

/** A guide reminder is due: watch the programme now, or dismiss. */
@Composable
fun ReminderDialog(due: com.hindstv.ui.DueReminder, onWatch: () -> Unit, onDismiss: () -> Unit) {
    val firstFocus = remember { FocusRequester() }
    val starting = due.reminder.start > System.currentTimeMillis() / 1000
    Dialog(onDismissRequest = onDismiss) {
        DialogPanel {
            Text(
                stringResource(if (starting) R.string.reminder_starting_soon else R.string.reminder_on_now),
                style = MaterialTheme.typography.labelMedium,
                color = HindsColors.Accent,
            )
            Text(due.reminder.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(due.channel.name, color = HindsColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(stringResource(R.string.reminder_watch), onClick = onWatch, primary = true, modifier = Modifier.focusRequester(firstFocus))
                PillButton(stringResource(R.string.reminder_dismiss), onClick = onDismiss)
            }
        }
    }
    LaunchedEffect(Unit) { firstFocus.requestFocusSoon() }
}

/** One of the followed teams is about to play (or has kicked off): watch it now. */
@Composable
fun MatchAlertDialog(alert: com.hindstv.ui.MatchAlertUi, onWatch: () -> Unit, onDismiss: () -> Unit) {
    val firstFocus = remember { FocusRequester() }
    val m = alert.match
    Dialog(onDismissRequest = onDismiss) {
        DialogPanel {
            Text(
                stringResource(if (alert.kickingOff) R.string.match_kicking_off else R.string.match_soon, m.kickoffLocal),
                style = MaterialTheme.typography.labelMedium,
                color = HindsColors.Accent,
            )
            Text(m.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (m.competition.isNotBlank()) Text(m.competition, color = HindsColors.Muted, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(stringResource(R.string.reminder_watch), onClick = onWatch, primary = true, modifier = Modifier.focusRequester(firstFocus))
                PillButton(stringResource(R.string.reminder_dismiss), onClick = onDismiss)
            }
        }
    }
    LaunchedEffect(Unit) { firstFocus.requestFocusSoon() }
}

/** ☰ with Home Assistant set up: lists, reminders and recording for a channel or programme. */
@Composable
fun ChannelMenuDialog(
    menu: com.hindstv.ui.ChannelMenuUi,
    onAddToList: () -> Unit,
    onRemind: () -> Unit,
    onRecordNow: () -> Unit,
    onRecordLater: () -> Unit,
    onStopRecording: () -> Unit,
    onClose: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose) {
        DialogPanel {
            Text(menu.channel.name.uppercase(), style = MaterialTheme.typography.labelMedium, color = HindsColors.Accent, maxLines = 1)
            Text(
                menu.programme?.title ?: menu.channel.name,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val first = Modifier.focusRequester(firstFocus)
            when {
                menu.recordingThis -> PillButton(stringResource(R.string.menu_stop_recording), onClick = onStopRecording, primary = true, modifier = first)
                menu.future -> PillButton(stringResource(R.string.menu_record_later), onClick = onRecordLater, primary = true, modifier = first)
                else -> PillButton(stringResource(R.string.menu_record_now), onClick = onRecordNow, primary = true, modifier = first)
            }
            if (menu.future) PillButton(stringResource(R.string.menu_remind), onClick = onRemind)
            PillButton(stringResource(R.string.menu_add_to_list), onClick = onAddToList)
            Text(stringResource(R.string.menu_record_hint), style = MaterialTheme.typography.bodySmall, color = HindsColors.Muted)
            PillButton(stringResource(R.string.cancel), onClick = onClose)
        }
    }
    LaunchedEffect(Unit) { firstFocus.requestFocusSoon() }
}

/** Changing channel would stop the Home Assistant recording: the provider allows one stream. */
@Composable
fun RecordConflictDialog(conflict: com.hindstv.ui.RecordConflictUi, onStopAndWatch: () -> Unit, onKeep: () -> Unit) {
    val firstFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onKeep) {
        DialogPanel {
            Text(stringResource(R.string.conflict_title), style = MaterialTheme.typography.labelMedium, color = HindsColors.Accent)
            Text(conflict.recording, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.conflict_text, conflict.channel.name), color = HindsColors.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(stringResource(R.string.conflict_keep), onClick = onKeep, primary = true, modifier = Modifier.focusRequester(firstFocus))
                PillButton(stringResource(R.string.conflict_stop), onClick = onStopAndWatch)
            }
        }
    }
    LaunchedEffect(Unit) { firstFocus.requestFocusSoon() }
}
