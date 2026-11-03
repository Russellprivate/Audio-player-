package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "converted_audios")
data class ConvertedAudio(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val filePath: String,
    val fileUri: String,
    val originalVideoName: String,
    val format: String, // MP3, M4A, WAV, FLAC
    val bitrateKbps: Int,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false
)
