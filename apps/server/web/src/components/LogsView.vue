<script setup>
// 只读运行日志：正在处理、等待队列与历史运行记录。
import { computed, onUnmounted, ref, watch } from 'vue';
import { ListTodo } from '@lucide/vue';
import {
  formatDuration,
  formatNumber,
  formatTime,
  isTerminalJob,
  jobElapsedMs,
  jobErrorMessage,
  jobKindLabel,
  jobMessage,
  jobOutcomeLabel,
  jobRunDurationMs,
  jobScanReport,
  jobScanSkipSummary,
  jobScopeLabel,
  jobShowsError,
  jobStatusClass,
  jobStatusLabel,
  progressValue,
  runningJobOf,
  waitingJobs,
} from '../utils/formatters';

const props = defineProps({
  jobs: { type: Object, required: true },
});

const emit = defineEmits(['cancel-job', 'retry-scan']);

const iconStrokeWidth = 1.8;
const cancelBusyId = ref('');
const now = ref(Date.now());
let clockTimer = null;

const items = computed(() => props.jobs.items || []);
const runningJob = computed(() => runningJobOf(items.value));
const waiting = computed(() => waitingJobs(items.value));
const terminal = computed(() => items.value.filter((job) => isTerminalJob(job)));
const focusJob = computed(() => runningJob.value || waiting.value[0] || null);

// 只在真的有任务在跑时开秒表，任务结束就停。
watch(
  runningJob,
  (job) => {
    if (job && clockTimer == null) {
      now.value = Date.now();
      clockTimer = window.setInterval(() => {
        now.value = Date.now();
      }, 1000);
    } else if (!job && clockTimer != null) {
      window.clearInterval(clockTimer);
      clockTimer = null;
    }
  },
  { immediate: true },
);

watch(items, (list) => {
  if (!cancelBusyId.value) return;
  const job = list.find((item) => item.id === cancelBusyId.value);
  if (!job || isTerminalJob(job)) cancelBusyId.value = '';
});

onUnmounted(() => {
  if (clockTimer != null) window.clearInterval(clockTimer);
});

const runningElapsedMs = computed(() => jobElapsedMs(runningJob.value, now.value));

const runningRate = computed(() => {
  const job = runningJob.value;
  const elapsed = runningElapsedMs.value;
  if (!job || elapsed == null || elapsed < 5000 || Number(job.current) <= 0) return null;
  return Math.round(Number(job.current) / (elapsed / 60000));
});

function scanReport(job) {
  return jobScanReport(job);
}

function scanSkipSummary(job) {
  return jobScanSkipSummary(scanReport(job));
}

function requestRetry(job) {
  emit('retry-scan', job);
}

function requestCancel(job) {
  if (cancelBusyId.value) return;
  cancelBusyId.value = job.id;
  emit('cancel-job', job.id);
}
</script>

<template>
  <section class="page-section">
    <div class="page-intro page-intro-compact">
      <div>
        <h1>日志</h1>
        <p class="page-lede">后台由单个 worker 串行处理任务：先看在处理的，再看排队等待的，最后是历史记录。</p>
      </div>
    </div>

    <article v-if="focusJob" class="surface-card current-task-card">
      <div class="task-heading">
        <div class="task-title-wrap">
          <span class="task-icon" aria-hidden="true">
            <ListTodo :size="21" :stroke-width="iconStrokeWidth" />
          </span>
          <div>
            <h2>{{ jobKindLabel(focusJob.kind) }}</h2>
            <small v-if="focusJob.kind === 'scan'">目录：{{ jobScopeLabel(focusJob) }}</small>
            <small v-else-if="runningJob">服务端后台任务</small>
            <small v-else>队首任务，worker 空闲后即将开始</small>
          </div>
        </div>
        <span class="status-badge" :class="jobStatusClass(focusJob.status)">
          {{ jobStatusLabel(focusJob.status) }}
        </span>
      </div>

      <div v-if="runningJob" class="task-progress-wrap">
        <div class="progress-label">
          <span>{{ jobMessage(runningJob) }}</span>
          <strong>{{ progressValue(runningJob) == null ? '—' : `${progressValue(runningJob)}%` }}</strong>
        </div>
        <progress
          v-if="progressValue(runningJob) != null"
          class="progress progress-primary"
          :value="runningJob.current"
          :max="runningJob.total"
        ></progress>
        <div v-else class="progress-indeterminate active">
          <span></span>
        </div>
        <div class="progress-meta">
          <span>{{ formatNumber(runningJob.current) }} 项已处理</span>
          <span v-if="runningJob.total != null">共 {{ formatNumber(runningJob.total) }} 项</span>
          <span v-else>总量正在统计</span>
          <span v-if="runningElapsedMs != null">已运行 {{ formatDuration(runningElapsedMs) }}</span>
          <span v-if="runningRate != null">约 {{ formatNumber(runningRate) }} 项/分钟</span>
        </div>
      </div>
      <div v-else class="task-progress-wrap">
        <div class="progress-meta">
          <span>{{ formatTime(focusJob.createdAt) }} 入队，等待 worker 开始</span>
          <span v-if="waiting.length > 1">队列中还有 {{ waiting.length - 1 }} 个任务</span>
        </div>
      </div>

      <div v-if="jobShowsError(focusJob)" class="task-error">{{ jobErrorMessage(focusJob.lastError) }}</div>
      <div class="task-actions">
        <span class="task-updated">最近更新：{{ formatTime(focusJob.updatedAt) }}</span>
        <button
          class="btn btn-outline btn-xs"
          type="button"
          :disabled="cancelBusyId === focusJob.id"
          @click="requestCancel(focusJob)"
        >
          {{ cancelBusyId === focusJob.id ? '取消中…' : '取消任务' }}
        </button>
      </div>
    </article>

    <article v-if="waiting.length" class="surface-card data-card">
      <div class="card-heading">
        <div>
          <h2>等待队列</h2>
        </div>
        <span class="record-count">{{ waiting.length }} 项</span>
      </div>
      <div class="table-wrap">
        <table class="table admin-table logs-table">
          <thead>
            <tr>
              <th scope="col">顺序</th>
              <th scope="col">任务</th>
              <th scope="col">状态</th>
              <th scope="col">入队时间</th>
              <th scope="col" class="table-actions-col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(job, index) in waiting" :key="job.id">
              <td class="table-num">#{{ index + 1 }}</td>
              <td>
                <strong>{{ jobKindLabel(job.kind) }}</strong>
                <small v-if="job.kind === 'scan'" class="table-secondary">目录：{{ jobScopeLabel(job) }}</small>
                <small class="table-secondary">{{ jobMessage(job) }}</small>
              </td>
              <td>
                <span class="status-badge" :class="jobStatusClass(job.status)">{{ jobStatusLabel(job.status) }}</span>
              </td>
              <td class="table-time">{{ formatTime(job.createdAt) }}</td>
              <td class="table-actions-col">
                <div class="table-actions">
                  <button
                    class="btn btn-outline btn-xs"
                    type="button"
                    :disabled="cancelBusyId === job.id"
                    @click="requestCancel(job)"
                  >
                    取消
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </article>

    <article class="surface-card data-card">
      <div class="card-heading">
        <div>
          <h2>运行记录</h2>
        </div>
        <span class="record-count">{{ terminal.length }} 条</span>
      </div>
      <div v-if="terminal.length" class="table-wrap">
        <table class="table admin-table logs-table">
          <thead>
            <tr>
              <th scope="col">操作</th>
              <th scope="col">状态</th>
              <th scope="col">结果</th>
              <th scope="col">耗时</th>
              <th scope="col">结束时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="job in terminal" :key="job.id">
              <td>
                <strong>{{ jobKindLabel(job.kind) }}</strong>
                <small v-if="job.kind === 'scan'" class="table-secondary">目录：{{ jobScopeLabel(job) }}</small>
              </td>
              <td>
                <span class="status-badge" :class="jobStatusClass(job.status)">{{ jobStatusLabel(job.status) }}</span>
              </td>
              <td>
                <small class="table-secondary">{{ jobOutcomeLabel(job) }}</small>
                <template v-if="scanReport(job)">
                  <small class="table-secondary">{{ scanSkipSummary(job) }}</small>
                  <details v-if="scanReport(job).failures.length" class="scan-failures">
                    <summary>失败明细 {{ scanReport(job).failures.length }} 项</summary>
                    <ul>
                      <li v-for="failure in scanReport(job).failures" :key="failure.path">
                        <code>{{ failure.path }}</code> — {{ failure.reason }}
                      </li>
                    </ul>
                  </details>
                  <button
                    v-if="scanReport(job).skippedFailed > 0 || scanReport(job).failures.length > 0"
                    class="btn btn-outline btn-xs scan-retry"
                    type="button"
                    @click="requestRetry(job)"
                  >
                    重试失败项
                  </button>
                </template>
                <small v-if="jobShowsError(job)" class="table-error">{{ jobErrorMessage(job.lastError) }}</small>
              </td>
              <td class="table-num">{{ formatDuration(jobRunDurationMs(job)) }}</td>
              <td class="table-time">{{ formatTime(job.finishedAt ?? job.updatedAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="empty-state">
        <span class="empty-symbol" aria-hidden="true">
          <ListTodo :size="22" :stroke-width="iconStrokeWidth" />
        </span>
        <strong>还没有运行记录</strong>
        <p>在媒体库刷新文件夹后，这里会显示处理结果与耗时。</p>
      </div>
    </article>
  </section>
</template>
