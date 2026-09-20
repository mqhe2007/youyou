<script setup>
// 回收站页：删除能力状态 + 条目列表（按媒体归组）；支持单项/批量恢复、彻底删除与清空。
import { computed, onMounted, ref } from 'vue';
import { AlertTriangle, ArchiveRestore, RefreshCw, RotateCcw, Trash2 } from '@lucide/vue';
import HelpTooltip from './HelpTooltip.vue';
import { getApiErrorMessage } from '../api';
import { formatBytes, formatNumber, formatTime } from '../utils/formatters';

const props = defineProps({
  api: {
    type: Object,
    required: true,
  },
});

const emit = defineEmits(['notify', 'session-expired']);

const iconStrokeWidth = 1.8;
const loading = ref(true);
const busy = ref('');
const capability = ref(null);
const entries = ref([]);
const selectedEntryIds = ref([]);
const confirmState = ref(null); // { kind: 'purge' | 'empty', entryIds: [] }

const enabled = computed(() => capability.value?.enabled === true);
const retentionDays = computed(() => capability.value?.retentionDays || 30);

const groups = computed(() => {
  const map = new Map();
  for (const entry of entries.value) {
    let group = map.get(entry.mediaId);
    if (!group) {
      group = {
        mediaId: entry.mediaId,
        name: entry.name,
        isVideo: entry.isVideo === 1 || entry.isVideo === true,
        entries: [],
      };
      map.set(entry.mediaId, group);
    }
    group.entries.push(entry);
  }
  return [...map.values()];
});

const selectedCount = computed(() => selectedEntryIds.value.length);

const selectedMediaIds = computed(() => {
  const ids = new Set();
  for (const entry of entries.value) {
    if (selectedEntryIds.value.includes(entry.id)) ids.add(entry.mediaId);
  }
  return [...ids];
});

const confirmOpen = computed(() => confirmState.value !== null);

const confirmTargetCount = computed(() => {
  if (confirmState.value?.kind === 'empty') return entries.value.length;
  return confirmState.value?.entryIds?.length || 0;
});

function handleError(error) {
  if (error?.code === 'unauthorized') {
    emit('session-expired');
    return;
  }
  emit('notify', getApiErrorMessage(error), 'error');
}

async function loadTrash({ silent = false } = {}) {
  if (!silent) loading.value = true;
  try {
    const result = await props.api.request('/api/v1/admin/trash');
    capability.value = result?.capability || null;
    entries.value = result?.entries || [];
    pruneSelection();
  } catch (error) {
    handleError(error);
  } finally {
    loading.value = false;
  }
}

function pruneSelection() {
  const alive = new Set(entries.value.map((entry) => entry.id));
  selectedEntryIds.value = selectedEntryIds.value.filter((id) => alive.has(id));
}

function toggleEntry(entryId) {
  if (selectedEntryIds.value.includes(entryId)) {
    selectedEntryIds.value = selectedEntryIds.value.filter((id) => id !== entryId);
  } else {
    selectedEntryIds.value = [...selectedEntryIds.value, entryId];
  }
}

function toggleMedia(group) {
  const ids = group.entries.map((entry) => entry.id);
  const allSelected = ids.every((id) => selectedEntryIds.value.includes(id));
  if (allSelected) {
    selectedEntryIds.value = selectedEntryIds.value.filter((id) => !ids.includes(id));
  } else {
    const merged = new Set([...selectedEntryIds.value, ...ids]);
    selectedEntryIds.value = [...merged];
  }
}

function mediaSelected(group) {
  return group.entries.every((entry) => selectedEntryIds.value.includes(entry.id));
}

function clearSelection() {
  selectedEntryIds.value = [];
}

function deletedByLabel(value) {
  if (!value) return '—';
  if (value.startsWith('device:')) return `设备 ${value.slice(7, 15)}`;
  if (value === 'admin_deleted' || value === 'admin') return '管理端';
  return value;
}

function remainingLabel(expiresAt) {
  if (!expiresAt) return '—';
  const remaining = Number(expiresAt) - Date.now();
  if (remaining <= 0) return '等待清理';
  const days = Math.ceil(remaining / 86400000);
  return `${days} 天后`;
}

function expiryTitle(expiresAt) {
  return expiresAt ? `到期时间：${formatTime(expiresAt)}` : '';
}

async function restoreMediaIds(mediaIds) {
  if (!mediaIds.length || busy.value) return;
  busy.value = 'restore';
  try {
    const result = await props.api.request('/api/v1/admin/trash/restore', {
      method: 'POST',
      body: { mediaIds },
    });
    const restored = result?.restored?.length || 0;
    const failures = result?.failures || [];
    if (failures.length) {
      emit('notify', `已恢复 ${restored} 项，${failures.length} 项恢复失败：${failures[0].message}`, 'error');
    } else if (result?.renamed?.length) {
      emit('notify', `已恢复 ${restored} 项，其中 ${result.renamed.length} 项因原路径被占用自动改名。`, 'success');
    } else {
      emit('notify', `已恢复 ${restored} 项，刷新后即可在媒体库中看到。`, 'success');
    }
    clearSelection();
    await loadTrash({ silent: true });
  } catch (error) {
    handleError(error);
  } finally {
    busy.value = '';
  }
}

async function restoreSelected() {
  await restoreMediaIds(selectedMediaIds.value);
}

async function restoreGroup(group) {
  await restoreMediaIds([group.mediaId]);
}

function requestPurge(entryIds) {
  if (!entryIds.length) return;
  confirmState.value = { kind: 'purge', entryIds: [...entryIds] };
}

function requestEmpty() {
  if (!entries.value.length) return;
  confirmState.value = { kind: 'empty', entryIds: [] };
}

function closeConfirm() {
  confirmState.value = null;
}

async function confirmDestructive() {
  const state = confirmState.value;
  if (!state) return;
  busy.value = state.kind;
  try {
    if (state.kind === 'empty') {
      const result = await props.api.request('/api/v1/admin/trash', { method: 'DELETE' });
      emit('notify', `回收站已清空，彻底删除 ${formatNumber(result?.purged || 0)} 项。`, 'success');
    } else {
      const result = await props.api.request('/api/v1/admin/trash/purge', {
        method: 'POST',
        body: { entryIds: state.entryIds },
      });
      emit('notify', `已彻底删除 ${formatNumber(result?.purged || 0)} 项。`, 'success');
    }
    confirmState.value = null;
    clearSelection();
    await loadTrash({ silent: true });
  } catch (error) {
    handleError(error);
  } finally {
    busy.value = '';
  }
}

onMounted(() => {
  void loadTrash();
});
</script>

<template>
  <section class="page-section trash-page">
    <div class="page-intro page-intro-compact">
      <div>
        <h1>回收站</h1>
        <p class="page-lede">
          删除的原件暂存 {{ retentionDays }} 天，可恢复或彻底删除。
          <HelpTooltip
            text="恢复后重新出现在原用户的媒体库。彻底删除与清空不可恢复。"
            label="查看回收站说明"
          />
        </p>
      </div>
      <div class="trash-actions">
        <button
          class="btn btn-outline btn-sm"
          type="button"
          :disabled="Boolean(busy)"
          @click="loadTrash()"
        >
          <RefreshCw :size="16" :stroke-width="iconStrokeWidth" aria-hidden="true" />
          刷新
        </button>
        <button
          class="btn btn-outline btn-sm"
          type="button"
          :disabled="!enabled || !selectedCount || Boolean(busy)"
          @click="restoreSelected"
        >
          <RotateCcw :size="16" :stroke-width="iconStrokeWidth" aria-hidden="true" />
          恢复选中
        </button>
        <button
          class="btn btn-outline btn-sm media-danger"
          type="button"
          :disabled="!enabled || !selectedCount || Boolean(busy)"
          @click="requestPurge(selectedEntryIds)"
        >
          <Trash2 :size="16" :stroke-width="iconStrokeWidth" aria-hidden="true" />
          彻底删除选中
        </button>
        <button
          class="btn btn-error btn-sm"
          type="button"
          :disabled="!enabled || !entries.length || Boolean(busy)"
          @click="requestEmpty"
        >
          清空回收站
        </button>
      </div>
    </div>

    <div v-if="capability && !enabled" class="alert alert-warning trash-capability-alert" role="alert">
      <AlertTriangle :size="18" :stroke-width="iconStrokeWidth" aria-hidden="true" />
      <div>
        <strong>删除能力未启用</strong>
        <p>{{ capability.blockedReason || '回收站目录不可用或配置不正确。' }}</p>
        <p class="trash-capability-path">当前配置：{{ capability.directory }}</p>
      </div>
    </div>

    <article class="surface-card data-card">
      <div class="card-heading">
        <h2>已删除媒体</h2>
        <span class="card-heading-secondary">
          {{ groups.length }} 个媒体 · {{ entries.length }} 个位置
          <template v-if="selectedCount"> · 已选 {{ selectedCount }} 个位置</template>
        </span>
      </div>

      <div v-if="loading" class="empty-state empty-state-compact">
        <span class="loading loading-spinner"></span>
        <p>正在读取回收站…</p>
      </div>

      <div v-else-if="!entries.length" class="empty-state">
        <span class="empty-symbol" aria-hidden="true">
          <ArchiveRestore :size="26" :stroke-width="iconStrokeWidth" />
        </span>
        <strong>回收站是空的</strong>
        <p>从媒体库删除的媒体会先来到这里，{{ retentionDays }} 天内可以恢复。</p>
      </div>

      <div v-else class="table-wrap">
        <table class="table admin-table trash-table">
          <thead>
            <tr>
              <th scope="col" class="trash-check-col"></th>
              <th scope="col">位置</th>
              <th scope="col" class="table-num">大小</th>
              <th scope="col">删除者</th>
              <th scope="col" class="table-time">删除时间</th>
              <th scope="col" class="table-time">到期</th>
              <th scope="col" class="table-actions-col">操作</th>
            </tr>
          </thead>
          <tbody v-for="group in groups" :key="group.mediaId">
            <tr class="trash-group-row">
              <td class="trash-check-col">
                <input
                  class="checkbox checkbox-sm"
                  type="checkbox"
                  :checked="mediaSelected(group)"
                  :disabled="!enabled || Boolean(busy)"
                  :aria-label="`选择 ${group.name}`"
                  @change="toggleMedia(group)"
                >
              </td>
              <td colspan="6">
                <div class="trash-group-cell">
                  <div class="trash-group-name">
                    <strong>{{ group.name }}</strong>
                    <span v-if="group.isVideo" class="status-badge status-muted">视频</span>
                    <span v-if="group.entries.length > 1" class="status-badge status-muted">
                      {{ group.entries.length }} 个位置
                    </span>
                  </div>
                  <span class="library-path">{{ group.mediaId }}</span>
                </div>
              </td>
            </tr>
            <tr v-for="entry in group.entries" :key="entry.id">
              <td class="trash-check-col">
                <input
                  class="checkbox checkbox-sm"
                  type="checkbox"
                  :checked="selectedEntryIds.includes(entry.id)"
                  :disabled="!enabled || Boolean(busy)"
                  :aria-label="`选择 ${entry.originalPath}`"
                  @change="toggleEntry(entry.id)"
                >
              </td>
              <td class="trash-path-cell">
                <span class="library-path">{{ entry.originalPath }}</span>
              </td>
              <td class="table-num">{{ formatBytes(entry.size) }}</td>
              <td>{{ deletedByLabel(entry.deletedBy) }}</td>
              <td class="table-time">{{ formatTime(entry.deletedAt) }}</td>
              <td class="table-time" :title="expiryTitle(entry.expiresAt)">
                {{ remainingLabel(entry.expiresAt) }}
              </td>
              <td>
                <div class="table-actions">
                  <button
                    class="btn btn-outline btn-xs"
                    type="button"
                    :disabled="!enabled || Boolean(busy)"
                    @click="restoreGroup(group)"
                  >
                    恢复
                  </button>
                  <button
                    class="btn btn-error btn-xs"
                    type="button"
                    :disabled="!enabled || Boolean(busy)"
                    @click="requestPurge([entry.id])"
                  >
                    彻底删除
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </article>

    <Teleport to="body">
      <div
        v-if="confirmOpen"
        class="modal modal-open"
        role="presentation"
        @click.self="closeConfirm"
      >
        <div class="modal-box admin-modal" role="dialog" aria-modal="true" aria-labelledby="trash-confirm-title">
          <h3 id="trash-confirm-title" class="admin-modal-title">
            {{ confirmState.kind === 'empty' ? '清空回收站' : '彻底删除' }}
          </h3>
          <p class="admin-modal-lede">
            <template v-if="confirmState.kind === 'empty'">
              将彻底删除回收站内全部 {{ confirmTargetCount }} 个位置的文件，此操作不可恢复。
            </template>
            <template v-else>
              将彻底删除选中的 {{ confirmTargetCount }} 个位置的文件，此操作不可恢复。
            </template>
          </p>
          <div class="admin-modal-actions">
            <button class="btn btn-ghost" type="button" :disabled="Boolean(busy)" @click="closeConfirm">
              取消
            </button>
            <button
              class="btn btn-error"
              type="button"
              :disabled="Boolean(busy)"
              @click="confirmDestructive"
            >
              <span v-if="busy" class="loading loading-spinner loading-sm"></span>
              确认{{ confirmState.kind === 'empty' ? '清空' : '删除' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </section>
</template>
