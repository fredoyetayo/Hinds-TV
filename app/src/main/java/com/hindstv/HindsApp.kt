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
import com.hindstv.data.ProfileStore
import com.hindstv.data.db.HindsDatabase
import com.hindstv.data.stalker.PortalProfile
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
    val channels by lazy { ChannelRepository(database.channelDao(), profileStore) { stalker() } }
    val player by lazy { PlayerEngine(app, http) }

    @Volatile private var client: StalkerClient? = null

    /** The client for the saved profile, reusing its session while the profile is unchanged. */
    fun stalker(): StalkerClient {
        val profile = profileStore.profile ?: error("No portal profile configured")
        client?.takeIf { it.profile == profile }?.let { return it }
        return synchronized(this) {
            client?.takeIf { it.profile == profile }
                ?: StalkerClient(http, profile, profileStore.endpoint).also { client = it }
        }
    }

    /** A client for a profile that is not saved yet (setup screen); always rediscovers the endpoint. */
    fun newClient(profile: PortalProfile) = StalkerClient(http, profile)

    fun useClient(newClient: StalkerClient) {
        client = newClient
    }
}
