package com.example.rwkvnotes.index

import com.example.rwkvnotes.data.local.NoteWithTags
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SemanticIndexSnapshot(
    val byTag: Map<String, List<Long>>,
)

@Singleton
class SemanticIndexService @Inject constructor() {
    private val _snapshot = MutableStateFlow(SemanticIndexSnapshot(emptyMap()))
    val snapshot: StateFlow<SemanticIndexSnapshot> = _snapshot.asStateFlow()

    fun rebuild(notes: List<NoteWithTags>) {
        val map = linkedMapOf<String, MutableList<Long>>()
        notes.forEach { item ->
            item.tags.forEach { tag ->
                map.getOrPut(tag.tag.lowercase()) { mutableListOf() }.add(item.note.id)
            }
        }
        _snapshot.value = SemanticIndexSnapshot(map.mapValues { it.value.toList() })
    }
}
