<script setup>
import { computed, ref } from 'vue';
import { ArrowRight, Camera, HardDrive, ScanLine, TriangleAlert, Users, Video } from '@lucide/vue';
import {
  formatBytes,
  formatNumber,
  formatTime,
  isActiveJob,
  jobMessage,
  jobStatusClass,
  jobStatusLabel,
  progressValue,
} from '../utils/formatters';

const props = defineProps({
  storage: { type: Object, default: null },
  diagnostics: { type: Object, default: null },
  jobs: { type: Object, required: true },
  users: { type: Array, default: () => [] },
  refreshedAt: { type: Number, default: null },
});

const emit = defineEmits(['refresh', 'go', 'retry-scan']);
const failureDetailsOpen = ref(false);

const mediaCount = computed(() => {
  if (!props.diagnostics) return null;
  return Number(props.diagnostics.photoCount || 0) + Number(props.diagnostics.videoCount || 0);
});
const connectedUsers = computed(() => props.users.filter((user) => Number(user.deviceCount) > 0).length);
const deviceCount = computed(() => props.users.reduce((count, user) => count + Number(user.deviceCount || 0), 0));
const freePercent = computed(() => {
  if (props.storage?.freeBytes == null || !props.storage?.totalBytes) return null;
  return Math.min(100, Math.max(0, Math.round(props.storage.freeBytes / props.storage.totalBytes * 100)));
});
const latestFullScan = computed(() => {
  const items = props.jobs.items || [];
  const active = items.find((job) => job.kind === 'scan'
    && !job.checkpoint?.scopePath && isActiveJob(job));
  const saved = props.diagnostics?.latestFullScan;
  return active || items.find((job) => job.id === saved?.id) || saved || null;
});
const scanProgress = computed(() => progressValue(latestFullScan.value));
const scanBusy = computed(() => (props.jobs.items || []).some((job) => job.kind === 'scan' && isActiveJob(job)));
const indexFailures = computed(() => Number(props.diagnostics?.indexFailureCount || 0));
const hasStorageProblem = computed(() => !props.storage || !props.storage.writable);
const lowSpace = computed(() => freePercent.value != null && freePercent.value < 10);
const hasAlert = computed(() => hasStorageProblem.value || !props.diagnostics || indexFailures.value > 0 || lowSpace.value);

function shortTime(value) {
  return new Date(value).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
}
</script>

<template>
  <section class="page-section dashboard-home">
    <div class="page-intro dashboard-intro">
      <div>
        <h1>仪表盘</h1>
        <p class="page-lede">了解照片库的规模与待处理事项。</p>
      </div>
      <span v-if="refreshedAt" class="dashboard-freshness">更新于 {{ shortTime(refreshedAt) }}</span>
    </div>

    <div class="dashboard-overview">
      <article class="surface-card dashboard-media">
        <div class="dashboard-media-title">
          <span class="dashboard-accent-line" aria-hidden="true"></span>
          <div>
            <h2>可浏览媒体</h2>
            <p>已成功索引并可在相册中浏览的媒体数量。</p>
          </div>
        </div>
        <strong class="dashboard-media-total">{{ formatNumber(mediaCount) }}</strong>
        <div class="dashboard-media-breakdown">
          <div>
            <span class="dashboard-icon" aria-hidden="true"><Camera :size="22" /></span>
            <span><small>照片</small><strong>{{ formatNumber(diagnostics?.photoCount) }}</strong></span>
          </div>
          <div>
            <span class="dashboard-icon" aria-hidden="true"><Video :size="22" /></span>
            <span><small>视频</small><strong>{{ formatNumber(diagnostics?.videoCount) }}</strong></span>
          </div>
        </div>
      </article>

      <article class="surface-card dashboard-side">
        <div class="dashboard-side-row">
          <span class="dashboard-icon dashboard-icon-large" aria-hidden="true"><Users :size="25" /></span>
          <div>
            <h2>已连接用户</h2>
            <strong>{{ formatNumber(connectedUsers) }} <span>/ {{ formatNumber(users.length) }}</span></strong>
            <p>{{ formatNumber(deviceCount) }} 台设备</p>
          </div>
        </div>
        <div class="dashboard-side-row">
          <span class="dashboard-icon dashboard-icon-large" aria-hidden="true"><HardDrive :size="25" /></span>
          <div class="dashboard-space-copy">
            <h2>剩余空间</h2>
            <strong>{{ formatBytes(storage?.freeBytes) }}</strong>
            <p>{{ storage?.totalBytes == null ? '总容量未知' : `总容量 ${formatBytes(storage.totalBytes)}` }}</p>
            <div v-if="freePercent != null" class="dashboard-space-meter">
              <div class="dashboard-meter-track" role="progressbar" aria-label="存储剩余空间" :aria-valuenow="freePercent" aria-valuemin="0" aria-valuemax="100">
                <span :style="{ width: `${freePercent}%` }"></span>
              </div>
              <small>剩余 {{ freePercent }}%</small>
            </div>
          </div>
        </div>
      </article>
    </div>

    <article class="surface-card dashboard-scan">
      <span class="dashboard-icon dashboard-icon-large" aria-hidden="true"><ScanLine :size="24" /></span>
      <div class="dashboard-scan-heading">
        <h2>最近全库扫描</h2>
        <p>{{ latestFullScan ? formatTime(latestFullScan.finishedAt || latestFullScan.startedAt || latestFullScan.createdAt) : '尚未运行全库扫描' }}</p>
      </div>
      <div v-if="latestFullScan" class="dashboard-scan-result">
        <span class="status-badge" :class="jobStatusClass(latestFullScan.status)">{{ jobStatusLabel(latestFullScan.status) }}</span>
        <p>{{ jobMessage(latestFullScan) }}</p>
        <progress v-if="latestFullScan.status === 'running' && scanProgress != null" :value="scanProgress" max="100" :aria-label="`扫描进度 ${scanProgress}%`"></progress>
      </div>
      <button class="btn btn-primary btn-sm" type="button" @click="emit('go', latestFullScan ? 'logs' : 'storage')">
        {{ latestFullScan ? '查看日志' : '打开媒体库' }}
        <ArrowRight :size="16" aria-hidden="true" />
      </button>
    </article>

    <div v-if="hasAlert" class="dashboard-alerts" aria-label="需要处理的事项">
      <div v-if="!storage" class="dashboard-alert-row">
        <TriangleAlert :size="20" aria-hidden="true" />
        <div><strong>无法读取媒体目录状态</strong><p>请重试；如果仍然失败，检查服务端存储配置。</p></div>
        <button class="text-button" type="button" @click="emit('refresh')">重试读取 <ArrowRight :size="16" aria-hidden="true" /></button>
      </div>
      <div v-else-if="!storage.writable" class="dashboard-alert-row">
        <TriangleAlert :size="20" aria-hidden="true" />
        <div><strong>媒体目录不可写</strong><p>检查媒体目录挂载与权限。</p></div>
        <button class="text-button" type="button" @click="emit('go', 'storage')">打开媒体库 <ArrowRight :size="16" aria-hidden="true" /></button>
      </div>
      <div v-if="storage && !diagnostics" class="dashboard-alert-row">
        <TriangleAlert :size="20" aria-hidden="true" />
        <div><strong>无法读取媒体库指标</strong><p>请重试获取照片和扫描数据。</p></div>
        <button class="text-button" type="button" @click="emit('refresh')">重试读取 <ArrowRight :size="16" aria-hidden="true" /></button>
      </div>
      <div v-if="indexFailures > 0" class="dashboard-alert-row">
        <TriangleAlert :size="20" aria-hidden="true" />
        <div><strong>{{ formatNumber(indexFailures) }} 项索引失败待处理</strong><p>这些文件尚未完成索引。</p></div>
        <button class="text-button" type="button" aria-controls="dashboard-failure-details" :aria-expanded="failureDetailsOpen" @click="failureDetailsOpen = !failureDetailsOpen">
          {{ failureDetailsOpen ? '收起失败项' : '查看失败项' }} <ArrowRight :size="16" aria-hidden="true" />
        </button>
      </div>
      <div v-if="indexFailures > 0 && failureDetailsOpen" id="dashboard-failure-details" class="dashboard-failure-details">
        <p v-if="indexFailures > diagnostics.indexFailures.length">显示最近 {{ diagnostics.indexFailures.length }} 项，共 {{ formatNumber(indexFailures) }} 项。</p>
        <ul>
          <li v-for="failure in diagnostics.indexFailures" :key="failure.path">
            <code>{{ failure.path }}</code>
            <span>{{ failure.reason }}</span>
          </li>
        </ul>
        <button class="btn btn-outline btn-sm" type="button" :disabled="scanBusy" @click="emit('retry-scan')">重试失败项</button>
      </div>
      <div v-if="lowSpace" class="dashboard-alert-row">
        <TriangleAlert :size="20" aria-hidden="true" />
        <div><strong>存储剩余空间不足 10%</strong><p>剩余 {{ formatBytes(storage.freeBytes) }}，上传可能受到影响。</p></div>
        <button class="text-button" type="button" @click="emit('go', 'storage')">打开媒体库 <ArrowRight :size="16" aria-hidden="true" /></button>
      </div>
    </div>
  </section>
</template>
