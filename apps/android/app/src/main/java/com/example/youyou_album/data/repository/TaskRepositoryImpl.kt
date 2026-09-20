package com.example.youyou_album.data.repository

import com.example.youyou_album.data.db.dao.TaskDao
import com.example.youyou_album.domain.model.AppTask
import com.example.youyou_album.domain.repository.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskRepositoryImpl @Inject constructor(
    private val taskDao: TaskDao,
) : TaskRepository {

    override fun observeAll(): Flow<List<AppTask>> =
        taskDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeActive(): Flow<List<AppTask>> =
        taskDao.observeActive().map { list -> list.map { it.toDomain() } }

    override suspend fun getById(id: String): AppTask? = taskDao.getById(id)?.toDomain()

    override suspend fun upsert(task: AppTask) = taskDao.upsert(task.toEntity())

    override suspend fun updateStatus(id: String, status: String, message: String?, updatedAt: Long) =
        taskDao.updateStatus(id, status, message, updatedAt)

    override suspend fun updateProgress(
        id: String, current: Int, total: Int?, indeterminate: Boolean, phase: String?, updatedAt: Long
    ) = taskDao.updateProgress(id, current, total, indeterminate, phase, updatedAt)

    override suspend fun deleteById(id: String) = taskDao.deleteById(id)
}
