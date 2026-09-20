<script setup>
// 只读运行日志：进度、范围、结果与错误。
import { ListTodo } from '@lucide/vue';
import {
  isActiveJob,
  jobErrorMessage,
  jobKindLabel,
  jobMessage,
  jobStatusClass,
  jobStatusLabel,
  formatNumber,
  formatTime,
  progressLabel,
  progressValue,
} from '../utils/formatters';

defineProps({
  jobs: { type: Object, required: true },
  currentJob: { type: Object, default: null },
});

const iconStrokeWidth = 1.8;
</script>

<template>
  <section class="page-section">
    <div class="page-intro page-intro-compact">
      <div>
        <h1>日志</h1>
        <p class="page-lede">查看后台处理的进度、结果与错误。</p>
      </div>
    </div>

    <article v-if="currentJob" class="surface-card current-task-card">
      <div class="task-heading">
        <div class="task-title-wrap">
          <span class="task-icon" aria-hidden="true">
            <ListTodo :size="21" :stroke-width="iconStrokeWidth" />
          </span>
          <div>
            <h2>{{ jobKindLabel(currentJob.kind) }}</h2>
            <small v-if="currentJob.kind === 'scan'">目录：{{ currentJob.checkpoint?.scopePath || '全部' }}</small>
          </div>
        </div>
        <span class="status-badge" :class="jobStatusClass(currentJob.status)">
          {{ jobStatusLabel(currentJob.status) }}
        </span>
      </div>
      <div class="task-progress-wrap">
        <div class="progress-label">
          <span>{{ jobMessage(currentJob) }}</span>
          <strong>{{ progressValue(currentJob) == null ? '—' : `${progressValue(currentJob)}%` }}</strong>
        </div>
        <progress
          v-if="progressValue(currentJob) != null"
          class="progress progress-primary"
          :value="currentJob.current"
          :max="currentJob.total"
        ></progress>
        <div v-else class="progress-indeterminate" :class="{ active: isActiveJob(currentJob) }">
          <span></span>
        </div>
        <div class="progress-meta">
          <span>{{ formatNumber(currentJob.current) }} 项已处理</span>
          <span v-if="currentJob.total != null">共 {{ formatNumber(currentJob.total) }} 项</span>
          <span v-else>总量正在统计</span>
        </div>
      </div>
      <div v-if="currentJob.lastError" class="task-error">{{ jobErrorMessage(currentJob.lastError) }}</div>
      <div class="task-actions">
        <span class="task-updated">最近更新：{{ formatTime(currentJob.updatedAt) }}</span>
      </div>
    </article>

    <article class="surface-card data-card">
      <div class="card-heading">
        <div>
          <h2>运行记录</h2>
        </div>
        <span class="record-count">{{ jobs.items?.length || 0 }} 条</span>
      </div>
      <div v-if="jobs.items?.length" class="table-wrap">
        <table class="table admin-table logs-table">
          <thead>
            <tr>
              <th scope="col">操作</th>
              <th scope="col">状态</th>
              <th scope="col">进度</th>
              <th scope="col">更新时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="job in jobs.items" :key="job.id">
              <td>
                <strong>{{ jobKindLabel(job.kind) }}</strong>
                <small v-if="job.kind === 'scan'" class="table-secondary">目录：{{ job.checkpoint?.scopePath || '全部' }}</small>
                <small class="table-secondary">{{ jobMessage(job) }}</small>
                <small v-if="job.lastError" class="task-error">{{ jobErrorMessage(job.lastError) }}</small>
              </td>
              <td><span class="status-badge" :class="jobStatusClass(job.status)">{{ jobStatusLabel(job.status) }}</span></td>
              <td>{{ progressLabel(job) }}</td>
              <td class="table-time">{{ formatTime(job.updatedAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="empty-state">
        <span class="empty-symbol" aria-hidden="true">
          <ListTodo :size="22" :stroke-width="iconStrokeWidth" />
        </span>
        <strong>还没有运行记录</strong>
        <p>在媒体库刷新文件夹后，这里会显示处理进度与结果。</p>
      </div>
    </article>
  </section>
</template>
