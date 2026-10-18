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
import com.hindstv.R
import com.hindstv.data.db.RadioEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
class RadioViewModel(private val app: HindsApp) : ViewModel() {
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
    /** Addresses still to try for the current station, if the current one doesn't start. */
    private var fallbacks: List<String> = emptyList()
    private var startWatch: Job? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (engine.owner !== this@RadioViewModel || _playing.value == null) return
            when (playbackState) {
                Player.STATE_BUFFERING -> setStatus(PlayStatus.Buffering)
                Player.STATE_READY -> {
                    startWatch?.cancel()
                    fallbacks = emptyList()
                    setStatus(PlayStatus.Playing)
                }
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
            if (!tryNext()) {
                _playing.update { it?.copy(status = PlayStatus.Error, error = Errors.describe(error)) }
            }
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
                val urls = media.radioUrls(station.id)
                fallbacks = urls.drop(1)
                start(urls.first())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _playing.update { it?.copy(status = PlayStatus.Error, error = Errors.describe(e)) }
            }
        }
    }

    /**
     * Plays [url] and gives it [START_TIMEOUT_MS] to produce sound; a stream that connects but
     * never sends audio would otherwise sit on "Tuning…" for ever.
     */
    private fun start(url: String) {
        engine.play(url, owner = this)
        startWatch?.cancel()
        startWatch = viewModelScope.launch {
            delay(START_TIMEOUT_MS)
            if (_playing.value?.status == PlayStatus.Playing) return@launch
            if (!tryNext()) {
                if (engine.owner === this@RadioViewModel) engine.stop()
                _playing.update { it?.copy(status = PlayStatus.Error, error = app.getString(R.string.radio_no_audio)) }
            }
        }
    }

    /** Moves on to the next address for the station; false when there is none left. */
    private fun tryNext(): Boolean {
        val next = fallbacks.firstOrNull() ?: return false
        fallbacks = fallbacks.drop(1)
        start(next)
        return true
    }

    /** Stop button: the station stays on screen so Play starts it again. */
    fun togglePlay() {
        val current = _playing.value ?: return
        if (current.status == PlayStatus.Stopped || current.status == PlayStatus.Error) {
            play(current.station)
        } else {
            playJob?.cancel()
            startWatch?.cancel()
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
        startWatch?.cancel()
        fallbacks = emptyList()
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
        private const val START_TIMEOUT_MS = 15_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                RadioViewModel(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as HindsApp)
            }
        }
    }
}
