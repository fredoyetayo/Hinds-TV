package com.hindstv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.hindstv.BuildConfig
import com.hindstv.R
import com.hindstv.ui.components.PillButton
import com.hindstv.ui.components.ProgressLine
import com.hindstv.ui.components.requestFocusSoon
import com.hindstv.ui.theme.HindsColors
import com.hindstv.ui.tv.DialogPanel
import com.hindstv.update.FailReason
import com.hindstv.update.UpdateUi
import com.hindstv.update.release

/** States that show the update prompt (Settings → About shows the rest). */
fun UpdateUi.showsPrompt(dismissedTag: String?): Boolean {
    val tag = release?.tag ?: return false
    return tag != dismissedTag && this !is UpdateUi.Checking
}

/** "Update available" → downloading → Fire OS's Install screen. Back or Later hides it. */
@Composable
fun UpdateDialog(state: UpdateUi, onUpdate: () -> Unit, onLater: () -> Unit) {
    val release = state.release ?: return
    val firstFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onLater) {
        DialogPanel {
            Text(stringResource(R.string.update_title), style = MaterialTheme.typography.labelMedium, color = HindsColors.Accent)
            Text(
                stringResource(R.string.update_version, release.versionName),
                style = MaterialTheme.typography.headlineSmall,
            )
            when (state) {
                is UpdateUi.Downloading -> {
                    Text(
                        state.percent?.let { stringResource(R.string.update_downloading_percent, it) }
                            ?: stringResource(R.string.update_downloading),
                        color = HindsColors.Muted,
                    )
                    ProgressLine((state.percent ?: 0) / 100f, Modifier.fillMaxWidth(), color = HindsColors.Accent)
                    PillButton(stringResource(R.string.cancel), onClick = onLater, modifier = Modifier.focusRequester(firstFocus))
                }
                is UpdateUi.Installing -> {
                    Text(stringResource(R.string.update_installing), color = HindsColors.Muted)
                    PillButton(stringResource(R.string.settings_ok), onClick = onLater, modifier = Modifier.focusRequester(firstFocus))
                }
                else -> {
                    val message = when (state) {
                        is UpdateUi.NeedsPermission -> stringResource(
                            if (state.settingsOpened) R.string.update_permission_opened else R.string.update_permission_manual,
                        )
                        is UpdateUi.Failed -> stringResource(
                            when (state.reason) {
                                FailReason.Check -> R.string.update_failed_check
                                FailReason.Download -> R.string.update_failed_download
                                FailReason.Install -> R.string.update_failed_install
                            },
                        )
                        else -> stringResource(R.string.update_current, BuildConfig.VERSION_NAME)
                    }
                    Text(message, color = HindsColors.Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton(
                            stringResource(if (state is UpdateUi.Failed) R.string.update_retry else R.string.update_now),
                            onClick = onUpdate,
                            primary = true,
                            modifier = Modifier.focusRequester(firstFocus),
                        )
                        PillButton(stringResource(R.string.update_later), onClick = onLater)
                    }
                }
            }
        }
    }
    // Each state has its own buttons; put focus back on the first one whenever it changes.
    LaunchedEffect(state::class) { firstFocus.requestFocusSoon() }
}
