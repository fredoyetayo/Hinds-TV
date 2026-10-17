package com.hindstv.ui

import android.view.KeyEvent

/** What a Fire TV remote button means to the app. */
enum class RemoteAction { ChannelUp, ChannelDown, OpenList, OpenGuide, ShowInfo, Recall, ToggleFavorite, PlayPause, Back }

/**
 * Remote mapping (see docs/RESEARCH.md §9). Home, Alexa, volume, mute, power, settings and the app
 * shortcut buttons are handled by Fire OS and never reach the app.
 *
 * - Anywhere outside the guide: the Live/Guide (TV) button opens the TV guide.
 * - Full-screen video: ▲/CH+ next channel, ▼/CH− previous, Select/◀ open the channel list,
 *   ▶ shows info, ⏪ jumps back to the previous channel, ☰ toggles favourite, ⏯ stops/resumes,
 *   Back asks to press again to exit.
 * - While the channel list is open, only CH+/CH− are taken here; everything else drives focus.
 */
object RemoteKeys {
    /** Set to true to make ▲ go to the previous channel instead (some people prefer list order). */
    const val INVERT_UP_DOWN = false

    fun map(keyCode: Int, fullscreen: Boolean): RemoteAction? {
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP -> return RemoteAction.ChannelUp
            KeyEvent.KEYCODE_CHANNEL_DOWN -> return RemoteAction.ChannelDown
            KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_TV -> return RemoteAction.OpenGuide
        }
        if (!fullscreen) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (INVERT_UP_DOWN) RemoteAction.ChannelDown else RemoteAction.ChannelUp
            KeyEvent.KEYCODE_DPAD_DOWN -> if (INVERT_UP_DOWN) RemoteAction.ChannelUp else RemoteAction.ChannelDown
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_LEFT,
            -> RemoteAction.OpenList
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> RemoteAction.ShowInfo
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_LAST_CHANNEL -> RemoteAction.Recall
            KeyEvent.KEYCODE_MENU -> RemoteAction.ToggleFavorite
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            -> RemoteAction.PlayPause
            KeyEvent.KEYCODE_BACK -> RemoteAction.Back
            else -> null
        }
    }
}
