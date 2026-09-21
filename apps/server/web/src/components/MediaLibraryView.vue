<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import {
  Check,
  CheckSquare,
  ChevronRight,
  Folder,
  FolderPlus,
  Images,
  Move,
  RefreshCw,
  Square,
  Trash2,
  Upload,
  X,
} from '@lucide/vue';
import { getApiErrorMessage } from '../api';
import MediaFolderTree from './MediaFolderTree.vue';

const props = defineProps({
  scanBusy: { type: Boolean, default: false },
  scanReport: { type: Object, default: null },
  refreshVersion: { type: Number, default: 0 },
  api: {
    type: Object,
    required: true,
  },
});

const emit = defineEmits(['notify', 'session-expired', 'refresh-folder', 'retry-scan']);

const iconStrokeWidth = 1.8;
const loading = ref(true);
const busy = ref('');
const currentPath = ref('');
const folders = ref([]);
const media = ref([]);
const selectMode = ref(false);
const selectedKeys = ref([]);
const mkdirOpen = ref(false);
const mkdirName = ref('');
const moveOpen = ref(false);
const moveDestPath = ref('');
const treeNodes = ref([]);
const treeLoading = ref(false);
const lightbox = ref(null);
const uploadInput = ref(null);
const contextMenu = ref(null);
const moveTargets = ref([]);
const infoOpen = ref(false);
const infoLoading = ref(false);
const infoError = ref('');
const infoData = ref(null);

const breadcrumbs = computed(() => {
  if (!currentPath.value) return [];
  const parts = currentPath.value.split('/').filter(Boolean);
  return parts.map((name, index) => ({
    name,
    path: parts.slice(0, index + 1).join('/'),
  }));
});

const emptyLibrary = computed(
  () => !loading.value && folders.value.length === 0 && media.value.length === 0,
);

const selectedCount = computed(() => selectedKeys.value.length);

const selectedItems = computed(() => {
  const keySet = new Set(selectedKeys.value);
  const items = [];
  for (const folder of folders.value) {
    if (keySet.has(itemKey('folder', folder))) {
      items.push({ kind: 'folder', item: folder });
    }
  }
  for (const file of media.value) {
    if (keySet.has(itemKey('media', file))) {
      items.push({ kind: 'media', item: file });
    }
  }
  return items;
});

watch(() => props.refreshVersion, () => { void loadListing(); });

function refreshFolder(path) {
  if (props.scanBusy || busy.value) return;
  closeContextMenu();
  emit('refresh-folder', path);
}

function retryFailedItems() {
  if (!props.scanReport) return;
  emit('retry-scan', props.scanReport.scopePath || '');
}

watch(currentPath, () => {
  clearSelection();
  closeContextMenu();
  void loadListing();
});

onMounted(() => {
  void loadListing();
  window.addEventListener('keydown', onGlobalKeydown);
  window.addEventListener('click', onGlobalClick);
});

onUnmounted(() => {
  window.removeEventListener('keydown', onGlobalKeydown);
  window.removeEventListener('click', onGlobalClick);
});

function itemKey(kind, item) {
  return kind === 'folder' ? `folder:${item.path}` : `media:${item.id}`;
}

function handleError(error) {
  if (error?.code === 'unauthorized') {
    emit('session-expired');
    return;
  }
  emit('notify', getApiErrorMessage(error), 'error');
}

async function loadListing() {
  loading.value = true;
  try {
    const query = currentPath.value
      ? `?path=${encodeURIComponent(currentPath.value)}`
      : '';
    const result = await props.api.request(`/api/v1/admin/media-library${query}`);
    folders.value = result?.folders || [];
    media.value = result?.media || [];
  } catch (error) {
    handleError(error);
  } finally {
    loading.value = false;
  }
}

function openPath(path) {
  currentPath.value = path || '';
}

function clearSelection() {
  selectedKeys.value = [];
}

function enterSelectMode() {
  selectMode.value = true;
  clearSelection();
  closeContextMenu();
}

function exitSelectMode() {
  selectMode.value = false;
  clearSelection();
}

function toggleSelected(kind, item) {
  const key = itemKey(kind, item);
  if (selectedKeys.value.includes(key)) {
    selectedKeys.value = selectedKeys.value.filter((value) => value !== key);
  } else {
    selectedKeys.value = [...selectedKeys.value, key];
  }
}

function isSelected(kind, item) {
  return selectedKeys.value.includes(itemKey(kind, item));
}

function onTileClick(kind, item) {
  closeContextMenu();
  if (selectMode.value) {
    toggleSelected(kind, item);
    return;
  }
  if (kind === 'folder') {
    openPath(item.path);
    return;
  }
  openLightbox(item);
}

function onTileContextMenu(event, kind, item) {
  event.preventDefault();
  if (selectMode.value) return;
  contextMenu.value = {
    kind,
    item,
    x: event.clientX,
    y: event.clientY,
  };
}

function closeContextMenu() {
  contextMenu.value = null;
}

function onGlobalClick() {
  closeContextMenu();
}

function onGlobalKeydown(event) {
  if (event.key === 'Escape') {
    closeContextMenu();
    closeLightbox();
    if (mkdirOpen.value) mkdirOpen.value = false;
    if (moveOpen.value) moveOpen.value = false;
    if (infoOpen.value) closeInfo();
    if (selectMode.value) exitSelectMode();
  }
}

function thumbnailUrl(mediaItem) {
  return `/api/v1/admin/media-library/media/${encodeURIComponent(mediaItem.id)}/thumbnail?size=256`;
}

const failedFolderThumbs = ref({});

function folderThumbnailUrl(folder) {
  return `/api/v1/admin/media-library/media/${encodeURIComponent(folder.thumbnailMediaId)}/thumbnail?size=256`;
}

function hasFolderThumbnail(folder) {
  return Boolean(folder.thumbnailMediaId) && !failedFolderThumbs.value[folder.thumbnailMediaId];
}

function onFolderThumbError(folder) {
  failedFolderThumbs.value = {
    ...failedFolderThumbs.value,
    [folder.thumbnailMediaId]: true,
  };
}

function pad2(value) {
  return String(value).padStart(2, '0');
}

function formatFileSize(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

function formatDateTime(ms) {
  const date = new Date(ms);
  const day = `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`;
  const time = `${pad2(date.getHours())}:${pad2(date.getMinutes())}:${pad2(date.getSeconds())}`;
  return `${day} ${time}`;
}

function formatDuration(ms) {
  const total = Math.max(0, Math.floor(ms / 1000));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  return hours > 0
    ? `${hours}:${pad2(minutes)}:${pad2(seconds)}`
    : `${minutes}:${pad2(seconds)}`;
}

async function openInfo(mediaItem) {
  closeContextMenu();
  infoOpen.value = true;
  infoLoading.value = true;
  infoError.value = '';
  infoData.value = null;
  try {
    infoData.value = await props.api.request(
      `/api/v1/admin/media-library/media/${encodeURIComponent(mediaItem.id)}/info`,
    );
  } catch (error) {
    infoError.value = getApiErrorMessage(error);
  } finally {
    infoLoading.value = false;
  }
}

function closeInfo() {
  infoOpen.value = false;
  infoData.value = null;
  infoError.value = '';
}

const infoRows = computed(() => {
  const data = infoData.value;
  if (!data) return [];
  const rows = [['文件名', data.name]];
  if (data.takenAt != null) {
    rows.push(['拍摄时间', formatDateTime(data.takenAt)]);
  } else if (data.sortAt != null) {
    rows.push([
      data.sortSource === 'filename' ? '文件名时间' : '媒体时间（回退）',
      formatDateTime(data.sortAt),
    ]);
  } else {
    rows.push(['媒体时间', '时间未知']);
  }
  if (data.modifiedAt != null) rows.push(['修改时间', formatDateTime(data.modifiedAt)]);
  if (data.width != null && data.height != null) {
    rows.push(['尺寸', `${data.width} × ${data.height}`]);
  }
  rows.push(['文件大小', formatFileSize(data.size)]);
  if (data.mimeType) rows.push(['类型', data.mimeType]);
  if (data.isVideo && data.durationMs != null) {
    rows.push(['时长', formatDuration(data.durationMs)]);
  }
  const exif = data.exif;
  if (exif) {
    if (exif.cameraModel) rows.push(['相机型号', exif.cameraModel]);
    if (exif.iso) rows.push(['ISO', exif.iso]);
    if (exif.aperture) rows.push(['光圈', exif.aperture]);
    if (exif.focalLength) rows.push(['焦距', exif.focalLength]);
    if (exif.exposureTime) rows.push(['曝光时间', exif.exposureTime]);
  }
  rows.push(['路径', data.path]);
  if (data.library) rows.push(['媒体库', data.library]);
  if (data.contentHash) rows.push(['内容哈希', `${data.contentHash.slice(0, 16)}...`]);
  return rows;
});

function contentUrl(mediaItem) {
  return `/api/v1/admin/media-library/media/${encodeURIComponent(mediaItem.id)}/content`;
}

function openLightbox(mediaItem) {
  closeContextMenu();
  lightbox.value = mediaItem;
}

function closeLightbox() {
  lightbox.value = null;
}

async function createFolder() {
  const name = mkdirName.value.trim();
  if (!name || name.includes('/') || name.includes('\\')) {
    emit('notify', '请输入不含路径分隔符的文件夹名称。', 'error');
    return;
  }
  busy.value = 'mkdir';
  try {
    const path = currentPath.value ? `${currentPath.value}/${name}` : name;
    await props.api.request('/api/v1/admin/media-library/mkdir', {
      method: 'POST',
      body: { path },
    });
    mkdirOpen.value = false;
    mkdirName.value = '';
    emit('notify', '文件夹已创建。', 'success');
    await loadListing();
  } catch (error) {
    handleError(error);
  } finally {
    busy.value = '';
  }
}

function targetsForAction(preferred) {
  if (preferred) return [preferred];
  return selectedItems.value;
}

async function deleteTargets(preferred = null) {
  const targets = targetsForAction(preferred);
  if (!targets.length) return;
  const label = targets.length === 1
    ? (targets[0].kind === 'folder'
      ? `文件夹「${targets[0].item.name}」`
      : `「${targets[0].item.name}」`)
    : `${targets.length} 项`;
  if (!window.confirm(`确定删除${label}？此操作不可撤销。`)) return;

  closeContextMenu();
  busy.value = 'delete';
  try {
    for (const target of targets) {
      const value = target.kind === 'folder' ? target.item.path : target.item.id;
      await props.api.request(
        `/api/v1/admin/media-library/entries?kind=${encodeURIComponent(target.kind)}&target=${encodeURIComponent(value)}`,
        { method: 'DELETE' },
      );
    }
    clearSelection();
    emit('notify', '已删除。', 'success');
    await loadListing();
  } catch (error) {
    handleError(error);
  } finally {
    busy.value = '';
  }
}

async function downloadTarget(preferred = null) {
  const targets = targetsForAction(preferred).filter((entry) => entry.kind === 'media');
  if (!targets.length) return;
  closeContextMenu();
  busy.value = 'download';
  try {
    for (const target of targets) {
      const blob = await props.api.requestBlob(contentUrl(target.item));
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = target.item.name || 'download';
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(url);
    }
  } catch (error) {
    handleError(error);
  } finally {
    busy.value = '';
  }
}

function joinPath(parent, name) {
  return parent ? `${parent}/${name}` : name;
}

function isForbiddenMoveDest(destPath, source) {
  if (source.kind !== 'folder') return false;
  const from = source.item.path;
  return destPath === from || destPath.startsWith(`${from}/`);
}

async function openMoveDialog(preferred = null) {
  const targets = targetsForAction(preferred);
  if (!targets.length) return;
  closeContextMenu();
  moveTargets.value = targets;
  moveDestPath.value = '';
  moveOpen.value = true;
  treeLoading.value = true;
  try {
    treeNodes.value = [
      {
        path: '',
        name: '全部',
        expanded: true,
        loading: false,
        children: await fetchFolderChildren(''),
      },
    ];
  } catch (error) {
    handleError(error);
    moveOpen.value = false;
  } finally {
    treeLoading.value = false;
  }
}

async function fetchFolderChildren(path) {
  const query = path ? `?path=${encodeURIComponent(path)}` : '';
  const result = await props.api.request(`/api/v1/admin/media-library${query}`);
  return (result?.folders || []).map((folder) => ({
    path: folder.path,
    name: folder.name,
    expanded: false,
    loading: false,
    children: null,
  }));
}

async function toggleTreeNode(node) {
  if (node.expanded) {
    node.expanded = false;
    return;
  }
  node.expanded = true;
  if (node.children == null) {
    node.loading = true;
    try {
      node.children = await fetchFolderChildren(node.path);
    } catch (error) {
      handleError(error);
      node.expanded = false;
    } finally {
      node.loading = false;
    }
  }
}

function selectMoveDest(path) {
  for (const source of moveTargets.value) {
    if (isForbiddenMoveDest(path, source)) {
      emit('notify', '不能移动到自身或其子目录。', 'error');
      return;
    }
  }
  moveDestPath.value = path;
}

async function confirmMove() {
  const dest = moveDestPath.value;
  busy.value = 'move';
  try {
    for (const source of moveTargets.value) {
      if (isForbiddenMoveDest(dest, source)) {
        throw Object.assign(new Error('不能移动到自身或其子目录。'), { code: 'invalid_request' });
      }
      const to = joinPath(dest, source.item.name);
      const from = source.item.path;
      if (from === to) continue;
      await props.api.request('/api/v1/admin/media-library/move', {
        method: 'POST',
        body: { from, to },
      });
    }
    moveOpen.value = false;
    moveTargets.value = [];
    clearSelection();
    emit('notify', '已移动。', 'success');
    await loadListing();
  } catch (error) {
    handleError(error);
  } finally {
    busy.value = '';
  }
}

function triggerUpload() {
  uploadInput.value?.click();
}

async function sha256Hex(file) {
  const buffer = await file.arrayBuffer();
  const digest = await crypto.subtle.digest('SHA-256', buffer);
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('');
}

async function onUploadChange(event) {
  const files = [...(event.target.files || [])];
  event.target.value = '';
  if (!files.length) return;

  busy.value = 'upload';
  try {
    for (const file of files) {
      const sha256 = await sha256Hex(file);
      const query = currentPath.value
        ? `?path=${encodeURIComponent(currentPath.value)}`
        : '';
      await props.api.request(`/api/v1/admin/media-library/upload${query}`, {
        method: 'POST',
        headers: {
          'X-Expected-Size': String(file.size),
          'X-Expected-SHA256': sha256,
          'X-File-Name': file.name,
          ...(file.type ? { 'X-Mime-Type': file.type } : {}),
        },
        body: file,
      });
    }
    emit('notify', files.length === 1 ? '已上传。' : `已上传 ${files.length} 个文件。`, 'success');
    await loadListing();
  } catch (error) {
    handleError(error);
  } finally {
    busy.value = '';
  }
}

function openMkdir() {
  closeContextMenu();
  mkdirName.value = '';
  mkdirOpen.value = true;
  nextTick(() => {
    document.querySelector('.media-dialog-card input')?.focus();
  });
}

function contextStyle() {
  if (!contextMenu.value) return {};
  const menuWidth = 180;
  const menuHeight = 200;
  const x = Math.min(contextMenu.value.x, window.innerWidth - menuWidth - 8);
  const y = Math.min(contextMenu.value.y, window.innerHeight - menuHeight - 8);
  return {
    left: `${Math.max(8, x)}px`,
    top: `${Math.max(8, y)}px`,
  };
}
</script>

<template>
  <section class="page-section media-library-page">
    <div class="page-intro page-intro-compact">
      <div>
        <h1>媒体库</h1>
        <p class="page-lede">浏览已索引的照片与文件夹。</p>
      </div>
      <div class="media-library-actions">
        <template v-if="selectMode">
          <span class="media-select-count">已选 {{ selectedCount }}</span>
          <button
            class="btn btn-outline btn-sm"
            type="button"
            :disabled="!selectedCount || Boolean(busy)"
            @click="openMoveDialog()"
          >
            <Move :size="16" :stroke-width="iconStrokeWidth" aria-hidden="true" />
            移动
          </button>
          <button
            class="btn btn-outline btn-sm media-danger"
            type="button"
            :disabled="!selectedCount || Boolean(busy)"
            @click="deleteTargets()"
          >
            <Trash2 :size="16" :stroke-width="iconStrokeWidth" aria-hidden="true" />
            删除
          </button>
          <button class="btn btn-ghost btn-sm" type="button" @click="exitSelectMode">
            <X :size="16" :stroke-width="iconStrokeWidth" aria-hidden="true" />
            完成
          </button>
        </template>
        <template v-else>
          <button class="btn btn-outline btn-sm" type="button" :disabled="scanBusy || Boolean(busy)" @click="refreshFolder(currentPath)" title="扫描当前文件夹及子文件夹">
            <RefreshCw class="refresh-glyph" :size="16" :stroke-width="iconStrokeWidth" :class="{ spinning: scanBusy }" aria-hidden="true" />
            {{ scanBusy ? '正在刷新…' : '刷新文件夹' }}
          </button>
          <button class="btn btn-outline btn-sm" type="button" :disabled="Boolean(busy)" @click="enterSelectMode">
            <CheckSquare :size="16" :stroke-width="iconStrokeWidth" aria-hidden="true" />
            选择
          </button>
          <button class="btn btn-outline btn-sm" type="button" :disabled="Boolean(busy)" @click="openMkdir">
            <FolderPlus :size="16" :stroke-width="iconStrokeWidth" aria-hidden="true" />
            新建文件夹
          </button>
          <button class="btn btn-primary btn-sm" type="button" :disabled="Boolean(busy)" @click="triggerUpload">
            <Upload :size="16" :stroke-width="iconStrokeWidth" aria-hidden="true" />
            上传
          </button>
        </template>
        <input
          ref="uploadInput"
          class="sr-only"
          type="file"
          accept="image/*,video/*"
          multiple
          @change="onUploadChange"
        >
      </div>
    </div>

    <nav class="media-breadcrumb" aria-label="媒体库路径">
      <button class="media-crumb" type="button" @click="openPath('')">全部</button>
      <template v-for="crumb in breadcrumbs" :key="crumb.path">
        <ChevronRight :size="14" :stroke-width="iconStrokeWidth" class="media-crumb-sep" aria-hidden="true" />
        <button class="media-crumb" type="button" @click="openPath(crumb.path)">{{ crumb.name }}</button>
      </template>
    </nav>

    <aside v-if="scanReport" class="media-index-report" aria-label="最近扫描索引统计">
      <div class="media-index-report-title">
        <div>
          <strong>最近扫描的索引统计</strong>
          <p v-if="scanReport.resumedFromJobId">
            从检查点续跑：此前已处理 {{ scanReport.resumedAtIndexed }} 项，累计 {{ scanReport.indexed }} 项。
          </p>
          <p v-else>统计来自最近一次该服务端扫描任务。</p>
        </div>
        <button
          v-if="scanReport.failed || scanReport.skippedFailed || scanReport.failures.length"
          class="btn btn-outline btn-sm"
          type="button"
          :disabled="scanBusy || Boolean(busy)"
          @click="retryFailedItems"
        >
          重试失败项
        </button>
      </div>
      <dl class="media-index-metrics">
        <div>
          <dt>未索引</dt>
          <dd>{{ scanReport.skippedUnsupported }}</dd>
          <small>不支持格式</small>
        </div>
        <div>
          <dt>已跳过</dt>
          <dd>{{ scanReport.skippedIgnored }}</dd>
          <small>垃圾文件或 0 字节</small>
        </div>
        <div>
          <dt>索引失败</dt>
          <dd>{{ scanReport.failed + scanReport.skippedFailed }}</dd>
          <small>可重试的内容解析失败</small>
        </div>
      </dl>
      <details v-if="scanReport.failures.length" class="scan-failures">
        <summary>查看失败原因（{{ scanReport.failures.length }} 项）</summary>
        <ul>
          <li v-for="failure in scanReport.failures" :key="failure.path">
            <code>{{ failure.path }}</code> — {{ failure.reason }}
          </li>
        </ul>
      </details>
    </aside>

    <div v-if="loading" class="media-grid media-grid-loading" aria-label="正在加载媒体库">
      <div v-for="index in 8" :key="index" class="media-tile skeleton"></div>
    </div>

    <div v-else-if="emptyLibrary" class="empty-state media-empty">
      <Images :size="28" :stroke-width="iconStrokeWidth" aria-hidden="true" />
      <strong>这里还没有内容</strong>
      <p>上传照片，或刷新文件夹以发现新拷入的媒体。</p>
    </div>

    <div v-else class="media-grid" :class="{ 'is-select-mode': selectMode }" role="list">
      <button
        v-for="folder in folders"
        :key="`folder-${folder.path}`"
        class="media-tile media-tile-folder"
        type="button"
        role="listitem"
        :class="{ 'is-selected': isSelected('folder', folder) }"
        @click="onTileClick('folder', folder)"
        @contextmenu="onTileContextMenu($event, 'folder', folder)"
      >
        <span v-if="selectMode" class="media-tile-check" aria-hidden="true">
          <Check v-if="isSelected('folder', folder)" :size="14" :stroke-width="2.2" />
          <Square v-else :size="14" :stroke-width="2" />
        </span>
        <span class="media-tile-preview folder-preview" aria-hidden="true">
          <img
            v-if="hasFolderThumbnail(folder)"
            :src="folderThumbnailUrl(folder)"
            alt=""
            loading="lazy"
            @error="onFolderThumbError(folder)"
          >
          <Folder v-else :size="36" :stroke-width="iconStrokeWidth" />
        </span>
        <span class="media-tile-meta">
          <strong>{{ folder.name }}</strong>
          <small>{{ folder.mediaCount }} 项</small>
        </span>
      </button>

      <button
        v-for="item in media"
        :key="`media-${item.id}`"
        class="media-tile media-tile-file"
        type="button"
        role="listitem"
        :class="{ 'is-selected': isSelected('media', item) }"
        @click="onTileClick('media', item)"
        @contextmenu="onTileContextMenu($event, 'media', item)"
      >
        <span v-if="selectMode" class="media-tile-check" aria-hidden="true">
          <Check v-if="isSelected('media', item)" :size="14" :stroke-width="2.2" />
          <Square v-else :size="14" :stroke-width="2" />
        </span>
        <span class="media-tile-preview">
          <img :src="thumbnailUrl(item)" :alt="item.name" loading="lazy">
          <span v-if="item.isVideo" class="media-video-badge">视频</span>
        </span>
      </button>
    </div>

    <Teleport to="body">
      <div
        v-if="contextMenu"
        class="media-context-menu"
        :style="contextStyle()"
        role="menu"
        @click.stop
      >
        <button
          v-if="contextMenu.kind === 'folder'"
          class="media-context-item"
          type="button"
          role="menuitem"
          @click="openPath(contextMenu.item.path); closeContextMenu()"
        >
          打开
        </button>
        <button
          v-else
          class="media-context-item"
          type="button"
          role="menuitem"
          @click="openLightbox(contextMenu.item)"
        >
          预览
        </button>
        <button
          v-if="contextMenu.kind === 'media'"
          class="media-context-item"
          type="button"
          role="menuitem"
          @click="openInfo(contextMenu.item)"
        >
          属性
        </button>
        <button
          v-if="contextMenu.kind === 'folder'"
          class="media-context-item"
          type="button"
          role="menuitem"
          :disabled="scanBusy || Boolean(busy)"
          @click="refreshFolder(contextMenu.item.path)"
        >
          {{ scanBusy ? '正在刷新…' : '刷新' }}
        </button>
        <button
          v-if="contextMenu.kind === 'media'"
          class="media-context-item"
          type="button"
          role="menuitem"
          :disabled="Boolean(busy)"
          @click="downloadTarget(contextMenu)"
        >
          下载
        </button>
        <button
          class="media-context-item"
          type="button"
          role="menuitem"
          :disabled="Boolean(busy)"
          @click="openMoveDialog(contextMenu)"
        >
          移动
        </button>
        <button
          class="media-context-item is-danger"
          type="button"
          role="menuitem"
          :disabled="Boolean(busy)"
          @click="deleteTargets(contextMenu)"
        >
          删除
        </button>
      </div>
    </Teleport>

    <Teleport to="body">
      <div
        v-if="mkdirOpen"
        class="media-overlay"
        role="presentation"
        @click.self="mkdirOpen = false"
      >
        <form class="media-dialog-card" @submit.prevent="createFolder" @click.stop>
          <h2>新建文件夹</h2>
          <label class="form-control">
            <span class="label-text">名称</span>
            <input v-model.trim="mkdirName" class="input input-bordered" type="text" required maxlength="200">
          </label>
          <div class="form-actions">
            <button class="btn btn-outline" type="button" @click="mkdirOpen = false">取消</button>
            <button class="btn btn-primary" type="submit" :disabled="busy === 'mkdir'">创建</button>
          </div>
        </form>
      </div>
    </Teleport>

    <Teleport to="body">
      <div
        v-if="moveOpen"
        class="media-overlay"
        role="presentation"
        @click.self="moveOpen = false"
      >
        <form class="media-dialog-card media-dialog-card-wide" @submit.prevent="confirmMove" @click.stop>
          <h2>移动到</h2>
          <p class="media-dialog-hint">选择目标文件夹</p>
          <div v-if="treeLoading" class="media-tree-loading">正在读取目录…</div>
          <div v-else class="media-tree" role="tree">
            <MediaFolderTree
              v-for="node in treeNodes"
              :key="node.path || 'root'"
              :node="node"
              :selected-path="moveDestPath"
              :depth="0"
              @toggle="toggleTreeNode"
              @select="selectMoveDest"
            />
          </div>
          <div class="form-actions">
            <button class="btn btn-outline" type="button" @click="moveOpen = false">取消</button>
            <button class="btn btn-primary" type="submit" :disabled="busy === 'move'">
              移动到此处
            </button>
          </div>
        </form>
      </div>
    </Teleport>

    <Teleport to="body">
      <div
        v-if="infoOpen"
        class="media-overlay"
        role="presentation"
        @click.self="closeInfo"
      >
        <div
          class="media-dialog-card media-dialog-card-info"
          role="dialog"
          aria-modal="true"
          aria-label="媒体属性"
          @click.stop
        >
          <h2>属性</h2>
          <p v-if="infoLoading" class="media-dialog-hint">正在读取属性…</p>
          <p v-else-if="infoError" class="media-dialog-error">{{ infoError }}</p>
          <div v-else class="media-info-list">
            <div v-for="row in infoRows" :key="row[0]" class="media-info-row">
              <span class="media-info-label">{{ row[0] }}</span>
              <span class="media-info-value">{{ row[1] }}</span>
            </div>
          </div>
          <div class="form-actions">
            <button class="btn btn-primary" type="button" @click="closeInfo">关闭</button>
          </div>
        </div>
      </div>
    </Teleport>

    <Teleport to="body">
      <div
        v-if="lightbox"
        class="media-lightbox"
        role="dialog"
        aria-modal="true"
        aria-label="媒体预览"
        @click.self="closeLightbox"
      >
        <button class="media-lightbox-close" type="button" aria-label="关闭预览" @click="closeLightbox">
          <X :size="20" :stroke-width="iconStrokeWidth" />
        </button>
        <video
          v-if="lightbox.isVideo"
          class="media-lightbox-media"
          :src="contentUrl(lightbox)"
          controls
          autoplay
        />
        <img
          v-else
          class="media-lightbox-media"
          :src="contentUrl(lightbox)"
          :alt="lightbox.name"
        >
        <p class="media-lightbox-caption">{{ lightbox.name }}</p>
      </div>
    </Teleport>
  </section>
</template>
