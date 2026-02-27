package com.stremiovlc.player.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PlaybackPositionDao {

    @Query("SELECT * FROM playback_positions WHERE uri = :uri LIMIT 1")
    suspend fun getPosition(uri: String): PlaybackPositionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePosition(entity: PlaybackPositionEntity)

    @Query("DELETE FROM playback_positions WHERE uri = :uri")
    suspend fun deletePosition(uri: String)

    @Query("DELETE FROM playback_positions WHERE lastPlayed < :olderThanMs")
    suspend fun pruneOldEntries(olderThanMs: Long)
}
