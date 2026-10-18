package com.hindstv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.hindstv.HindsApp
import com.hindstv.data.db.RadioEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RadioUi(
    val station: RadioEntity,
    val status: PlayStatus = PlayStatus.Tuning,
    val error: String? = null,
    /** "Artist – Title" from the stream's ICY metadata, when the station sends it. */
    val nowPlaying: String? = null,
)

/** Radio: one station at a time through the app's single player, audio only. */
class RadioViewModel(app: HindsApp) : ViewModel() {
    private val media = app.container.media
    private val engine = app.container.player

    val stations: StateFlow<List<RadioEntity>?> = media.radio()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _playing = MutableStateFlow<RadioUi?>(null)
    val playing: StateFlow<RadioUi?> = _playing.asStateFlow()

    /** Black screen while listening (the picture is off but the music keeps going). */
    private val _screenOff = MutableStateFlow(false)
    val screenOff: StateFlow<Boolean> = _screenOff.asStateFlow()

    private var playJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (engine.owner !== this@RadioViewModel || _playing.value == null) return
            when (playbackState) {
                Player.STATE_BUFFERING -> setStatus(PlayStatus.Buffering)
                Player.STATE_READY -> setStatus(PlayStatus.Playing)
                else -> Unit
            }
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            if (engine.owner !== this@RadioViewModel) return
            val title = listOfNotNull(mediaMetadata.artist, mediaMetadata.title)
                .joinToString(" – ")
                .trim()
                .ifEmpty { null }
            _playing.update { it?.copy(nowPlaying = title) }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (engine.owner !== this@RadioViewModel) return
            _playing.update { it?.copy(status = PlayStatus.Error, error = Errors.describe(error)) }
        }
    }

    init {
        engine.player.addListener(listener)
    }

    fun play(station: RadioEntity) {
        stop()
        _playing.value = RadioUi(station)
        playJob = viewModelScope.launch {
            try {
                val url = media.radioUrl(station.id)
                engine.play(url, owner = this@RadioViewModel)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _playing.update { it?.copy(status = PlayStatus.Error, error = Errors.describe(e)) }
            }
        }
    }

    /** Stop button: the station stays on screen so Play starts it again. */
    fun togglePlay() {
        val current = _playing.value ?: return
        if (current.status == PlayStatus.Stopped || current.status == PlayStatus.Error) {
            play(current.station)
        } else {
            playJob?.cancel()
            if (engine.owner === this) engine.stop()
            _playing.update { it?.copy(status = PlayStatus.Stopped, nowPlaying = null) }
        }
    }

    fun setScreenOff(off: Boolean) {
        _screenOff.value = off && _playing.value != null
    }

    /** Leaving Radio (or the app): the provider connection goes back to live TV. */
    fun stop() {
        playJob?.cancel()
        if (engine.owner === this) engine.stop()
        _playing.value = null
        _screenOff.value = false
    }

    private fun setStatus(status: PlayStatus) {
        _playing.update { it?.copy(status = status, error = null) }
    }

    override fun onCleared() {
        engine.player.removeListener(listener)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                RadioViewModel(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as HindsApp)
            }
        }
    }
}
