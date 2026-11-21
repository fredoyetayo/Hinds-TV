package com.hindstv.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import com.hindstv.core.Diagnostics
import com.hindstv.live.FrameRates
import kotlin.math.abs

/**
 * Switches the TV's refresh rate to suit the video (50 Hz for UK broadcasts) through the window's
 * preferred display mode. Fire OS 8 is Android 11, where only this kind of switch can change the
 * HDMI rate. The rate is measured from the frames themselves, because broadcast TS rarely states it.
 * Leaving the app hands the display back to Fire OS's own setting.
 */
@OptIn(UnstableApi::class)
class FrameRateMatcher(
    private val activity: Activity,
    private val player: ExoPlayer,
    private val enabled: () -> Boolean,
) {
    private val main = Handler(Looper.getMainLooper())
    private val timestamps = ArrayList<Long>(SAMPLE)
    @Volatile private var sampling = false
    private var appliedFps = 0f

    private val frames = VideoFrameMetadataListener { presentationTimeUs, _, _: Format, _ ->
        if (!sampling) return@VideoFrameMetadataListener
        synchronized(timestamps) {
            timestamps += presentationTimeUs
            if (timestamps.size < SAMPLE) return@VideoFrameMetadataListener
            val fps = FrameRates.estimate(timestamps.toList())
            timestamps.clear()
            sampling = false
            if (fps != null) main.post { apply(fps) }
        }
    }

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() = restart()

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) = restart()
    }

    fun attach() {
        player.setVideoFrameMetadataListener(frames)
        player.addListener(listener)
    }

    fun detach() {
        player.clearVideoFrameMetadataListener(frames)
        player.removeListener(listener)
        sampling = false
    }

    /** A new picture: measure its frame rate afresh. */
    private fun restart() {
        if (!enabled()) return
        synchronized(timestamps) { timestamps.clear() }
        sampling = true
    }

    private fun apply(fps: Float) {
        if (!enabled() || abs(fps - appliedFps) < 0.01f) return
        val display = activity.window.decorView.display ?: return
        val current = display.mode
        val sameSize = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        val refresh = FrameRates.bestRefresh(fps, sameSize.map(Display.Mode::getRefreshRate)) ?: return
        val mode = sameSize.firstOrNull { abs(it.refreshRate - refresh) < 0.01f } ?: return
        appliedFps = fps
        if (mode.modeId == current.modeId) return
        Diagnostics.add("display", "video ${"%.2f".format(fps)} fps: switching to ${"%.2f".format(refresh)} Hz")
        val params = activity.window.attributes
        params.preferredDisplayModeId = mode.modeId
        activity.window.attributes = params
    }

    private companion object {
        const val SAMPLE = 48
    }
}
