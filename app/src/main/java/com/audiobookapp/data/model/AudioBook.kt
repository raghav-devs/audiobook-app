package com.audiobookapp.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audiobooks")
data class AudioBook(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val userId: Int,
    val googleUserId: String? = null,
    val title: String,
    val originalFileName: String,
    val originalFilePath: String,
    val mp3FilePath: String,
    val mp3FileName: String,
    val durationMs: Long = 0L,
    val lastPositionMs: Long = 0L,
    val coverArtPath: String? = null,
    val fileType: String,
    val fileSizeBytes: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val isConversionComplete: Boolean = false,
    val conversionProgress: Int = 0,
    // Streaming playback: true once >= 10% of audio is written to disk
    val isPlayable: Boolean = false,
    // Non-null if conversion failed — shown in library card
    val conversionError: String? = null
)
