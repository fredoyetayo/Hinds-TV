package com.hindstv.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.hindstv.BuildConfig
import com.hindstv.R
import com.hindstv.data.stalker.PortalProfile
import com.hindstv.ui.SetupForm
import com.hindstv.ui.SetupUi
import com.hindstv.ui.components.TvTextField
import com.hindstv.ui.theme.HindsColors

@Composable
fun SetupScreen(
    state: SetupUi,
    onFormChange: ((SetupForm) -> SetupForm) -> Unit,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
) {
    val form = state.form
    var advanced by rememberSaveable { mutableStateOf(false) }
    val firstField = remember { FocusRequester() }

    if (state.canCancel) BackHandler(onBack = onCancel)

    Box(Modifier.fillMaxSize().background(HindsColors.Background)) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .width(620.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, color = HindsColors.Accent)
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.setup_subtitle), style = MaterialTheme.typography.bodyMedium, color = HindsColors.Muted)

            TvTextField(
                label = stringResource(R.string.field_portal_url),
                value = form.portalUrl,
                onValueChange = { v -> onFormChange { it.copy(portalUrl = v) } },
                placeholder = "http://example.com/c/",
                keyboardType = KeyboardType.Uri,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().focusRequester(firstField),
            )
            TvTextField(
                label = stringResource(R.string.field_mac),
                value = form.mac,
                onValueChange = { v -> onFormChange { it.copy(mac = v) } },
                placeholder = "00:1A:79:XX:XX:XX",
                keyboardType = KeyboardType.Ascii,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )

            Surface(
                onClick = { advanced = !advanced },
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = HindsColors.Background,
                    focusedContainerColor = HindsColors.Surface,
                ),
            ) {
                Text(
                    text = stringResource(if (advanced) R.string.advanced_hide else R.string.advanced_show),
                    style = MaterialTheme.typography.bodyMedium,
                    color = HindsColors.Muted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (advanced) {
                ChoiceField(
                    label = stringResource(R.string.field_stb_model),
                    value = form.stbType,
                    onClick = {
                        val types = PortalProfile.STB_TYPES
                        val next = types[(types.indexOf(form.stbType) + 1).mod(types.size)]
                        onFormChange { it.copy(stbType = next) }
                    },
                )
                val notSet = stringResource(R.string.field_empty)
                TvTextField(
                    label = stringResource(R.string.field_serial), value = form.serialNumber, placeholder = notSet,
                    onValueChange = { v -> onFormChange { it.copy(serialNumber = v) } }, modifier = Modifier.fillMaxWidth(),
                )
                TvTextField(
                    label = stringResource(R.string.field_device_id), value = form.deviceId, placeholder = notSet,
                    onValueChange = { v -> onFormChange { it.copy(deviceId = v) } }, modifier = Modifier.fillMaxWidth(),
                )
                TvTextField(
                    label = stringResource(R.string.field_device_id2), value = form.deviceId2, placeholder = notSet,
                    onValueChange = { v -> onFormChange { it.copy(deviceId2 = v) } }, modifier = Modifier.fillMaxWidth(),
                )
                TvTextField(
                    label = stringResource(R.string.field_signature), value = form.signature, placeholder = notSet,
                    onValueChange = { v -> onFormChange { it.copy(signature = v) } }, modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = onConnect, enabled = !state.busy) { Text(stringResource(R.string.connect)) }
                if (state.canCancel) {
                    OutlinedButton(onClick = onCancel, enabled = !state.busy) { Text(stringResource(R.string.cancel)) }
                }
            }
            state.progress?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = HindsColors.Accent) }
            state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = HindsColors.Error) }
            Text(
                text = stringResource(R.string.about_line, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelSmall,
                color = HindsColors.Muted,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }

    LaunchedEffect(Unit) { runCatching { firstField.requestFocus() } }
}

@Composable
private fun ChoiceField(label: String, value: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = HindsColors.Surface,
            focusedContainerColor = HindsColors.Accent.copy(alpha = 0.25f),
        ),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = HindsColors.Muted)
            Text("$value  ·  Select to change", style = MaterialTheme.typography.bodyLarge)
        }
    }
}
