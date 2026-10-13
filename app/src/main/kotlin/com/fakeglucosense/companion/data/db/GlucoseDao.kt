package com.fakeglucosense.companion.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GlucoseDao {

    /** Returns one row id per input; -1 means the row already existed and was ignored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<GlucoseEntity>): List<Long>

    @Query("SELECT * FROM glucose_readings WHERE epochMillis >= :since ORDER BY epochMillis ASC")
    fun observeSince(since: Long): Flow<List<GlucoseEntity>>

    @Query("SELECT * FROM glucose_readings WHERE epochMillis >= :from AND epochMillis < :until ORDER BY epochMillis ASC")
    suspend fun between(from: Long, until: Long): List<GlucoseEntity>

    @Query("SELECT * FROM glucose_readings ORDER BY epochMillis DESC LIMIT 1")
    fun observeLatest(): Flow<GlucoseEntity?>

    @Query("SELECT * FROM glucose_readings ORDER BY epochMillis DESC LIMIT 1")
    suspend fun latest(): GlucoseEntity?

    @Query("DELETE FROM glucose_readings WHERE epochMillis < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int
}
