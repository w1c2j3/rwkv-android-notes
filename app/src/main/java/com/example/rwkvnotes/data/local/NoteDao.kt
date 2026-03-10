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

    @Transaction
    @Query("SELECT * FROM notes ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    suspend fun getNotesPage(limit: Int, offset: Int): List<NoteWithTags>

    @Transaction
    @Query(
        """
        SELECT * FROM notes
        WHERE raw_text LIKE '%' || :query || '%' OR markdown LIKE '%' || :query || '%'
        ORDER BY created_at DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun searchNotesPage(query: String, limit: Int, offset: Int): List<NoteWithTags>

    @Transaction
    @Query(
        """
        SELECT DISTINCT n.* FROM notes n
        INNER JOIN tags t ON t.note_id = n.id
        WHERE LOWER(t.tag) = LOWER(:tag)
        ORDER BY n.created_at DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun filterNotesByTagPage(tag: String, limit: Int, offset: Int): List<NoteWithTags>

    @Transaction
    @Query(
        """
        SELECT DISTINCT n.* FROM notes n
        INNER JOIN tags t ON t.note_id = n.id
        WHERE LOWER(t.tag) = LOWER(:tag)
          AND (n.raw_text LIKE '%' || :query || '%' OR n.markdown LIKE '%' || :query || '%')
        ORDER BY n.created_at DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun searchNotesByTagPage(query: String, tag: String, limit: Int, offset: Int): List<NoteWithTags>
}
