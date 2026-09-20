<script setup>
// 标准用户列表页：用户表格 + 新建（含目录名）/二维码/收藏/删除 弹窗。
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { Heart, QrCode, Trash2, UserPlus, Users } from '@lucide/vue';
import QrcodeVue from 'qrcode.vue';
import HelpTooltip from './HelpTooltip.vue';
import { getApiErrorMessage } from '../api';
import { formatNumber, formatTime } from '../utils/formatters';

const props = defineProps({
  api: {
    type: Object,
    required: true,
  },
});

const emit = defineEmits(['notify']);

// 弹窗打开时把焦点放进第一个输入框
const vAutofocus = { mounted: (el) => el.focus() };

const iconStrokeWidth = 1.8;
const users = ref([]);
const loading = ref(true);
const busy = ref('');

// 标准弹窗状态：create | bind | qr | favorites | delete
const modal = ref(null);
const modalUser = ref(null);
const newName = ref('');
const newLibraryName = ref('');
const favorites = ref([]);
const favoritesLoading = ref(false);
const deleteConfirmName = ref('');

const pairing = ref(null); // { userId, code, expiresAt }
const nowMs = ref(Date.now());
const pairingServerUrl = window.location.origin;
let tickTimer = null;

const pairingAddressProblem = computed(() => {
  try {
    const host = new URL(pairingServerUrl).hostname;
    return host === 'localhost' || host === '::1' || host === '127.0.0.1' || host.startsWith('127.');
  } catch {
    return true;
  }
});

const pairingExpired = computed(() => {
  if (!pairing.value?.expiresAt) return false;
  return Number(pairing.value.expiresAt) <= nowMs.value;
});

const pairingQrValue = computed(() => {
  if (!pairing.value?.code) return '';
  return JSON.stringify({
    type: 'youyou-connect',
    version: 1,
    serverUrl: pairingServerUrl,
    pairingCode: pairing.value.code,
  });
});

const pairingRemainingLabel = computed(() => {
  if (!pairing.value?.expiresAt) return '';
  const remaining = Number(pairing.value.expiresAt) - nowMs.value;
  if (remaining <= 0) return '已过期，请重新生成';
  const totalSeconds = Math.ceil(remaining / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `剩余 ${minutes}:${String(seconds).padStart(2, '0')}`;
});

function notify(message, type = 'info') {
  emit('notify', message, type);
}

function startTick() {
  stopTick();
  nowMs.value = Date.now();
  tickTimer = window.setInterval(() => {
    nowMs.value = Date.now();
  }, 1000);
}

function stopTick() {
  window.clearInterval(tickTimer);
  tickTimer = null;
}

function openModal(kind, user = null) {
  modal.value = kind;
  modalUser.value = user;
  if (kind === 'create') {
    newName.value = '';
    newLibraryName.value = '';
  }
  if (kind === 'delete') deleteConfirmName.value = '';
  if (kind === 'favorites') loadFavorites(user);
}

function closeModal() {
  modal.value = null;
  modalUser.value = null;
  if (modal.value !== 'qr') {
    pairing.value = null;
    stopTick();
  }
}

async function loadUsers({ silent = false } = {}) {
  if (!silent) loading.value = true;
  try {
    users.value = (await props.api.request('/api/v1/admin/users')) || [];
  } catch (error) {
    notify(getApiErrorMessage(error), 'error');
  } finally {
    loading.value = false;
  }
}

async function submitCreate() {
  const name = newName.value.trim();
  const libraryName = newLibraryName.value.trim();
  if (!name) {
    notify('请输入用户名。', 'error');
    return;
  }
  if (!/^[A-Za-z0-9]{1,64}$/.test(libraryName)) {
    notify('目录名只能包含 1-64 位英文字母和数字。', 'error');
    return;
  }
  const existing = users.value.find((user) => user.libraryRoot === libraryName);
  if (existing) {
    notify(`目录 ${libraryName} 已被用户「${existing.name}」绑定，不能重复绑定。`, 'error');
    return;
  }
  try {
    const status = await props.api.request('/api/v1/admin/status');
    if (status?.storage?.writable !== true) {
      notify('服务端媒体根目录不可写，请先检查存储权限。', 'error');
      return;
    }
  } catch (error) {
    notify(`无法完成存储预检：${getApiErrorMessage(error)}`, 'error');
    return;
  }
  const accepted = window.confirm(
    `请确认创建信息：\n用户：${name}\n媒体库目录：${libraryName}\n\n目录将被终身绑定且不可更换；若目录已存在，其中未分配媒体会归入该用户。`,
  );
  if (!accepted) return;
  busy.value = 'create';
  try {
    await props.api.request('/api/v1/admin/users', {
      method: 'POST',
      body: { name, libraryName },
    });
    notify(`用户「${name}」已创建，媒体库目录 ${libraryName} 已绑定。`, 'success');
    closeModal();
    await loadUsers({ silent: true });
  } catch (error) {
    notify(getApiErrorMessage(error), 'error');
  } finally {
    busy.value = '';
  }
}

async function openPairing(user) {
  if (pairingAddressProblem.value) {
    notify('管理端地址是 localhost，手机无法访问，请改用局域网 IP 或反向代理地址。', 'error');
    return;
  }
  busy.value = `pair-${user.id}`;
  try {
    const status = await props.api.request('/api/v1/admin/status');
    if (status?.storage?.writable !== true) {
      notify('媒体根目录不可写，无法生成二维码，请检查存储目录权限。', 'error');
      return;
    }
    const code = await props.api.request(
      `/api/v1/admin/users/${encodeURIComponent(user.id)}/pairing-codes`,
      { method: 'POST' },
    );
    pairing.value = { userId: user.id, code: code.code, expiresAt: code.expiresAt };
    modal.value = 'qr';
    modalUser.value = user;
    startTick();
    notify(`已为「${user.name}」生成连接二维码，有效期 10 分钟。`, 'success');
  } catch (error) {
    notify(getApiErrorMessage(error), 'error');
  } finally {
    busy.value = '';
  }
}

async function copyPairingValue(value, label) {
  try {
    await navigator.clipboard.writeText(value);
    notify(`${label}已复制。`, 'success');
  } catch {
    notify(`无法自动复制${label}，请手动选择：${value}`, 'info');
  }
}

async function loadFavorites(user) {
  favoritesLoading.value = true;
  favorites.value = [];
  try {
    favorites.value =
      (await props.api.request(
        `/api/v1/admin/users/${encodeURIComponent(user.id)}/favorites`,
      )) || [];
  } catch (error) {
    notify(getApiErrorMessage(error), 'error');
  } finally {
    favoritesLoading.value = false;
  }
}

function requestDelete(user) {
  openModal('delete', user);
}

async function submitDelete() {
  const user = modalUser.value;
  if (deleteConfirmName.value !== user.name) {
    notify('输入的用户名不匹配，未删除。', 'error');
    return;
  }
  busy.value = 'delete';
  try {
    await props.api.request(`/api/v1/admin/users/${encodeURIComponent(user.id)}`, {
      method: 'DELETE',
    });
    notify(`用户「${user.name}」已删除，其媒体已归还未分配目录。`, 'success');
    closeModal();
    await loadUsers({ silent: true });
  } catch (error) {
    notify(getApiErrorMessage(error), 'error');
  } finally {
    busy.value = '';
  }
}

function thumbnailUrl(item) {
  return `/api/v1/admin/media-library/media/${encodeURIComponent(item.id)}/thumbnail?size=128`;
}

function onKeydown(event) {
  if (event.key === 'Escape' && modal.value) {
    closeModal();
  }
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown);
  void loadUsers();
});

onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown);
  stopTick();
});
</script>

<template>
  <section class="page-section">
    <div class="page-intro">
      <div>
        <h1>用户</h1>
        <p class="page-lede">
          为每个用户绑定一个专属媒体库目录。
          <HelpTooltip
            text="目录名仅支持 1-64 位英文字母和数字。服务端会在媒体根目录下自动创建该目录，并与用户终身绑定，创建后不可更换。"
            label="查看媒体库目录说明"
          />
        </p>
      </div>
      <button class="btn btn-primary" type="button" @click="openModal('create')">
        <UserPlus :size="17" :stroke-width="iconStrokeWidth" aria-hidden="true" />
        新建用户
      </button>
    </div>

    <article class="surface-card data-card">
      <div class="card-heading">
        <div>
          <h2>用户列表</h2>
        </div>
        <button
          class="btn btn-ghost btn-sm"
          type="button"
          :disabled="loading"
          @click="loadUsers()"
        >
          <Users :size="15" :stroke-width="iconStrokeWidth" aria-hidden="true" />
          刷新
        </button>
      </div>

      <div v-if="loading" class="empty-state empty-state-compact">
        <span class="loading loading-spinner"></span>
        <strong>正在读取用户</strong>
      </div>

      <div v-else-if="!users.length" class="empty-state">
        <span class="empty-symbol" aria-hidden="true">
          <Users :size="22" :stroke-width="iconStrokeWidth" />
        </span>
        <strong>还没有用户</strong>
        <p>点击右上角「新建用户」，再绑定媒体库目录并生成二维码。</p>
      </div>

      <div v-else class="table-wrap">
        <table class="table admin-table">
          <thead>
            <tr>
              <th scope="col">用户</th>
              <th scope="col">媒体库目录</th>
              <th scope="col" class="table-num">照片</th>
              <th scope="col" class="table-num">设备</th>
              <th scope="col">创建时间</th>
              <th scope="col" class="table-actions-col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="user in users" :key="user.id">
              <td>
                <div class="user-cell">
                  <span class="device-avatar" aria-hidden="true">{{ user.name?.slice(0, 1) || '用' }}</span>
                  <strong>{{ user.name }}</strong>
                </div>
              </td>
              <td>
                <span v-if="user.libraryRoot" class="library-path">{{ user.libraryRoot }}</span>
                <span v-else class="status-badge status-muted">未绑定</span>
              </td>
              <td class="table-num">{{ formatNumber(user.mediaCount) }}</td>
              <td class="table-num">{{ formatNumber(user.deviceCount) }}</td>
              <td class="table-time">{{ formatTime(user.createdAt) }}</td>
              <td>
                <div class="table-actions">
                  <button
                    class="btn btn-outline btn-xs"
                    type="button"
                    :disabled="busy === `pair-${user.id}`"
                    @click="openPairing(user)"
                  >
                    <span v-if="busy === `pair-${user.id}`" class="loading loading-spinner loading-xs"></span>
                    <QrCode v-else :size="13" :stroke-width="iconStrokeWidth" aria-hidden="true" />
                    二维码
                  </button>
                  <button class="btn btn-outline btn-xs" type="button" @click="openModal('favorites', user)">
                    <Heart :size="13" :stroke-width="iconStrokeWidth" aria-hidden="true" />
                    收藏
                  </button>
                  <button class="btn btn-error btn-xs" type="button" @click="requestDelete(user)">
                    <Trash2 :size="13" :stroke-width="iconStrokeWidth" aria-hidden="true" />
                    删除
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
    v-if="modal"
    class="modal modal-open"
    role="dialog"
    aria-modal="true"
    aria-labelledby="admin-modal-heading"
    @click.self="closeModal"
  >
      <div class="modal-box admin-modal">
        <button class="modal-close-btn" type="button" aria-label="关闭" @click="closeModal">
          <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>
        </button>

        <template v-if="modal === 'create'">
          <h3 id="admin-modal-heading" class="admin-modal-title">新建用户</h3>
          <p class="admin-modal-lede">服务端会在媒体根目录下自动创建该目录并与用户终身绑定（不可更换）。</p>
          <form @submit.prevent="submitCreate">
            <label class="form-label" for="new-user-name">用户名</label>
            <input
              id="new-user-name"
              v-autofocus
              v-model="newName"
              class="input input-bordered w-full"
              type="text"
              placeholder="如：妈妈"
              maxlength="100"
            >
            <label class="form-label" for="new-library-name">媒体库目录名</label>
            <input
              id="new-library-name"
              v-model="newLibraryName"
              class="input input-bordered w-full"
              type="text"
              placeholder="仅英文字母和数字，如 mama"
              maxlength="64"
            >
            <div class="admin-modal-actions">
              <button class="btn btn-ghost" type="button" @click="closeModal">取消</button>
              <button class="btn btn-primary" type="submit" :disabled="busy === 'create'">
                <span v-if="busy === 'create'" class="loading loading-spinner loading-sm"></span>
                创建
              </button>
            </div>
          </form>
        </template>

        <template v-else-if="modal === 'qr'">
          <h3 id="admin-modal-heading" class="admin-modal-title">{{ modalUser?.name }} 的连接二维码</h3>
          <p class="admin-modal-lede">扫码即登录并绑定该用户的媒体库。</p>
          <div v-if="pairing && !pairingExpired" class="pairing-qr-panel">
            <div class="pairing-qr-frame" role="img" aria-label="用户连接二维码">
              <QrcodeVue :value="pairingQrValue" :size="176" level="H" render-as="svg" :margin="3" />
            </div>
            <div class="pairing-qr-copy">
              <strong class="pairing-remaining" aria-live="polite">{{ pairingRemainingLabel }}</strong>
              <p>有效期至 {{ formatTime(pairing.expiresAt) }}</p>
              <div class="pairing-value-row">
                <span>配对码：<code>{{ pairing.code }}</code></span>
                <button class="btn btn-ghost btn-xs" type="button" @click="copyPairingValue(pairing.code, '配对码')">复制</button>
              </div>
              <div class="pairing-value-row">
                <span>服务端：<code>{{ pairingServerUrl }}</code></span>
                <button class="btn btn-ghost btn-xs" type="button" @click="copyPairingValue(pairingServerUrl, '服务端地址')">复制</button>
              </div>
            </div>
          </div>
          <div v-else class="admin-modal-actions admin-modal-actions-col">
            <p>二维码已过期，请重新生成。</p>
            <button class="btn btn-primary btn-sm" type="button" @click="openPairing(modalUser)">
              重新生成
            </button>
          </div>
          <div class="admin-modal-actions">
            <button class="btn btn-ghost" type="button" @click="closeModal">关闭</button>
          </div>
        </template>

        <template v-else-if="modal === 'favorites'">
          <h3 id="admin-modal-heading" class="admin-modal-title">{{ modalUser?.name }} 的收藏</h3>
          <div v-if="favoritesLoading" class="empty-state empty-state-compact">
            <span class="loading loading-spinner"></span>
            <strong>正在读取收藏</strong>
          </div>
          <div v-else-if="favorites.length" class="favorites-grid">
            <img
              v-for="item in favorites"
              :key="item.id"
              :src="thumbnailUrl(item)"
              :alt="item.name"
              class="favorites-thumb"
              loading="lazy"
            >
          </div>
          <p v-else class="favorites-empty">该用户还没有收藏。</p>
          <div class="admin-modal-actions">
            <button class="btn btn-ghost" type="button" @click="closeModal">关闭</button>
          </div>
        </template>

        <template v-else-if="modal === 'delete'">
          <h3 id="admin-modal-heading" class="admin-modal-title">删除用户 — {{ modalUser?.name }}</h3>
          <p class="admin-modal-lede">
            将吊销该用户全部设备、删除其标签，媒体归还未分配目录，且不可撤销。
          </p>
          <form @submit.prevent="submitDelete">
            <label class="form-label" for="delete-confirm">输入用户名「{{ modalUser?.name }}」以确认</label>
            <input
              id="delete-confirm"
              v-autofocus
              v-model="deleteConfirmName"
              class="input input-bordered w-full"
              type="text"
              autocomplete="off"
            >
            <div class="admin-modal-actions">
              <button class="btn btn-ghost" type="button" @click="closeModal">取消</button>
              <button class="btn btn-error" type="submit" :disabled="busy === 'delete' || deleteConfirmName !== modalUser?.name">
                <span v-if="busy === 'delete'" class="loading loading-spinner loading-sm"></span>
                确认删除
              </button>
            </div>
          </form>
        </template>
      </div>
    </div>
  </Teleport>
  </section>
</template>
