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

    suspend fun getNotesPage(page: Int, pageSize: Int): List<NoteWithTags> {
        require(page >= 0) { "page must be >= 0" }
        require(pageSize > 0) { "pageSize must be > 0" }
        return db.noteDao().getNotesPage(limit = pageSize, offset = page * pageSize)
    }

    suspend fun searchNotesPage(query: String, page: Int, pageSize: Int): List<NoteWithTags> {
        require(query.isNotBlank()) { "query is blank" }
        require(page >= 0) { "page must be >= 0" }
        require(pageSize > 0) { "pageSize must be > 0" }
        return db.noteDao().searchNotesPage(query = query.trim(), limit = pageSize, offset = page * pageSize)
    }

    suspend fun filterNotesByTagPage(tag: String, page: Int, pageSize: Int): List<NoteWithTags> {
        require(tag.isNotBlank()) { "tag is blank" }
        require(page >= 0) { "page must be >= 0" }
        require(pageSize > 0) { "pageSize must be > 0" }
        return db.noteDao().filterNotesByTagPage(tag = tag.trim(), limit = pageSize, offset = page * pageSize)
    }

    suspend fun getHistoryPage(query: String, tag: String?, page: Int, pageSize: Int): List<NoteWithTags> {
        require(page >= 0) { "page must be >= 0" }
        require(pageSize > 0) { "pageSize must be > 0" }
        val normalizedQuery = query.trim()
        val normalizedTag = tag?.trim().orEmpty()
        return when {
            normalizedQuery.isNotBlank() && normalizedTag.isNotBlank() ->
                db.noteDao().searchNotesByTagPage(
                    query = normalizedQuery,
                    tag = normalizedTag,
                    limit = pageSize,
                    offset = page * pageSize,
                )
            normalizedQuery.isNotBlank() -> searchNotesPage(normalizedQuery, page, pageSize)
            normalizedTag.isNotBlank() -> filterNotesByTagPage(normalizedTag, page, pageSize)
            else -> getNotesPage(page, pageSize)
        }
    }

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
