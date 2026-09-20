package com.example.youyou_album.presentation.photo

import com.example.youyou_album.domain.model.Photo

/** Follow the selected media through sorting and remote-to-local hash merging. */
internal fun PhotoDetailUiState.refreshPhotos(updated: List<Photo>, initialId: String): PhotoDetailUiState {
    val selected = photos.getOrNull(currentIndex)
    val selectedId = if (isLoading) initialId else selected?.id
    val sameId = updated.indexOfFirst { it.id == selectedId }
    val mergedLocal = if (sameId < 0 && selected?.sourceType == "server" && !selected.contentHash.isNullOrEmpty()) {
        updated.indexOfFirst { it.sourceType == "local" && it.contentHash == selected.contentHash }
    } else -1
    val index = when {
        sameId >= 0 -> sameId
        mergedLocal >= 0 -> mergedLocal
        else -> currentIndex.coerceIn(0, (updated.size - 1).coerceAtLeast(0))
    }
    return copy(photos = updated, currentIndex = index, isLoading = false)
}
