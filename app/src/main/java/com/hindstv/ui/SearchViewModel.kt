package com.hindstv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hindstv.HindsApp
import com.hindstv.data.db.ChannelRow
import com.hindstv.data.db.ProgrammeEntity
import com.hindstv.data.db.RadioEntity
import com.hindstv.data.source.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A programme found by search, with the channel it is on. */
data class ProgrammeHit(val programme: ProgrammeEntity, val channel: ChannelRow)

data class SearchResults(
    val query: String = "",
    val channels: List<ChannelRow> = emptyList(),
    val programmes: List<ProgrammeHit> = emptyList(),
    val videos: List<VideoItem> = emptyList(),
    val radio: List<RadioEntity> = emptyList(),
    val searching: Boolean = false,
    /** Video search asks the provider; when that fails the local results still show. */
    val videoError: String? = null,
) {
    val isEmpty: Boolean get() = channels.isEmpty() && programmes.isEmpty() && videos.isEmpty() && radio.isEmpty()
}

/**
 * Search as you type across channels, what's on (the guide), videos and radio. Local results
 * (database) come back in milliseconds; the provider's video search runs alongside and never
 * holds the rest up.
 */
class SearchViewModel(app: HindsApp) : ViewModel() {
    private val channels = app.container.channels
    private val media = app.container.media
    private val store = app.container.profileStore

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow(SearchResults())
    val results: StateFlow<SearchResults> = _results.asStateFlow()

    private val _recent = MutableStateFlow(store.recentSearches)
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChange(text: String) {
        _query.value = text
        searchJob?.cancel()
        val q = text.trim()
        if (q.length < MIN_CHARS) {
            _results.value = SearchResults(query = q)
            return
        }
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS) // typing on a TV keyboard is slow; don't search every letter
            _results.value = _results.value.copy(query = q, searching = true)
            coroutineScope {
                val video = async {
                    try {
                        media.searchVideos(q) to null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList<VideoItem>() to Errors.describe(e)
                    }
                }
                val now = System.currentTimeMillis() / 1000
                val local = SearchResults(
                    query = q,
                    channels = channels.searchChannels(q),
                    programmes = channels.searchProgrammes(q, now).mapNotNull { p ->
                        channels.channel(p.channelId)?.let { ProgrammeHit(p, it) }
                    },
                    radio = media.searchRadio(q),
                    searching = true,
                )
                _results.value = local
                val (videos, error) = video.await()
                _results.value = local.copy(videos = videos, videoError = error, searching = false)
            }
        }
    }

    /** Remembers a search the user acted on, so it is one press away next time. */
    fun remember(text: String = _query.value) {
        val q = text.trim()
        if (q.length < MIN_CHARS) return
        val list = (listOf(q) + _recent.value.filterNot { it.equals(q, ignoreCase = true) }).take(MAX_RECENT)
        _recent.value = list
        store.recentSearches = list
    }

    fun clearRecent() {
        _recent.value = emptyList()
        store.recentSearches = emptyList()
    }

    companion object {
        private const val MIN_CHARS = 2
        private const val DEBOUNCE_MS = 300L
        private const val MAX_RECENT = 8

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { SearchViewModel(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as HindsApp) }
        }
    }
}
