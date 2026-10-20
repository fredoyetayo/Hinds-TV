package com.hindstv.ui.components

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * ⏪/⏩ through a long lazy list or grid: focus jumps a page at a time instead of one row per
 * press. The container takes [modifier]; each item takes [pageJumpItem] with its index (on the item or a
 * wrapper around it: a focus requester on a wrapper focuses its first focusable child).
 */
@Stable
class PageJumpState internal constructor(
    private val scope: CoroutineScope,
    private val page: Int,
) {
    internal var count = 0
    internal var scrollTo: suspend (Int) -> Unit = {}
    internal val requester = FocusRequester()
    internal var target by mutableIntStateOf(-1)
    internal var focused = -1
    private var job: Job? = null

    /** Scrolls to [index] and focuses it, e.g. the card a details page was opened from. */
    fun focus(index: Int) {
        if (index !in 0 until count) return
        focused = index
        job?.cancel()
        job = scope.launch {
            scrollTo(index)
            target = index
            requester.requestFocusSoon()
            target = -1
        }
    }

    val modifier: Modifier = Modifier.onPreviewKeyEvent { event ->
        val delta = when (event.nativeKeyEvent.keyCode) {
            KeyEvent.KEYCODE_MEDIA_REWIND -> -page
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> page
            else -> return@onPreviewKeyEvent false
        }
        if (event.type == KeyEventType.KeyDown && count > 0) {
            val to = (focused.coerceAtLeast(0) + delta).coerceIn(0, count - 1)
            if (to != focused) focus(to)
        }
        true
    }
}

fun Modifier.pageJumpItem(state: PageJumpState, index: Int): Modifier =
    onFocusChanged { if (it.hasFocus) state.focused = index }
        .then(if (index == state.target) Modifier.focusRequester(state.requester) else Modifier)

/** [scrollTo] brings an index on screen (a list may want to leave a few rows above it). */
@Composable
fun rememberPageJump(count: Int, page: Int, scrollTo: suspend (Int) -> Unit): PageJumpState {
    val scope = rememberCoroutineScope()
    val latestScroll by rememberUpdatedState(scrollTo)
    val state = remember(page) { PageJumpState(scope, page) }
    state.count = count
    state.scrollTo = { latestScroll(it) }
    return state
}
