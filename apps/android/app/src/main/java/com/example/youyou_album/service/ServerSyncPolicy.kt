package com.example.youyou_album.service

/**
 * 只有同一身份、且清单游标已经提交时才走增量。
 * 空游标表示整份清单还没写完（或上次中断在提交前），必须重新拉快照，不能当成「已经同步完」。
 */
internal fun hasIncrementalCursor(
    savedNamespace: String?,
    savedCursor: String?,
    activeNamespace: String,
): Boolean =
    !savedNamespace.isNullOrBlank() &&
        savedNamespace == activeNamespace &&
        !savedCursor.isNullOrBlank()
