package com.hindstv.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "channels", indices = [Index("genreId"), Index("sortOrder")])
data class ChannelEntity(
    @PrimaryKey val id: String,
    val sortOrder: Int,
    val number: Int,
    val name: String,
    val genreId: String,
    val logo: String?,
    val cmd: String,
    val useTmpLink: Boolean,
    val hasArchive: Boolean,
    val archiveHours: Int,
    val xmltvId: String?,
    val censored: Boolean,
)

@Entity(tableName = "genres")
data class GenreEntity(
    @PrimaryKey val id: String,
    val title: String,
    val sortOrder: Int,
    val censored: Boolean,
)

/** Favourites are user data: they survive channel list refreshes (keyed by portal channel id). */
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val channelId: String,
    val addedAt: Long,
)

/** Guide entry for one of our channels; times are epoch seconds. A re-syncable cache. */
@Entity(tableName = "programmes", primaryKeys = ["channelId", "start"], indices = [Index("stop")])
data class ProgrammeEntity(
    val channelId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
)

/** Videos from a playlist (portals are browsed online instead). A re-syncable cache. */
@Entity(tableName = "videos", indices = [Index("categoryId", "sortOrder")])
data class VideoEntity(
    @PrimaryKey val id: String,
    val categoryId: String,
    val sortOrder: Int,
    val title: String,
    val poster: String?,
    val cmd: String,
)

@Entity(tableName = "video_categories")
data class VideoCategoryEntity(
    @PrimaryKey val id: String,
    val title: String,
    val sortOrder: Int,
)

/** Radio stations from a portal's radio module or a playlist. A re-syncable cache. */
@Entity(tableName = "radio", indices = [Index("sortOrder")])
data class RadioEntity(
    @PrimaryKey val id: String,
    val sortOrder: Int,
    val number: Int,
    val name: String,
    val logo: String?,
    val cmd: String,
)

/**
 * Where the user stopped in a video (user data: survives syncs). [key] is the video id, plus the
 * episode key for series. The video's details are kept so "Continue watching" can resume it
 * without asking the provider first.
 */
@Entity(tableName = "watch_progress", indices = [Index("videoId"), Index("updatedAt")])
data class WatchProgressEntity(
    @PrimaryKey val key: String,
    val videoId: String,
    val categoryId: String,
    val title: String,
    val description: String?,
    val poster: String?,
    val year: String?,
    val durationMin: Int,
    val cmd: String,
    /** Comma-separated episode numbers (series stored as one item). */
    val episodeNumbers: String,
    val isSeries: Boolean,
    val episodeKey: String?,
    val episodeTitle: String?,
    val episodeSeason: String?,
    val episodeNumber: Int?,
    val episodeCmd: String?,
    val episodeSeries: Int?,
    val positionMs: Long,
    val durationMs: Long,
    val finished: Boolean,
    val updatedAt: Long,
)

/** What the guide importer needs per channel. */
data class EpgRef(val channelId: String, val epgId: String?, val name: String)

/** Light projection used by lists and zapping — never load `cmd` etc. for thousands of rows. */
data class ChannelRow(
    val id: String,
    val number: Int,
    val name: String,
    val logo: String?,
    val genreId: String,
    val isFavorite: Boolean,
)

@Dao
abstract class ChannelDao {
    @Query("SELECT * FROM genres ORDER BY sortOrder")
    abstract fun genres(): Flow<List<GenreEntity>>

    @Query(
        "SELECT c.id, c.number, c.name, c.logo, c.genreId, (f.channelId IS NOT NULL) AS isFavorite " +
            "FROM channels c LEFT JOIN favorites f ON f.channelId = c.id ORDER BY c.sortOrder",
    )
    abstract fun allChannels(): Flow<List<ChannelRow>>

    @Query(
        "SELECT c.id, c.number, c.name, c.logo, c.genreId, (f.channelId IS NOT NULL) AS isFavorite " +
            "FROM channels c LEFT JOIN favorites f ON f.channelId = c.id WHERE c.genreId = :genreId ORDER BY c.sortOrder",
    )
    abstract fun channelsInGenre(genreId: String): Flow<List<ChannelRow>>

    @Query(
        "SELECT c.id, c.number, c.name, c.logo, c.genreId, 1 AS isFavorite " +
            "FROM channels c INNER JOIN favorites f ON f.channelId = c.id ORDER BY f.addedAt",
    )
    abstract fun favoriteChannels(): Flow<List<ChannelRow>>

    @Query(
        "SELECT c.id, c.number, c.name, c.logo, c.genreId, (f.channelId IS NOT NULL) AS isFavorite " +
            "FROM channels c LEFT JOIN favorites f ON f.channelId = c.id WHERE c.id = :id",
    )
    abstract suspend fun channelRow(id: String): ChannelRow?

    @Query("SELECT cmd FROM channels WHERE id = :id")
    abstract suspend fun cmd(id: String): String?

    @Query("SELECT id AS channelId, xmltvId AS epgId, name FROM channels")
    abstract suspend fun epgRefs(): List<EpgRef>

    @Query("SELECT COUNT(*) FROM channels")
    abstract suspend fun channelCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertChannels(channels: List<ChannelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertGenres(genres: List<GenreEntity>)

    @Query("DELETE FROM channels")
    protected abstract suspend fun clearChannels()

    @Query("DELETE FROM genres")
    protected abstract suspend fun clearGenres()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertVideos(videos: List<VideoEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertVideoCategories(categories: List<VideoCategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertRadio(stations: List<RadioEntity>)

    @Query("DELETE FROM videos")
    protected abstract suspend fun clearVideos()

    @Query("DELETE FROM video_categories")
    protected abstract suspend fun clearVideoCategories()

    @Query("DELETE FROM radio")
    protected abstract suspend fun clearRadio()

    /** Swaps in a freshly synced list atomically, so readers never see a half-written state. */
    @Transaction
    open suspend fun replaceAll(
        genres: List<GenreEntity>,
        channels: List<ChannelEntity>,
        videoCategories: List<VideoCategoryEntity>,
        videos: List<VideoEntity>,
        radio: List<RadioEntity>,
    ) {
        clearGenres()
        clearChannels()
        clearVideoCategories()
        clearVideos()
        clearRadio()
        insertGenres(genres)
        channels.chunked(1_000).forEach { insertChannels(it) }
        insertVideoCategories(videoCategories)
        videos.chunked(1_000).forEach { insertVideos(it) }
        radio.chunked(1_000).forEach { insertRadio(it) }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE channelId = :channelId")
    abstract suspend fun removeFavorite(channelId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE channelId = :channelId)")
    abstract suspend fun isFavorite(channelId: String): Boolean
}

@Dao
abstract class MediaDao {
    @Query("SELECT * FROM video_categories ORDER BY sortOrder")
    abstract suspend fun videoCategories(): List<VideoCategoryEntity>

    @Query("SELECT * FROM videos WHERE categoryId = :categoryId ORDER BY sortOrder LIMIT :limit OFFSET :offset")
    abstract suspend fun videos(categoryId: String, limit: Int, offset: Int): List<VideoEntity>

    @Query("SELECT * FROM radio ORDER BY sortOrder")
    abstract fun radio(): Flow<List<RadioEntity>>

    @Query("SELECT cmd FROM radio WHERE id = :id")
    abstract suspend fun radioCmd(id: String): String?

    /** Most recently watched unfinished entry per video. */
    @Query(
        "SELECT * FROM watch_progress WHERE finished = 0 AND positionMs >= :minPositionMs " +
            "AND updatedAt = (SELECT MAX(p2.updatedAt) FROM watch_progress p2 WHERE p2.videoId = watch_progress.videoId) " +
            "ORDER BY updatedAt DESC LIMIT :limit",
    )
    abstract fun continueWatching(minPositionMs: Long, limit: Int): Flow<List<WatchProgressEntity>>

    @Query("SELECT * FROM watch_progress WHERE videoId = :videoId ORDER BY updatedAt DESC")
    abstract fun progressFor(videoId: String): Flow<List<WatchProgressEntity>>

    @Query("SELECT * FROM watch_progress WHERE `key` = :key")
    abstract suspend fun progress(key: String): WatchProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveProgress(progress: WatchProgressEntity)

    @Query("DELETE FROM watch_progress")
    abstract suspend fun clearProgress()
}

@Dao
abstract class EpgDao {
    /** The programme on now and the one after it. */
    @Query("SELECT * FROM programmes WHERE channelId = :channelId AND stop > :now ORDER BY start LIMIT 2")
    abstract suspend fun nowNext(channelId: String, now: Long): List<ProgrammeEntity>

    @Query("SELECT * FROM programmes WHERE start <= :now AND stop > :now")
    abstract suspend fun airingAt(now: Long): List<ProgrammeEntity>

    @Query(
        "SELECT * FROM programmes WHERE channelId IN (:channelIds) AND stop > :from AND start < :to " +
            "ORDER BY channelId, start",
    )
    abstract suspend fun window(channelIds: List<String>, from: Long, to: Long): List<ProgrammeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(programmes: List<ProgrammeEntity>)

    @Query("DELETE FROM programmes")
    abstract suspend fun clear()

    @Query("SELECT COUNT(*) FROM programmes")
    abstract suspend fun count(): Int
}

@Database(
    entities = [
        ChannelEntity::class, GenreEntity::class, FavoriteEntity::class, ProgrammeEntity::class,
        VideoEntity::class, VideoCategoryEntity::class, RadioEntity::class, WatchProgressEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class HindsDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao

    abstract fun epgDao(): EpgDao

    abstract fun mediaDao(): MediaDao

    companion object {
        fun create(context: Context): HindsDatabase =
            Room.databaseBuilder(context, HindsDatabase::class.java, "hinds.db")
                // Favourites and resume positions are user data, so known upgrades migrate; only
                // an unknown (older) schema falls back to starting fresh.
                .addMigrations(MIGRATION_2_3)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        /** v3 adds videos, radio and resume positions; everything from v2 is kept. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `videos` (`id` TEXT NOT NULL, `categoryId` TEXT NOT NULL, " +
                        "`sortOrder` INTEGER NOT NULL, `title` TEXT NOT NULL, `poster` TEXT, `cmd` TEXT NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_videos_categoryId_sortOrder` ON `videos` (`categoryId`, `sortOrder`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `video_categories` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`sortOrder` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `radio` (`id` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, " +
                        "`number` INTEGER NOT NULL, `name` TEXT NOT NULL, `logo` TEXT, `cmd` TEXT NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_radio_sortOrder` ON `radio` (`sortOrder`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `watch_progress` (`key` TEXT NOT NULL, `videoId` TEXT NOT NULL, " +
                        "`categoryId` TEXT NOT NULL, `title` TEXT NOT NULL, `description` TEXT, `poster` TEXT, " +
                        "`year` TEXT, `durationMin` INTEGER NOT NULL, `cmd` TEXT NOT NULL, " +
                        "`episodeNumbers` TEXT NOT NULL, `isSeries` INTEGER NOT NULL, `episodeKey` TEXT, " +
                        "`episodeTitle` TEXT, `episodeSeason` TEXT, `episodeNumber` INTEGER, `episodeCmd` TEXT, " +
                        "`episodeSeries` INTEGER, `positionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, " +
                        "`finished` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_watch_progress_videoId` ON `watch_progress` (`videoId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_watch_progress_updatedAt` ON `watch_progress` (`updatedAt`)")
            }
        }
    }
}
