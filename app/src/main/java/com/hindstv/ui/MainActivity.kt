package com.hindstv.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hindstv.HindsApp
import com.hindstv.ui.components.NavSection
import com.hindstv.ui.radio.RadioScreen
import com.hindstv.ui.search.SearchScreen
import com.hindstv.ui.settings.SettingsScreen
import com.hindstv.ui.setup.SetupScreen
import com.hindstv.ui.theme.HindsTheme
import com.hindstv.ui.tv.ListEditDialog
import com.hindstv.ui.tv.ListPickerDialog
import com.hindstv.ui.tv.TrackPickerDialog
import com.hindstv.ui.tv.TvScreen
import com.hindstv.ui.videos.VideosScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels { AppViewModel.Factory }
    private val videoVm: VideoViewModel by viewModels { VideoViewModel.Factory }
    private val radioVm: RadioViewModel by viewModels { RadioViewModel.Factory }
    private val searchVm: SearchViewModel by viewModels { SearchViewModel.Factory }
    private val settingsVm: SettingsViewModel by viewModels { SettingsViewModel.Factory }

    /** Keys whose DOWN we consumed; their UP must not leak into Compose (e.g. a stray click). */
    private val consumedKeys = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val light by vm.lightTheme.collectAsStateWithLifecycle()
            HindsTheme(light) { HindsRoot(vm, videoVm, radioVm, searchVm, settingsVm, (application as HindsApp).container.trackPicker) }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    override fun onStop() {
        videoVm.onBackground() // saves the resume position before the player stops
        radioVm.stop()
        vm.onBackground()
        super.onStop()
    }

    /**
     * Full-screen remote handling lives here rather than in Compose focus: there is nothing
     * focusable over the video, and this can't be broken by a lost focus target.
     * (RestrictedApi: known lint false positive for overriding Activity.dispatchKeyEvent in a
     * ComponentActivity subclass.)
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && consumedKeys.remove(event.keyCode)) return true
        // Radio's "screen off": any button brings the picture back (and does nothing else).
        if (vm.screen.value == Screen.Radio && radioVm.screenOff.value) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                consumedKeys += event.keyCode
                radioVm.setScreenOff(false)
            }
            return true
        }
        // The video player has nothing focusable; its keys go straight to the view model.
        if (vm.screen.value == Screen.Videos && videoVm.page.value == VideoPageUi.Player && event.keyCode != KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && videoVm.onPlayerKey(event.keyCode)) {
                consumedKeys += event.keyCode
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN) return true // never move focus behind the picture
        }
        // The guide handles every key itself (channel paging, Back, Guide to close).
        if (vm.screen.value == Screen.Tv && !vm.isGuideOpen) {
            val action = RemoteKeys.map(event.keyCode, vm.isFullscreen, vm.invertUpDown)
            if (action != null) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    consumedKeys += event.keyCode
                    if (action == RemoteAction.Back) {
                        if (event.repeatCount == 0 && vm.onBackInFullscreen()) finish()
                    } else {
                        vm.onRemote(action)
                    }
                }
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            // Lets us discover what each remote button sends: adb logcat -s HindsKeys
            Log.d(KEY_LOG_TAG, "${KeyEvent.keyCodeToString(event.keyCode)} (${event.keyCode})")
        }
        return super.dispatchKeyEvent(event)
    }

    private companion object {
        const val KEY_LOG_TAG = "HindsKeys"
    }
}

@Composable
private fun HindsRoot(
    vm: AppViewModel,
    videoVm: VideoViewModel,
    radioVm: RadioViewModel,
    searchVm: SearchViewModel,
    settingsVm: SettingsViewModel,
    trackPicker: TrackPicker,
) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val profileVersion by vm.profileVersion.collectAsStateWithLifecycle()
    LaunchedEffect(profileVersion) { if (profileVersion > 0) videoVm.reset() }

    // One provider connection: whoever is playing lets go before the next section starts.
    val navigate: (NavSection) -> Unit = { section ->
        when (section) {
            NavSection.Live, NavSection.Guide -> {
                videoVm.onLeave()
                radioVm.stop()
                vm.openLive()
                if (section == NavSection.Guide) vm.openGuide()
            }
            NavSection.Videos -> {
                radioVm.stop()
                vm.openSection(Screen.Videos)
            }
            NavSection.Radio -> {
                videoVm.onLeave()
                vm.openSection(Screen.Radio)
            }
            NavSection.Search -> {
                videoVm.onLeave()
                radioVm.stop()
                vm.openSection(Screen.Search)
            }
            NavSection.Settings -> {
                videoVm.onLeave()
                radioVm.stop()
                vm.openSection(Screen.Settings)
                settingsVm.onOpen()
            }
        }
    }

    val listPicker by vm.listPicker.collectAsStateWithLifecycle()
    val listEdit by vm.listEdit.collectAsStateWithLifecycle()
    listPicker?.let { ListPickerDialog(it, vm::toggleInList, vm::createListWith, vm::closeListPicker) }
    listEdit?.let { ListEditDialog(it, vm::renameList, vm::deleteList, vm::closeListEdit) }
    val tracks by trackPicker.choices.collectAsStateWithLifecycle()
    tracks?.let { TrackPickerDialog(it, trackPicker::selectAudio, trackPicker::selectSubtitles, trackPicker::close) }

    when (screen) {
        Screen.Setup -> {
            val setup by vm.setup.collectAsStateWithLifecycle()
            SetupScreen(
                state = setup,
                onFormChange = vm::updateForm,
                onGenerateMac = vm::generateMac,
                onConnect = vm::connect,
                onCancel = vm::cancelSetup,
            )
        }
        Screen.Tv -> TvScreen(vm, navigate)
        Screen.Videos -> VideosScreen(videoVm, navigate, onExit = { navigate(NavSection.Live) })
        Screen.Radio -> RadioScreen(radioVm, navigate, onExit = { navigate(NavSection.Live) })
        Screen.Settings -> SettingsScreen(
            settingsVm,
            navigate,
            onExit = { navigate(NavSection.Live) },
            onChangeAccount = vm::openSetup,
            onRefreshChannels = vm::refreshChannels,
            onEditList = vm::editList,
        )
        Screen.Search -> SearchScreen(
            searchVm,
            navigate,
            onExit = { navigate(NavSection.Live) },
            onWatch = vm::watchFromSearch,
            onOpenVideo = { video ->
                vm.openSection(Screen.Videos)
                videoVm.openDetails(video)
            },
            onPlayRadio = { station ->
                vm.openSection(Screen.Radio)
                radioVm.play(station)
            },
        )
    }
}
