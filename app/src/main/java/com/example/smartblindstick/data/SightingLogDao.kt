package com.example.smartblindstick.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SightingLogDao {
    @Insert
    fun insert(log: SightingLog)

    @Query("SELECT * FROM sighting_logs WHERE objectLabel LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT 5")
    fun searchLogs(query: String): List<SightingLog>

    @Query("DELETE FROM sighting_logs WHERE timestamp < :cutoffTime")
    fun deleteOlderThan(cutoffTime: Long)
}
