package com.hindstv

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.hindstv.core.Http
import com.hindstv.data.ChannelRepository
import com.hindstv.data.MediaRepository
import com.hindstv.data.ProfileStore
import com.hindstv.data.db.HindsDatabase
import com.hindstv.data.source.ChannelSource
import com.hindstv.data.source.PlaylistSource
import com.hindstv.data.source.SourceProfile
import com.hindstv.data.source.StalkerSource
import com.hindstv.data.stalker.StalkerClient
import com.hindstv.player.PlayerEngine
import okio.Path.Companion.toOkioPath

class HindsApp : Application(), SingletonImageLoader.Factory {
    val container by lazy { AppContainer(this) }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.10).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("logos").toOkioPath())
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .build()
}

/**
 * Manual dependency injection: everything is created lazily on first use, so nothing slows down
 * app start. No DI framework on purpose (startup cost, APK size).
 */
class AppContainer(private val app: Application) {
    val http by lazy { Http.createClient() }
    val profileStore by lazy { ProfileStore(app) }
    val database by lazy { HindsDatabase.create(app) }
    val channels by lazy { ChannelRepository(database.channelDao(), database.epgDao(), profileStore) { source() } }
    val media by lazy { MediaRepository(database.mediaDao()) { source() } }
    val player by lazy { PlayerEngine(app, http) }

    @Volatile private var source: ChannelSource? = null
    @Volatile private var sourceProfile: SourceProfile? = null

    /** The source for the saved profile, reused (with its portal session) while the profile is unchanged. */
    fun source(): ChannelSource {
        val profile = profileStore.profile ?: error("No source configured")
        source?.takeIf { sourceProfile == profile }?.let { return it }
        return synchronized(this) {
            source?.takeIf { sourceProfile == profile } ?: newSource(profile, profileStore.endpoint, profileStore.portalToken).also {
                source = it
                sourceProfile = profile
            }
        }
    }

    /** The portal client when the saved profile is a Stalker portal (for the keep-alive). */
    fun stalkerOrNull(): StalkerClient? = if (profileStore.profile is SourceProfile.Stalker) {
        (source() as? StalkerSource)?.client
    } else {
        null
    }

    /** A source for a profile that is not saved yet (setup screen); portals rediscover their endpoint. */
    fun newSource(profile: SourceProfile, knownEndpoint: String? = null, knownToken: String? = null): ChannelSource = when (profile) {
        is SourceProfile.Stalker -> StalkerSource(StalkerClient(http, profile.portal, knownEndpoint, knownToken))
        is SourceProfile.Playlist -> PlaylistSource(http, profile) { profileStore.discoveredEpgUrl }
    }

    fun useSource(profile: SourceProfile, newSource: ChannelSource) = synchronized(this) {
        source = newSource
        sourceProfile = profile
    }
}
