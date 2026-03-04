package com.example.rwkvnotes.note

import androidx.room.withTransaction
import com.example.rwkvnotes.ai.protocol.InferenceFinalJson
import com.example.rwkvnotes.data.local.AppDatabase
import com.example.rwkvnotes.data.local.NoteEntity
import com.example.rwkvnotes.data.local.NoteWithTags
import com.example.rwkvnotes.data.local.TagEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class NoteRepository @Inject constructor(
    private val db: AppDatabase,
) {
    fun observeNotes(): Flow<List<NoteWithTags>> = db.noteDao().observeNotesWithTags()

    suspend fun saveInference(rawInput: String, final: InferenceFinalJson) {
        db.withTransaction {
            val noteId = db.noteDao().insertNote(
                NoteEntity(
                    rawText = rawInput,
                    markdown = final.markdown,
                ),
            )
            if (final.tags.isNotEmpty()) {
                db.noteDao().insertTags(final.tags.map { TagEntity(noteId = noteId, tag = it) })
            }
        }
    }
}
