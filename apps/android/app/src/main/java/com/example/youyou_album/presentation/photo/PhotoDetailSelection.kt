package com.example.youyou_album.presentation.photo

import com.example.youyou_album.domain.model.Photo

/** Follow the selected media through sorting and remote-to-local hash merging. */
internal fun PhotoDetailUiState.refreshPhotos(updated: List<Photo>, initialId: String): PhotoDetailUiState {
    val selected = photos.getOrNull(currentIndex)
    // 时间线是两段式发射：首屏 200 条先到，用户在更深处点开的那一张通常不在里面。
    // 定位不到时不能就地落到第 0 张——一旦落下，后续发射就以这张为锚点，永远回不到
    // 用户点的那张（真机表现：从时间线深处点开，详情页永远显示最新一张）。
    val anchorId = if (!anchored && initialId.isNotBlank()) initialId else selected?.id
    val sameId = if (anchorId == null) -1 else updated.indexOfFirst { it.id == anchorId }
    val mergedLocal = if (sameId < 0 && selected?.sourceType == "server" && !selected.contentHash.isNullOrEmpty()) {
        updated.indexOfFirst { it.sourceType == "local" && it.contentHash == selected.contentHash }
    } else {
        -1
    }
    val index = when {
        sameId >= 0 -> sameId
        mergedLocal >= 0 -> mergedLocal
        else -> currentIndex.coerceIn(0, (updated.size - 1).coerceAtLeast(0))
    }
    return copy(
        photos = updated,
        currentIndex = index,
        isLoading = false,
        anchored = anchored || initialId.isBlank() || sameId >= 0 || mergedLocal >= 0,
    )
}
