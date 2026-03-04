package com.example.rwkvnotes.data.local

import androidx.room.Embedded
import androidx.room.Relation

data class NoteWithTags(
    @Embedded val note: NoteEntity,
    @Relation(parentColumn = "id", entityColumn = "note_id")
    val tags: List<TagEntity>,
)
