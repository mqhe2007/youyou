<script setup>
import { computed, reactive, ref, watch } from 'vue';
import {
  AlertCircle,
  CheckCircle2,
  Eye,
  EyeOff,
  KeyRound,
  LogIn,
} from '@lucide/vue';
import HelpTooltip from './HelpTooltip.vue';

const props = defineProps({
  mode: {
    type: String,
    default: 'login',
  },
  busy: {
    type: Boolean,
    default: false,
  },
  notice: {
    type: Object,
    default: null,
  },
});

const emit = defineEmits(['login', 'setup']);

const loginForm = reactive({
  password: '',
});
const setupForm = reactive({
  setupToken: '',
  password: '',
  confirmPassword: '',
});
const localError = ref('');
const showPassword = ref(false);
const logoPath = '/admin/logo_mark.png';
const iconStrokeWidth = 1.8;

const isSetup = computed(() => props.mode === 'setup');
const pageTitle = computed(() =>
  isSetup.value ? '初始化' : '登录',
);
const pageDescription = computed(() =>
  isSetup.value
    ? '设置管理员密码，开始使用。'
    : '登录后管理照片库。',
);

watch(
  () => props.mode,
  () => {
    localError.value = '';
    showPassword.value = false;
    loginForm.password = '';
    setupForm.password = '';
    setupForm.confirmPassword = '';
  },
);

function submitLogin() {
  localError.value = '';
  emit('login', { password: loginForm.password });
}

function submitSetup() {
  localError.value = '';

  if (setupForm.password.length < 12) {
    localError.value = '管理员密码至少需要 12 个字符。';
    return;
  }

  if (setupForm.password !== setupForm.confirmPassword) {
    localError.value = '两次输入的密码不一致。';
    return;
  }

  emit('setup', {
    setupToken: setupForm.setupToken,
    password: setupForm.password,
  });
}
</script>

<template>
  <main class="auth-page">
    <header class="auth-brand" aria-label="柚柚相册">
      <div class="brand-lockup brand-lockup-large">
        <img class="brand-logo" :src="logoPath" alt="柚柚标志">
        <span>
          <strong>柚柚相册</strong>
          <small>轻松管理人生影相</small>
        </span>
      </div>
    </header>

    <section class="auth-panel">
      <div class="auth-form-wrap">
        <div class="auth-heading">
          <h2>{{ pageTitle }}</h2>
          <p>{{ pageDescription }}</p>
        </div>

        <div
          v-if="localError || notice?.message"
          class="alert auth-alert"
          :class="localError ? 'alert-error' : `alert-${notice.type || 'info'}`"
          role="alert"
        >
          <span class="alert-symbol" aria-hidden="true">
            <AlertCircle
              v-if="localError || notice?.type === 'error'"
              :size="17"
              :stroke-width="iconStrokeWidth"
            />
            <CheckCircle2 v-else :size="17" :stroke-width="iconStrokeWidth" />
          </span>
          <span>{{ localError || notice.message }}</span>
        </div>

        <form v-if="isSetup" class="auth-form" @submit.prevent="submitSetup">
          <label class="form-control">
            <span class="label-text label-with-help">
              <span>一次性初始化令牌</span>
              <HelpTooltip
                text="从服务端主机读取 bootstrap/setup-token 文件，只在首次初始化时使用。"
                label="查看初始化令牌说明"
              />
            </span>
            <input
              v-model.trim="setupForm.setupToken"
              class="input input-bordered"
              type="text"
              required
              autocomplete="one-time-code"
              placeholder="粘贴服务端生成的令牌"
            >
          </label>

          <label class="form-control">
            <span class="label-text">管理员密码</span>
            <span class="password-input">
              <input
                v-model="setupForm.password"
                class="input input-bordered"
                :type="showPassword ? 'text' : 'password'"
                required
                minlength="12"
                autocomplete="new-password"
                placeholder="至少 12 个字符"
              >
              <button
                class="password-toggle"
                type="button"
                :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                @click="showPassword = !showPassword"
              >
                <EyeOff v-if="showPassword" :size="17" :stroke-width="iconStrokeWidth" aria-hidden="true" />
                <Eye v-else :size="17" :stroke-width="iconStrokeWidth" aria-hidden="true" />
              </button>
            </span>
          </label>

          <label class="form-control">
            <span class="label-text">再次输入密码</span>
            <input
              v-model="setupForm.confirmPassword"
              class="input input-bordered"
              :type="showPassword ? 'text' : 'password'"
              required
              minlength="12"
              autocomplete="new-password"
              placeholder="再次确认管理员密码"
            >
          </label>

          <button class="btn btn-primary auth-submit" type="submit" :disabled="busy">
            <KeyRound v-if="!busy" :size="17" :stroke-width="iconStrokeWidth" aria-hidden="true" />
            <span v-if="busy" class="loading loading-spinner loading-sm"></span>
            {{ busy ? '正在初始化' : '完成初始化' }}
          </button>
        </form>

        <form v-else class="auth-form" @submit.prevent="submitLogin">
          <label class="form-control">
            <span class="label-text">管理员密码</span>
            <input
              v-model="loginForm.password"
              class="input input-bordered"
              type="password"
              required
              autocomplete="current-password"
              placeholder="输入管理员密码"
            >
          </label>

          <button class="btn btn-primary auth-submit" type="submit" :disabled="busy">
            <LogIn v-if="!busy" :size="17" :stroke-width="iconStrokeWidth" aria-hidden="true" />
            <span v-if="busy" class="loading loading-spinner loading-sm"></span>
            {{ busy ? '正在登录' : '进入管理工作台' }}
          </button>
        </form>
      </div>
    </section>
  </main>
</template>
