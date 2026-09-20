<script setup>
// 标准仪表盘：健康状态 + 关键指标 + 下一步引导 + 最近运行记录 + 审计摘要。
import { computed } from 'vue';
import { ArrowRight, CheckCircle2, Database, HardDrive, Images, RefreshCw } from '@lucide/vue';
import {
  auditActionLabel,
  auditResultLabel,
  formatBytes,
  formatNumber,
  formatTime,
  jobKindLabel,
  jobMessage,
  jobStatusClass,
  jobStatusLabel,
  quickCheckLabel,
} from '../utils/formatters';

const props = defineProps({
  status: { type: Object, default: null },
  storage: { type: Object, default: null },
  diagnostics: { type: Object, default: null },
  jobs: { type: Object, required: true },
  auditLog: { type: Array, default: () => [] },
  refreshing: { type: Boolean, default: false },
});

const emit = defineEmits(['refresh', 'go']);

const iconStrokeWidth = 1.8;

const storageHealth = computed(() => {
  if (!props.storage) return 'unknown';
  if (props.storage.writable) return 'healthy';
  if (props.storage.readOnly) return 'readonly';
  return 'offline';
});

const storageHealthLabel = computed(() => ({
  healthy: '媒体目录可用',
  readonly: '媒体目录只读',
  offline: '媒体目录不可用',
  unknown: '正在读取',
}[storageHealth.value]));

const storageHealthClass = computed(() => `health-${storageHealth.value}`);

const storageHealthHint = computed(() => ({
  healthy: '照片库运行正常。',
  readonly: '照片目录只读，请检查运行环境挂载与权限。',
  offline: '暂时无法访问照片目录。',
  unknown: '正在读取存储目录状态。',
}[storageHealth.value]));

const recentJobs = computed(() => (props.jobs.items || []).slice(0, 5));

const nextAction = computed(() => {
  if (storageHealth.value === 'offline' || storageHealth.value === 'readonly') {
    return {
      title: '照片目录不可用',
      description: '请检查运行环境中的媒体目录权限与挂载，然后重新扫描。',
      section: 'storage',
      action: '打开媒体库',
    };
  }
  if (!props.jobs.items?.some((job) => job.kind === 'scan' && job.status === 'succeeded')) {
    return {
      title: '刷新照片目录',
      description: '在媒体库刷新文件夹后即可浏览照片。',
      section: 'storage',
      action: '打开媒体库',
    };
  }
  return {
    title: '照片库运行正常',
    description: '可以在媒体库整理照片，或在用户页生成连接二维码。',
    section: 'storage',
    action: '打开媒体库',
  };
});
</script>

<template>
  <section class="page-section">
    <div class="page-intro">
      <div>
        <h1>仪表盘</h1>
        <p class="page-lede">查看照片库状态与运行健康。</p>
      </div>
      <button class="btn btn-primary" type="button" :disabled="refreshing" @click="emit('refresh')">
        <RefreshCw :size="17" :stroke-width="iconStrokeWidth" aria-hidden="true" />
        更新状态
      </button>
    </div>

    <div class="overview-health" :class="storageHealthClass">
      <span class="health-pulse" aria-hidden="true"></span>
      <div>
        <strong>{{ storageHealthLabel }}</strong>
        <p>{{ storageHealthHint }}</p>
      </div>
      <button
        v-if="storageHealth !== 'healthy'"
        class="btn btn-sm btn-outline"
        type="button"
        @click="emit('go', 'logs')"
      >
        查看日志
      </button>
    </div>

    <div class="metric-grid" aria-label="服务端关键指标">
      <article class="metric-card metric-card-accent">
        <div class="metric-heading">
          <span>照片索引</span>
          <span class="metric-icon" aria-hidden="true">
            <Images :size="18" :stroke-width="iconStrokeWidth" />
          </span>
        </div>
        <strong class="metric-value">{{ formatNumber(diagnostics?.mediaCount) }}</strong>
        <span class="metric-note">已索引</span>
      </article>
      <article class="metric-card">
        <div class="metric-heading">
          <span>可用空间</span>
          <span class="metric-icon" aria-hidden="true">
            <HardDrive :size="18" :stroke-width="iconStrokeWidth" />
          </span>
        </div>
        <strong class="metric-value metric-value-compact">{{ storage?.freeBytes == null ? '—' : formatBytes(storage.freeBytes) }}</strong>
        <span class="metric-note">存储剩余</span>
      </article>
      <article class="metric-card">
        <div class="metric-heading">
          <span>数据库</span>
          <span class="metric-icon" aria-hidden="true">
            <Database :size="18" :stroke-width="iconStrokeWidth" />
          </span>
        </div>
        <strong class="metric-value metric-value-compact">{{ quickCheckLabel(diagnostics?.databaseQuickCheck) }}</strong>
        <span class="metric-note">快速检查 · API {{ diagnostics?.apiVersion || '—' }}</span>
      </article>
    </div>

    <div class="dashboard-columns">
      <article class="surface-card data-card">
        <div class="card-heading">
          <div>
            <h2>下一步</h2>
          </div>
        </div>
        <div class="next-action">
          <h3>{{ nextAction.title }}</h3>
          <p>{{ nextAction.description }}</p>
          <button class="btn btn-primary btn-sm" type="button" @click="emit('go', nextAction.section)">
            {{ nextAction.action }}
            <ArrowRight :size="15" :stroke-width="iconStrokeWidth" aria-hidden="true" />
          </button>
        </div>

        <div class="card-heading card-heading-secondary">
          <div>
            <h2>最近运行记录</h2>
          </div>
          <button class="text-button" type="button" @click="emit('go', 'logs')">
            全部日志
          </button>
        </div>
        <div v-if="recentJobs.length" class="mini-job-list">
          <div v-for="job in recentJobs" :key="job.id" class="mini-job-row">
            <span class="status-badge" :class="jobStatusClass(job.status)">
              {{ jobStatusLabel(job.status) }}
            </span>
            <span class="mini-job-copy">
              <strong>{{ jobKindLabel(job.kind) }}</strong>
              <small>{{ jobMessage(job) }}</small>
            </span>
            <span class="mini-job-time">{{ formatTime(job.updatedAt) }}</span>
          </div>
        </div>
        <p v-else class="mini-empty">还没有运行记录。</p>
      </article>

      <article class="surface-card data-card">
        <div class="card-heading">
          <div>
            <h2>审计日志</h2>
          </div>
          <span class="record-count">{{ auditLog.length }} 条</span>
        </div>
        <div v-if="auditLog.length" class="mini-job-list">
          <div v-for="entry in auditLog.slice(0, 8)" :key="`${entry.createdAt}-${entry.action}`" class="mini-job-row">
            <span class="status-badge" :class="entry.result === 'failure' ? 'status-error' : 'status-success'">
              {{ auditResultLabel(entry.result) }}
            </span>
            <span class="mini-job-copy">
              <strong>{{ auditActionLabel(entry.action) }}</strong>
              <small>{{ entry.actor }} · {{ entry.target }}</small>
            </span>
            <span class="mini-job-time">{{ formatTime(entry.createdAt) }}</span>
          </div>
        </div>
        <div v-else class="empty-state empty-state-compact">
          <span class="empty-symbol" aria-hidden="true">
            <CheckCircle2 :size="20" :stroke-width="iconStrokeWidth" />
          </span>
          <strong>暂无审计记录</strong>
        </div>
      </article>
    </div>
  </section>
</template>
