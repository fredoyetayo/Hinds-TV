package com.hindstv.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.hindstv.BuildConfig
import com.hindstv.core.Diagnostics
import com.hindstv.core.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data object UpToDate : UpdateUi
    data class Available(val release: ReleaseInfo) : UpdateUi
    /** [percent] is null while the size is unknown. */
    data class Downloading(val release: ReleaseInfo, val percent: Int?) : UpdateUi
    /** Fire OS is showing its install screen. */
    data class Installing(val release: ReleaseInfo) : UpdateUi
    /** Hinds TV isn't allowed to install apps yet; Settings was opened (or couldn't be). */
    data class NeedsPermission(val release: ReleaseInfo, val settingsOpened: Boolean) : UpdateUi
    data class Failed(val reason: FailReason, val release: ReleaseInfo?) : UpdateUi
}

enum class FailReason { Check, Download, Install }

/** The release a state is about, if any. */
val UpdateUi.release: ReleaseInfo?
    get() = when (this) {
        is UpdateUi.Available -> release
        is UpdateUi.Downloading -> release
        is UpdateUi.Installing -> release
        is UpdateUi.NeedsPermission -> release
        is UpdateUi.Failed -> release
        else -> null
    }

/**
 * Self-update from the repo's GitHub Releases: check `releases/latest`, stream the APK straight
 * into a [PackageInstaller] session and commit it. Fire OS then shows its own Install screen (a
 * sideloaded app can't skip that one press). Shared by the prompt and Settings → About.
 */
class Updater(private val context: Context, private val http: OkHttpClient, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val state: StateFlow<UpdateUi> = _state.asStateFlow()

    /** The version the user said "Later" to; the prompt stays away until a newer one appears. */
    private val _dismissed = MutableStateFlow<String?>(null)
    val dismissed: StateFlow<String?> = _dismissed.asStateFlow()

    private var lastCheckAt = 0L
    private var job: Job? = null

    /** On launch and coming back to the app; quiet, and at most every few hours. */
    fun checkInBackground() {
        if (System.currentTimeMillis() - lastCheckAt < AUTO_CHECK_INTERVAL_MS) return
        check(userAsked = false)
    }

    /** Coming back to the app. The first check waits for launch, after the first channel is playing. */
    fun checkOnResume() {
        if (lastCheckAt != 0L) checkInBackground()
    }

    /** "Check for updates" in Settings. */
    fun check(userAsked: Boolean = true) {
        if (job?.isActive == true) return
        lastCheckAt = System.currentTimeMillis()
        job = scope.launch {
            if (userAsked) _state.value = UpdateUi.Checking
            val release = try {
                latestRelease()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diagnostics.add("update", "check failed: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
                if (userAsked) _state.value = UpdateUi.Failed(FailReason.Check, null)
                return@launch
            }
            _state.value = when {
                release != null && release.versionCode > BuildConfig.VERSION_CODE -> UpdateUi.Available(release)
                userAsked -> UpdateUi.UpToDate
                else -> UpdateUi.Idle
            }
            if (userAsked) _dismissed.value = null
        }
    }

    /** "Later": hide the prompt until a newer build appears (Settings → About still offers this one). */
    fun later() {
        val release = _state.value.release ?: return
        job?.cancel()
        _dismissed.value = release.tag
        _state.value = UpdateUi.Available(release)
    }

    fun install(release: ReleaseInfo) {
        if (job?.isActive == true) return
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateUi.NeedsPermission(release, openInstallPermission())
            return
        }
        job = scope.launch {
            _state.value = UpdateUi.Downloading(release, null)
            try {
                downloadAndCommit(release)
                _state.value = UpdateUi.Installing(release)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diagnostics.add("update", "install failed: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
                _state.value = UpdateUi.Failed(FailReason.Download, release)
            }
        }
    }

    /** Called by [InstallResultReceiver] when Fire OS reports how the install went. */
    internal fun onInstallResult(status: Int, message: String?) {
        val release = (_state.value as? UpdateUi.Installing)?.release
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> Unit // the confirm screen is up
            PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateUi.Idle // usually we are replaced first
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = release?.let(UpdateUi::Available) ?: UpdateUi.Idle
            else -> {
                Diagnostics.add("update", "installer status $status ${message.orEmpty()}")
                _state.value = UpdateUi.Failed(FailReason.Install, release)
            }
        }
    }

    private suspend fun latestRelease(): ReleaseInfo? {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "HindsTV/${BuildConfig.VERSION_NAME}")
            .build()
        return http.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = withContext(Dispatchers.IO) { response.body.string() }
            ReleaseInfo.parse(body)
        }
    }

    private suspend fun downloadAndCommit(release: ReleaseInfo) {
        val request = Request.Builder().url(release.apkUrl).header("User-Agent", "HindsTV/${BuildConfig.VERSION_NAME}").build()
        http.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            withContext(Dispatchers.IO) {
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(context.packageName)
                val total = response.body.contentLength()
                if (total > 0) params.setSize(total)
                val sessionId = installer.createSession(params)
                val session = installer.openSession(sessionId)
                try {
                    session.openWrite(ReleaseInfo.APK_NAME, 0, total).use { out ->
                        response.body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var written = 0L
                            var lastPercent = -1
                            while (true) {
                                if (!isActive) throw CancellationException()
                                val read = input.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                                written += read
                                val percent = if (total > 0) (written * 100 / total).toInt() else null
                                if (percent != lastPercent) {
                                    lastPercent = percent ?: -1
                                    _state.value = UpdateUi.Downloading(release, percent)
                                }
                            }
                        }
                        session.fsync(out)
                    }
                    session.commit(resultIntent(sessionId).intentSender)
                } catch (e: Throwable) {
                    session.abandon()
                    throw e
                } finally {
                    session.close()
                }
            }
        }
    }

    private fun resultIntent(sessionId: Int): PendingIntent {
        val intent = Intent(context, InstallResultReceiver::class.java)
        // The installer adds the status extras, so the intent must be mutable (explicit, so that's safe).
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, sessionId, intent, flags)
    }

    /** Fire OS: Settings → My Fire TV → Developer options → Install unknown apps → Hinds TV. */
    private fun openInstallPermission(): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    private companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/fredoyetayo/Hinds-TV/releases/latest"
        const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
