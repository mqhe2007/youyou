<script setup>
import { onMounted, ref } from 'vue';
import { createAdminApi, getApiErrorMessage } from './api';
import AuthView from './components/AuthView.vue';
import DashboardView from './components/DashboardView.vue';

const api = createAdminApi();
const view = ref('checking');
const busy = ref(false);
const notice = ref(null);
const logoPath = '/admin/logo_mark.png';

async function checkSetup() {
  try {
    const result = await api.request('/api/v1/admin/setup');
    if (!result.initialized) {
      view.value = 'setup';
      return;
    }

    try {
      await api.request('/api/v1/admin/session');
      view.value = 'dashboard';
    } catch (error) {
      view.value = 'login';
      if (error?.code !== 'unauthorized') {
        notice.value = {
          type: 'error',
          message: getApiErrorMessage(error),
        };
      }
    }
  } catch (error) {
    view.value = 'login';
    notice.value = {
      type: 'error',
      message: getApiErrorMessage(error),
    };
  }
}

async function submitSetup({ setupToken, password }) {
  busy.value = true;
  notice.value = null;
  try {
    await api.request('/api/v1/admin/setup', {
      method: 'POST',
      body: { setupToken, password },
    });
    view.value = 'login';
    notice.value = {
      type: 'success',
      message: '初始化完成，请使用刚设置的密码登录。',
    };
  } catch (error) {
    notice.value = {
      type: 'error',
      message: error?.code === 'unauthorized'
        ? '初始化令牌无效或已失效，请重新获取。'
        : getApiErrorMessage(error),
    };
  } finally {
    busy.value = false;
  }
}

async function submitLogin({ password }) {
  busy.value = true;
  notice.value = null;
  try {
    const session = await api.request('/api/v1/admin/session', {
      method: 'POST',
      body: { password },
    });
    api.setCsrfToken(session?.csrfToken);
    view.value = 'dashboard';
  } catch (error) {
    notice.value = {
      type: 'error',
      message: getApiErrorMessage(error),
    };
  } finally {
    busy.value = false;
  }
}

function returnToLogin(message = '') {
  api.clearCsrfToken();
  view.value = 'login';
  notice.value = message
    ? {
        type: 'error',
        message,
      }
    : null;
}

onMounted(() => {
  void checkSetup();
});
</script>

<template>
  <div v-if="view === 'checking'" class="app-loading">
    <img class="brand-logo" :src="logoPath" alt="柚柚标志">
    <span class="loading loading-spinner loading-sm"></span>
    <span>正在连接服务端</span>
  </div>

  <AuthView
    v-else-if="view === 'setup' || view === 'login'"
    :mode="view"
    :busy="busy"
    :notice="notice"
    @setup="submitSetup"
    @login="submitLogin"
  />

  <DashboardView
    v-else
    :api="api"
    @logout="returnToLogin()"
    @session-expired="returnToLogin('登录已失效，请重新登录。')"
  />
</template>
