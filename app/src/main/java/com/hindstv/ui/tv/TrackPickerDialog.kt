package com.hindstv.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hindstv.R
import com.hindstv.player.TrackChoices
import com.hindstv.player.TrackOption
import com.hindstv.ui.components.PillButton
import com.hindstv.ui.components.requestFocusSoon
import com.hindstv.ui.theme.HindsColors

/** "Audio & subtitles": audio tracks on the left, subtitles (with Off) on the right. Back closes. */
@Composable
fun TrackPickerDialog(
    choices: TrackChoices,
    onAudio: (String) -> Unit,
    onSubtitles: (String?) -> Unit,
    onClose: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose) {
        DialogPanel {
            Text(stringResource(R.string.tracks_title), style = MaterialTheme.typography.headlineSmall)
            if (choices.isEmpty) {
                Text(stringResource(R.string.tracks_none), style = MaterialTheme.typography.bodyMedium, color = HindsColors.Muted)
                PillButton(stringResource(R.string.settings_ok), onClick = onClose, primary = true, modifier = Modifier.focusRequester(firstFocus))
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Heading(stringResource(R.string.tracks_audio))
                        if (choices.audio.size <= 1) {
                            Text(
                                choices.audio.firstOrNull()?.label ?: stringResource(R.string.tracks_no_audio_choice),
                                style = MaterialTheme.typography.bodyMedium,
                                color = HindsColors.Muted,
                            )
                        } else {
                            Options(choices.audio, onAudio, firstFocus)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Heading(stringResource(R.string.tracks_subtitles))
                        PillButton(
                            check(choices.subtitlesOff) + stringResource(R.string.tracks_off),
                            onClick = { onSubtitles(null) },
                            primary = choices.subtitlesOff,
                            modifier = if (choices.audio.size <= 1) Modifier.focusRequester(firstFocus) else Modifier,
                        )
                        Options(choices.subtitles, { onSubtitles(it) }, focus = null)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { firstFocus.requestFocusSoon() }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = HindsColors.Accent)
}

@Composable
private fun Options(options: List<TrackOption>, onPick: (String) -> Unit, focus: FocusRequester?) {
    val focusOn = options.firstOrNull { it.selected } ?: options.firstOrNull()
    LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options, key = { it.id }) { option ->
            PillButton(
                check(option.selected) + option.label,
                onClick = { onPick(option.id) },
                primary = option.selected,
                modifier = if (focus != null && option == focusOn) Modifier.focusRequester(focus) else Modifier,
            )
        }
    }
}

private fun check(selected: Boolean) = if (selected) "✓  " else ""
