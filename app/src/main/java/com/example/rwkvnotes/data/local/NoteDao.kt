package com.example.rwkvnotes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Insert
    suspend fun insertNote(note: NoteEntity): Long

    @Insert
    suspend fun insertTags(tags: List<TagEntity>)

    @Transaction
    @Query("SELECT * FROM notes ORDER BY created_at DESC")
    fun observeNotesWithTags(): Flow<List<NoteWithTags>>
}
