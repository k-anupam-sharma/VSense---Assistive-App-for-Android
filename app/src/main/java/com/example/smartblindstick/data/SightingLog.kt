package com.example.smartblindstick.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sighting_logs")
data class SightingLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val objectLabel: String,
    val timestamp: Long,
    val globalAzimuth: Float,
    val confidence: Float,
    val sceneContext: String
)
