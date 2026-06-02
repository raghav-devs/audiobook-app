package com.audiobookapp.data.db

import androidx.lifecycle.LiveData
import androidx.room.*
import com.audiobookapp.data.model.AudioBook

@Dao
interface AudioBookDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudioBook(book: AudioBook): Long

    @Update
    suspend fun updateAudioBook(book: AudioBook)

    @Delete
    suspend fun deleteAudioBook(book: AudioBook)

    @Query("SELECT * FROM audiobooks WHERE userId = :userId ORDER BY createdAt DESC")
    fun getBooksByLocalUser(userId: Int): LiveData<List<AudioBook>>

    @Query("SELECT * FROM audiobooks WHERE googleUserId = :googleId ORDER BY createdAt DESC")
    fun getBooksByGoogleUser(googleId: String): LiveData<List<AudioBook>>

    @Query("SELECT * FROM audiobooks WHERE id = :id LIMIT 1")
    suspend fun getBookById(id: Int): AudioBook?

    @Query("UPDATE audiobooks SET lastPositionMs = :position WHERE id = :id")
    suspend fun updateLastPosition(id: Int, position: Long)

    @Query("UPDATE audiobooks SET durationMs = :duration, isConversionComplete = 1, conversionProgress = 100 WHERE id = :id")
    suspend fun markConversionComplete(id: Int, duration: Long)

    @Query("UPDATE audiobooks SET conversionProgress = :progress WHERE id = :id")
    suspend fun updateConversionProgress(id: Int, progress: Int)

    // Streaming: mark file playable once enough chunks are written (>= 10%)
    @Query("UPDATE audiobooks SET isPlayable = 1 WHERE id = :id")
    suspend fun markPlayable(id: Int)

    // Store error message so UI can show it
    @Query("UPDATE audiobooks SET conversionError = :error, isConversionComplete = 0 WHERE id = :id")
    suspend fun markConversionError(id: Int, error: String)
}
