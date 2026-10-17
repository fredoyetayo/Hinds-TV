package com.hindstv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.hindstv.ui.theme.HindsColors
import com.hindstv.ui.theme.HindsStyle

private val FieldShape = RoundedCornerShape(12.dp)

/**
 * A text field that behaves well with a D-pad: it is a normal focusable button until selected,
 * then becomes an editable field and opens the Fire TV keyboard. Done/Back returns to button mode,
 * so the remote can never get "stuck" inside a text field.
 */
@Composable
fun TvTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
) {
    var editing by remember { mutableStateOf(false) }
    var restoreFocus by remember { mutableStateOf(false) }
    val buttonFocus = remember { FocusRequester() }

    if (editing) {
        val fieldFocus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        var fieldValue by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
        var hadFocus by remember { mutableStateOf(false) }
        fun finish(returnFocus: Boolean) {
            keyboard?.hide()
            editing = false
            restoreFocus = returnFocus
        }
        BackHandler { finish(returnFocus = true) }

        Column(modifier) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = HindsColors.Accent)
            BasicTextField(
                value = fieldValue,
                onValueChange = {
                    fieldValue = it
                    onValueChange(it.text)
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = HindsColors.Text),
                cursorBrush = SolidColor(HindsColors.Accent),
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                ),
                keyboardActions = KeyboardActions(onDone = { finish(returnFocus = true) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(HindsColors.Surface, FieldShape)
                    .border(2.dp, HindsColors.Accent, FieldShape)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .focusRequester(fieldFocus)
                    .onFocusChanged {
                        if (it.isFocused) {
                            hadFocus = true
                        } else if (hadFocus) {
                            finish(returnFocus = false) // user moved away with the D-pad
                        }
                    },
            )
        }
        LaunchedEffect(Unit) {
            runCatching { fieldFocus.requestFocus() }
            keyboard?.show()
        }
    } else {
        Surface(
            onClick = { editing = true },
            enabled = enabled,
            modifier = modifier.focusRequester(buttonFocus),
            shape = ClickableSurfaceDefaults.shape(FieldShape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            colors = HindsStyle.surfaceColors(),
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                val content = LocalContentColor.current
                Text(label, style = MaterialTheme.typography.labelMedium, color = content.copy(alpha = 0.6f))
                Text(
                    text = value.ifEmpty { placeholder },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (value.isEmpty()) content.copy(alpha = 0.45f) else content,
                )
            }
        }
        LaunchedEffect(restoreFocus) {
            if (restoreFocus) {
                restoreFocus = false
                runCatching { buttonFocus.requestFocus() }
            }
        }
    }
}
