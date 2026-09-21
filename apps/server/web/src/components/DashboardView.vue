<script setup>
// 管理端外壳：侧边导航 + 顶栏（面包屑 / 用户菜单）+ Toast 反馈。
// 数据加载与任务轮询集中在壳层，通过 props 下发、事件上抛。
import { computed, onMounted, onUnmounted, ref } from 'vue';
import {
  AlertCircle,
  CheckCircle2,
  Images,
  LayoutDashboard,
  ListTodo,
  LogOut,
  Menu,
  Trash2,
  Users,
  X,
} from '@lucide/vue';
import { getApiErrorMessage } from '../api';
import { isActiveJob, isTerminalJob, jobScanReport } from '../utils/formatters';
import DashboardHome from './DashboardHome.vue';
import MediaLibraryView from './MediaLibraryView.vue';
import LogsView from './LogsView.vue';
import TrashView from './TrashView.vue';
import UsersView from './UsersView.vue';

const props = defineProps({
  api: {
    type: Object,
    required: true,
  },
});

const emit = defineEmits(['logout', 'session-expired']);

const navigation = [
  { id: 'dashboard', label: '仪表盘', description: '状态与健康', icon: LayoutDashboard },
  { id: 'storage', label: '媒体库', description: '浏览与整理', icon: Images },
  { id: 'trash', label: '回收站', description: '删除与恢复', icon: Trash2 },
  { id: 'users', label: '用户', description: '家人与媒体库', icon: Users },
  { id: 'logs', label: '日志', description: '进度与运行记录', icon: ListTodo },
];

const activeSection = ref('dashboard');
const mobileNavOpen = ref(false);
const initialLoading = ref(true);
const refreshing = ref(false);
const actionBusy = ref('');
const toast = ref(null);
const status = ref(null);
const storage = ref(null);
const jobs = ref({ items: [] });
const watchedScanId = ref(null);
const libraryRevision = ref(0);
const diagnostics = ref(null);
const auditLog = ref([]);
const auditLoaded = ref(false);
const userMenuOpen = ref(false);
const logoPath = '/admin/logo_mark.png';
const iconStrokeWidth = 1.8;
let toastTimer = null;
let pollTimer = null;

const activeNavigation = computed(
  () => navigation.find((item) => item.id === activeSection.value) || navigation[0],
);

const activeJobs = computed(() =>
  (jobs.value.items || []).filter((job) => isActiveJob(job)),
);

const latestScanReport = computed(() =>
  (jobs.value.items || []).find((job) => job.kind === 'scan' && jobScanReport(job)) || null,
);

function notify(message, type = 'info') {
  window.clearTimeout(toastTimer);
  toast.value = { message, type };
  if (type !== 'error') {
    toastTimer = window.setTimeout(() => {
      toast.value = null;
    }, 6000);
  }
}

function dismissToast() {
  window.clearTimeout(toastTimer);
  toast.value = null;
}

function handleError(error, silent = false) {
  if (error?.code === 'unauthorized') {
    emit('session-expired');
    return;
  }
  if (!silent) {
    notify(getApiErrorMessage(error), 'error');
  }
}

async function loadDashboard({ silent = false } = {}) {
  if (!silent) refreshing.value = true;

  try {
    const [statusResult, storageResult, jobsResult] =
      await Promise.all([
        props.api.request('/api/v1/admin/status'),
        props.api.request('/api/v1/admin/storage'),
        props.api.request('/api/v1/admin/jobs?limit=20'),
      ]);

    status.value = statusResult;
    storage.value = storageResult;
    jobs.value = jobsResult || { items: [] };

    if (activeJobs.value.length) {
      scheduleJobPoll();
    } else {
      stopJobPoll();
    }

    await Promise.all([
      diagnostics.value ? Promise.resolve() : loadDiagnostics({ silent: true }),
      auditLoaded.value ? Promise.resolve() : loadAudit({ silent: true }),
    ]);
  } catch (error) {
    handleError(error, silent);
  } finally {
    initialLoading.value = false;
    refreshing.value = false;
  }
}

async function loadDiagnostics({ silent = false } = {}) {
  try {
    diagnostics.value = await props.api.request('/api/v1/admin/diagnostics');
  } catch (error) {
    handleError(error, silent);
  }
}

async function loadAudit({ silent = false } = {}) {
  try {
    const page = await props.api.request('/api/v1/admin/audit-log?limit=30');
    auditLog.value = page?.items || [];
    auditLoaded.value = true;
  } catch (error) {
    handleError(error, silent);
  }
}

async function refreshAll() {
  dismissToast();
  await loadDashboard();
  await Promise.all([loadDiagnostics(), loadAudit()]);
  notify('状态已更新。', 'success');
}

const validSections = new Set(navigation.map((item) => item.id));

function sectionFromHash() {
  const raw = window.location.hash.replace(/^#\/?/, '').split('?')[0];
  if (raw === 'tasks') return 'logs';
  return validSections.has(raw) ? raw : null;
}

function selectSection(section) {
  activeSection.value = section;
  mobileNavOpen.value = false;
  const target = `#/${section}`;
  if (window.location.hash !== target) {
    // 写入 hash 产生历史记录，浏览器前进/后退可在页面间导航
    window.location.hash = target;
  }
  if (section === 'dashboard') {
    void Promise.all([
      loadDiagnostics({ silent: true }),
      auditLoaded.value ? Promise.resolve() : loadAudit({ silent: true }),
    ]);
  }
}

function onHashChange() {
  const section = sectionFromHash();
  if (section && section !== activeSection.value) {
    activeSection.value = section;
    mobileNavOpen.value = false;
    if (section === 'dashboard') {
      void Promise.all([
        loadDiagnostics({ silent: true }),
        auditLoaded.value ? Promise.resolve() : loadAudit({ silent: true }),
      ]);
    }
  }
}

async function loadJobs() {
  const result = await props.api.request('/api/v1/admin/jobs?limit=20');
  jobs.value = result || { items: [] };
}

async function startScan(path = '', options = {}) {
  if (actionBusy.value === 'scan-start') return;
  actionBusy.value = 'scan-start';
  try {
    const retrySuffix = options.retryFailed ? '&retry_failed=true' : '';
    const job = await props.api.request(
      `/api/v1/admin/jobs/scan?path=${encodeURIComponent(path)}${retrySuffix}`,
      { method: 'POST' },
    );
    watchedScanId.value = job?.id || null;
    await loadJobs();
    notify(
      options.retryFailed
        ? `正在重试「${path || '全部'}」中的失败项，进度可在日志中查看。`
        : `正在刷新「${path || '全部'}」，进度可在日志中查看。`,
      'success',
    );
    scheduleJobPoll();
  } catch (error) {
    handleError(error);
  } finally {
    actionBusy.value = '';
  }
}

function retryFailedScan(jobOrPath) {
  const scopePath = typeof jobOrPath === 'string'
    ? jobOrPath
    : jobOrPath?.checkpoint?.scopePath || '';
  startScan(scopePath, { retryFailed: true });
}

function scheduleJobPoll() {
  window.clearTimeout(pollTimer);
  pollTimer = window.setTimeout(pollJobs, 1500);
}

function stopJobPoll() {
  window.clearTimeout(pollTimer);
  pollTimer = null;
}

async function pollJobs() {
  try {
    await loadJobs();
    if (watchedScanId.value) {
      const watched = (jobs.value.items || []).find((job) => job.id === watchedScanId.value);
      if (watched && isTerminalJob(watched)) {
        watchedScanId.value = null;
        libraryRevision.value += 1;
        notify(
          watched.status === 'succeeded' ? '文件夹刷新完成，详情见日志。' : '文件夹刷新未完成，请查看日志。',
          watched.status === 'succeeded' ? 'success' : 'error',
        );
        await loadDashboard({ silent: true });
      }
    }
    if (activeJobs.value.length) {
      scheduleJobPoll();
    } else {
      stopJobPoll();
    }
  } catch (error) {
    handleError(error, true);
  }
}

async function cancelJob(jobId) {
  try {
    await props.api.request(`/api/v1/jobs/${encodeURIComponent(jobId)}/cancel`, {
      method: 'POST',
    });
    notify('任务已取消。', 'success');
    await loadJobs();
  } catch (error) {
    handleError(error);
  }
}

async function logout() {
  actionBusy.value = 'logout';
  try {
    await props.api.request('/api/v1/admin/session', { method: 'DELETE' });
  } catch (error) {
    if (error?.code !== 'unauthorized') {
      handleError(error, true);
    }
  } finally {
    props.api.clearCsrfToken();
    emit('logout');
    actionBusy.value = '';
  }
}

function onGlobalClick(event) {
  if (userMenuOpen.value && !event.target.closest('.dropdown')) {
    userMenuOpen.value = false;
  }
}

function onGlobalKeydown(event) {
  if (event.key === 'Escape' && userMenuOpen.value) {
    userMenuOpen.value = false;
  }
}

onMounted(() => {
  const initial = sectionFromHash();
  if (initial) {
    activeSection.value = initial;
  }
  window.addEventListener('hashchange', onHashChange);
  window.addEventListener('click', onGlobalClick);
  window.addEventListener('keydown', onGlobalKeydown);
  void loadDashboard();
});

onUnmounted(() => {
  window.removeEventListener('hashchange', onHashChange);
  window.removeEventListener('click', onGlobalClick);
  window.removeEventListener('keydown', onGlobalKeydown);
  window.clearTimeout(toastTimer);
  stopJobPoll();
});
</script>

<template>
  <div class="dashboard-shell">
    <button
      v-if="mobileNavOpen"
      class="nav-backdrop"
      type="button"
      aria-label="关闭导航"
      @click="mobileNavOpen = false"
    ></button>

    <aside class="dashboard-sidebar" :class="{ 'is-open': mobileNavOpen }">
      <div class="sidebar-brand">
        <div class="brand-lockup">
          <img class="brand-logo" :src="logoPath" alt="柚柚标志">
          <span>
            <strong>柚柚相册</strong>
            <small>轻松管理人生影相</small>
          </span>
        </div>
        <button
          class="sidebar-close"
          type="button"
          aria-label="关闭导航"
          @click="mobileNavOpen = false"
        >
          <X :size="18" :stroke-width="iconStrokeWidth" aria-hidden="true" />
        </button>
      </div>

      <nav class="dashboard-nav" aria-label="管理页面">
        <button
          v-for="item in navigation"
          :key="item.id"
          class="dashboard-nav-item"
          :class="{ active: activeSection === item.id }"
          type="button"
          @click="selectSection(item.id)"
        >
          <span class="nav-glyph" aria-hidden="true">
            <component :is="item.icon" :size="18" :stroke-width="iconStrokeWidth" />
          </span>
          <span class="nav-copy">
            <strong>{{ item.label }}</strong>
            <small>{{ item.description }}</small>
          </span>
          <span v-if="item.id === 'logs' && activeJobs.length" class="nav-count">
            {{ activeJobs.length }}
          </span>
        </button>
      </nav>

      <div class="sidebar-footer">
        <span class="sidebar-version">服务端 {{ status?.serverVersion || '—' }}</span>
      </div>
    </aside>

    <div class="dashboard-main">
      <header class="dashboard-topbar">
        <div class="topbar-left">
          <button
            class="mobile-nav-trigger"
            type="button"
            aria-label="打开导航"
            @click="mobileNavOpen = true"
          >
            <Menu :size="20" :stroke-width="iconStrokeWidth" aria-hidden="true" />
          </button>
          <div class="breadcrumb">
            <span>管理工作台</span>
            <span class="breadcrumb-divider">/</span>
            <strong>{{ activeNavigation.label }}</strong>
          </div>
        </div>
        <div class="topbar-actions">
          <div class="dropdown dropdown-end">
            <button
              class="user-menu-trigger"
              type="button"
              :aria-expanded="userMenuOpen"
              aria-haspopup="menu"
              :disabled="actionBusy === 'logout'"
              @click="userMenuOpen = !userMenuOpen"
            >
              <span class="user-avatar" aria-hidden="true">管</span>
              <span class="user-menu-name">管理员</span>
            </button>
            <ul
              v-if="userMenuOpen"
              class="menu dropdown-content user-menu-panel"
            >
              <li class="user-menu-meta">
                <span>服务端 {{ status?.serverVersion || '—' }}</span>
              </li>
              <li>
                <button
                  type="button"
                  :disabled="actionBusy === 'logout'"
                  @click="userMenuOpen = false; logout()"
                >
                  <LogOut :size="16" :stroke-width="iconStrokeWidth" aria-hidden="true" />
                  退出登录
                </button>
              </li>
            </ul>
          </div>
        </div>
      </header>

      <main class="dashboard-content">
        <div v-if="toast" class="admin-toast-wrap" role="status" aria-live="polite">
          <div class="alert admin-toast" :class="`alert-${toast.type}`">
            <span class="alert-symbol" aria-hidden="true">
              <AlertCircle
                v-if="toast.type === 'error'"
                :size="17"
                :stroke-width="iconStrokeWidth"
              />
              <CheckCircle2 v-else :size="17" :stroke-width="iconStrokeWidth" />
            </span>
            <span>{{ toast.message }}</span>
            <button class="alert-close" type="button" aria-label="关闭提示" @click="dismissToast">
              <X :size="18" :stroke-width="iconStrokeWidth" aria-hidden="true" />
            </button>
          </div>
        </div>

        <div v-if="initialLoading" class="loading-page" aria-label="正在加载管理数据">
          <div class="skeleton loading-title"></div>
          <div class="loading-metrics">
            <div v-for="index in 4" :key="index" class="surface-card loading-metric">
              <div class="skeleton loading-line loading-line-short"></div>
              <div class="skeleton loading-line loading-line-large"></div>
            </div>
          </div>
          <div class="surface-card loading-panel">
            <div class="skeleton loading-line loading-line-medium"></div>
            <div class="skeleton loading-block"></div>
          </div>
        </div>

        <template v-else>
          <DashboardHome
            v-if="activeSection === 'dashboard'"
            :status="status"
            :storage="storage"
            :diagnostics="diagnostics"
            :jobs="jobs"
            :audit-log="auditLog"
            :refreshing="refreshing"
            @refresh="refreshAll"
            @go="selectSection"
          />

          <MediaLibraryView
            v-else-if="activeSection === 'storage'"
            :api="api"
            :scan-busy="actionBusy === 'scan-start' || activeJobs.some((job) => job.kind === 'scan')"
            :scan-report="latestScanReport ? jobScanReport(latestScanReport) : null"
            :refresh-version="libraryRevision"
            @refresh-folder="startScan"
            @retry-scan="retryFailedScan"
            @notify="(message, type) => notify(message, type)"
            @session-expired="emit('session-expired')"
          />

          <UsersView
            v-else-if="activeSection === 'users'"
            :api="api"
            @notify="(message, type) => notify(message, type)"
          />

          <TrashView
            v-else-if="activeSection === 'trash'"
            :api="api"
            @notify="(message, type) => notify(message, type)"
            @session-expired="emit('session-expired')"
          />

          <LogsView
            v-else-if="activeSection === 'logs'"
            :jobs="jobs"
            @cancel-job="cancelJob"
            @retry-scan="retryFailedScan"
          />
        </template>
      </main>
    </div>
  </div>
</template>
