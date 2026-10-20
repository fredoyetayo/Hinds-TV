package com.hindstv.ui

import androidx.media3.common.C
import com.hindstv.player.PlayerEngine
import com.hindstv.player.TrackChoices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The "Audio & subtitles" dialog's state. One for the app, like the player it controls: live TV
 * opens it with ⏩, the video player with ☰.
 */
class TrackPicker(private val engine: PlayerEngine) {
    private val _choices = MutableStateFlow<TrackChoices?>(null)
    val choices: StateFlow<TrackChoices?> = _choices.asStateFlow()

    fun open() {
        _choices.value = engine.trackChoices()
    }

    fun selectAudio(id: String) {
        engine.selectTrack(C.TRACK_TYPE_AUDIO, id)
        // The player reports the new selection a moment later; show it straight away.
        _choices.value = _choices.value?.let { c -> c.copy(audio = c.audio.map { it.copy(selected = it.id == id) }) }
    }

    /** [id] null turns subtitles off. */
    fun selectSubtitles(id: String?) {
        engine.selectTrack(C.TRACK_TYPE_TEXT, id)
        _choices.value = _choices.value?.let { c ->
            c.copy(subtitles = c.subtitles.map { it.copy(selected = it.id == id) }, subtitlesOff = id == null)
        }
    }

    fun close() {
        _choices.value = null
    }
}
