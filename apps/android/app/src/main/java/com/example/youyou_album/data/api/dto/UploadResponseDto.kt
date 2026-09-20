package com.example.youyou_album.data.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class UploadResponseDto(
    val mediaId: String,
    val path: String,
    val size: Long,
    val sha256: String,
)
