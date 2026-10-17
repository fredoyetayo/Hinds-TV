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

    /** Swaps in a freshly synced list atomically, so readers never see a half-written state. */
    @Transaction
    open suspend fun replaceAll(genres: List<GenreEntity>, channels: List<ChannelEntity>) {
        clearGenres()
        clearChannels()
        insertGenres(genres)
        channels.chunked(1_000).forEach { insertChannels(it) }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE channelId = :channelId")
    abstract suspend fun removeFavorite(channelId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE channelId = :channelId)")
    abstract suspend fun isFavorite(channelId: String): Boolean
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
    entities = [ChannelEntity::class, GenreEntity::class, FavoriteEntity::class, ProgrammeEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class HindsDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao

    abstract fun epgDao(): EpgDao

    companion object {
        fun create(context: Context): HindsDatabase =
            Room.databaseBuilder(context, HindsDatabase::class.java, "hinds.db")
                // Channels/genres are a re-syncable cache. Bump the version freely; add a real
                // migration once there is user data worth keeping beyond favourites.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
