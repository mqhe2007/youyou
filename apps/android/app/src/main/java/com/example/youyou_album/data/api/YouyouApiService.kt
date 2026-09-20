package com.example.youyou_album.data.api

import com.example.youyou_album.BuildConfig
import com.example.youyou_album.data.api.dto.BootstrapStartDto
import com.example.youyou_album.data.api.dto.BootstrapStatusDto
import com.example.youyou_album.data.api.dto.ChangesPageDto
import com.example.youyou_album.data.api.dto.CreateTagRequestDto
import com.example.youyou_album.data.api.dto.DeviceCredentialsDto
import com.example.youyou_album.data.api.dto.MediaDeleteRequestDto
import com.example.youyou_album.data.api.dto.MediaDeleteResponseDto
import com.example.youyou_album.data.api.dto.MediaFolderPageDto
import com.example.youyou_album.data.api.dto.MediaOperationDto
import com.example.youyou_album.data.api.dto.MediaPageDto
import com.example.youyou_album.data.api.dto.PairRequestDto
import com.example.youyou_album.data.api.dto.ServerHealthDto
import com.example.youyou_album.data.api.dto.ServerInfoDto
import com.example.youyou_album.data.api.dto.ServerJobDto
import com.example.youyou_album.data.api.dto.ServerMediaDto
import com.example.youyou_album.data.api.dto.ServerRelationDto
import com.example.youyou_album.data.api.dto.ServerTagDto
import com.example.youyou_album.data.api.dto.SetupStatusDto
import com.example.youyou_album.data.api.dto.SnapshotPageDto
import com.example.youyou_album.data.api.dto.TagPageDto
import com.example.youyou_album.data.api.dto.UpdateTagRequestDto
import com.example.youyou_album.data.api.dto.UploadResponseDto
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface YouyouApiService {

    // Health & Setup
    @GET("health")
    suspend fun health(): ServerHealthDto

    @GET("admin/setup")
    suspend fun setupStatus(): SetupStatusDto

    // Pairing
    @POST("pairing")
    suspend fun pair(@Body request: PairRequestDto): DeviceCredentialsDto

    // 客户端自注销（断开连接时通知服务端撤销设备）
    @POST("devices/me/revoke")
    suspend fun revokeSelfDevice(): Response<Unit>

    // Server info
    @GET("server")
    suspend fun serverInfo(@Header("Authorization") authorization: String? = null): ServerInfoDto

    // Media
    @GET("media")
    suspend fun listMedia(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 100,
    ): MediaPageDto

    @GET("media/folders")
    suspend fun listMediaFolders(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 100,
    ): MediaFolderPageDto

    @GET("media/{id}")
    suspend fun getMedia(@Path("id") id: String): ServerMediaDto

    /**
     * 设备侧删除：删除当前用户库内该媒体的全部原件并打墓碑。
     * 携带幂等操作 ID；版本冲突返回 `conflict` 与当前版本。
     * Retrofit 禁止 @DELETE 携带 @Body，故用 @HTTP(hasBody = true)。
     */
    @HTTP(method = "DELETE", path = "media/{id}", hasBody = true)
    suspend fun deleteMedia(
        @Path("id") id: String,
        @Body body: MediaDeleteRequestDto,
        @Header("Authorization") authorization: String? = null,
    ): MediaDeleteResponseDto

    /** 删除操作结果只读查询：查询不到不是成功证明，也不触发自动重放。 */
    @GET("media/operations/{operationId}")
    suspend fun getMediaOperation(
        @Path("operationId") operationId: String,
        @Header("Authorization") authorization: String? = null,
    ): MediaOperationDto

    @POST("media/{id}/favorite")
    suspend fun setFavorite(
        @Path("id") id: String,
        @Body body: com.example.youyou_album.data.api.dto.FavoriteUpdateRequestDto,
    ): ServerMediaDto

    @Streaming
    @GET("media/{id}/content")
    suspend fun openMediaContent(
        @Path("id") id: String,
        @Header("Range") range: String? = null,
    ): ResponseBody

    @Streaming
    @GET("media/{id}/thumbnail")
    suspend fun openMediaThumbnail(
        @Path("id") id: String,
        @Query("size") size: Int = 512,
    ): ResponseBody

    // Upload — 原始字节流上传，元数据通过 Header 传递
    @POST("media/upload")
    suspend fun uploadMedia(
        @Header("X-Expected-Size") expectedSize: Long,
        @Header("X-Expected-SHA256") expectedSha256: String,
        @Header("X-File-Name") fileName: String,
        @Header("X-Mime-Type") mimeType: String?,
        @Header("X-Taken-At") takenAt: Long?,
        @Header("X-Sort-At") sortAt: Long? = null,
        @Header("X-Sort-Source") sortSource: String = "unknown",
        @Header("X-Time-Version") timeVersion: Int = 1,
        @Header("X-Original-Name") originalName: String? = null,
        @Body body: RequestBody,
    ): UploadResponseDto

    // Bootstrap sync
    @POST("sync/bootstrap")
    suspend fun startBootstrap(): BootstrapStartDto

    @GET("sync/bootstrap/{snapshotId}")
    suspend fun getBootstrap(@Path("snapshotId") snapshotId: String): BootstrapStatusDto

    @GET("sync/bootstrap/{snapshotId}/{entity}")
    suspend fun listBootstrapEntity(
        @Path("snapshotId") snapshotId: String,
        @Path("entity") entity: String,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 100,
    ): SnapshotPageDto

    // Changes
    @GET("changes")
    suspend fun listChanges(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 200,
    ): ChangesPageDto

    // Jobs
    @GET("jobs/{id}")
    suspend fun getJob(@Path("id") id: String): ServerJobDto

    // Tags
    @GET("tags")
    suspend fun listTags(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 100,
    ): TagPageDto

    @POST("tags")
    suspend fun createTag(
        @Body request: CreateTagRequestDto,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): ServerTagDto

    @PATCH("tags/{id}")
    suspend fun updateTag(
        @Path("id") id: String,
        @Body request: UpdateTagRequestDto,
        @Header("If-Match") ifMatch: String,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): ServerTagDto

    @DELETE("tags/{id}")
    suspend fun deleteTag(
        @Path("id") id: String,
        @Header("If-Match") ifMatch: String,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): ServerTagDto

    @POST("tags/{tagId}/media/{mediaId}")
    suspend fun addTagMedia(
        @Path("tagId") tagId: String,
        @Path("mediaId") mediaId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): ServerRelationDto

    @DELETE("tags/{tagId}/media/{mediaId}")
    suspend fun removeTagMedia(
        @Path("tagId") tagId: String,
        @Path("mediaId") mediaId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): Response<Unit>

    companion object {
        const val API_PREFIX = "/api/v1/"

        /**
         * 客户端版本号随 [BuildConfig.VERSION_NAME] 派生，避免手写常量与
         * `build.gradle.kts` 的 versionName 漂移（服务端按该头做版本门槛判断）。
         */
        val CLIENT_VERSION: String = BuildConfig.VERSION_NAME
    }
}
