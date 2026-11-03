package com.example.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioDao {
    @Query("SELECT * FROM converted_audios ORDER BY createdAt DESC")
    fun getAllAudios(): Flow<List<ConvertedAudio>>

    @Query("SELECT * FROM converted_audios WHERE format = :format ORDER BY createdAt DESC")
    fun getAudiosByFormat(format: String): Flow<List<ConvertedAudio>>

    @Query("SELECT * FROM converted_audios WHERE isFavorite = 1 ORDER BY createdAt DESC")
    fun getFavoriteAudios(): Flow<List<ConvertedAudio>>

    @Query("SELECT * FROM converted_audios WHERE title LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    fun searchAudios(query: String): Flow<List<ConvertedAudio>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudio(audio: ConvertedAudio): Long

    @Update
    suspend fun updateAudio(audio: ConvertedAudio)

    @Delete
    suspend fun deleteAudio(audio: ConvertedAudio)

    @Query("DELETE FROM converted_audios WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE converted_audios SET isFavorite = NOT isFavorite WHERE id = :id")
    suspend fun toggleFavorite(id: Long)

    @Query("UPDATE converted_audios SET title = :newTitle WHERE id = :id")
    suspend fun updateTitle(id: Long, newTitle: String)
}
