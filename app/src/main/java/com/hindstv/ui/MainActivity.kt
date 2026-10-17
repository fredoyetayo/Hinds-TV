package com.hindstv.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hindstv.ui.setup.SetupScreen
import com.hindstv.ui.theme.HindsTheme
import com.hindstv.ui.tv.TvScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels { AppViewModel.Factory }

    /** Keys whose DOWN we consumed; their UP must not leak into Compose (e.g. a stray click). */
    private val consumedKeys = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HindsTheme { HindsRoot(vm) } }
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    override fun onStop() {
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
        if (vm.screen.value == Screen.Tv) {
            val action = RemoteKeys.map(event.keyCode, vm.isFullscreen)
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
private fun HindsRoot(vm: AppViewModel) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    when (screen) {
        Screen.Setup -> {
            val setup by vm.setup.collectAsStateWithLifecycle()
            SetupScreen(
                state = setup,
                onFormChange = vm::updateForm,
                onConnect = vm::connect,
                onCancel = vm::cancelSetup,
            )
        }
        Screen.Tv -> TvScreen(vm)
    }
}
