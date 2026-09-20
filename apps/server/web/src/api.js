const CSRF_COOKIE = 'youyou_admin_csrf';

const ERROR_MESSAGES = {
  unauthorized: '登录已失效，请重新登录。',
  forbidden: '当前会话没有执行这项操作的权限。',
  invalid_request: '提交的信息不完整或格式不正确。',
  conflict: '当前操作暂时无法进行，请先检查正在运行的任务。',
  storage_read_only: '存储目录为只读，无法执行写入操作。',
  storage_unavailable: '存储目录暂时不可用，请检查目录和权限。',
  invalid_path: '路径无效。',
  path_outside_root: '路径不能越出媒体根目录。',
  not_a_directory: '指定路径不是目录。',
  directory_not_empty: '文件夹非空，无法删除。',
  file_not_found: '找不到指定文件。',
  not_found: '找不到指定内容。',
  invalid_upload: '上传内容校验失败，请重试。',
  rate_limited: '尝试次数过多，请稍后再试。',
  internal_error: '服务端暂时无法完成操作，请稍后再试。',
};

function readCookie(name) {
  const prefix = `${name}=`;
  return document.cookie
    .split(';')
    .map((part) => part.trim())
    .find((part) => part.startsWith(prefix))
    ?.slice(prefix.length) || null;
}

export function getApiErrorMessage(error) {
  if (error?.code && ERROR_MESSAGES[error.code]) {
    return ERROR_MESSAGES[error.code];
  }

  if (error instanceof TypeError) {
    return '无法连接服务端，请确认服务正在运行。';
  }

  return error?.message || '操作未完成，请稍后再试。';
}

export function createAdminApi() {
  let csrfToken = readCookie(CSRF_COOKIE);

  async function request(path, options = {}) {
    const method = options.method || 'GET';
    const headers = new Headers(options.headers || {});
    let body = options.body;
    const isBinaryBody =
      typeof Blob !== 'undefined' && body instanceof Blob
      || typeof ArrayBuffer !== 'undefined' && body instanceof ArrayBuffer
      || typeof Uint8Array !== 'undefined' && body instanceof Uint8Array;

    if (body !== undefined && typeof body !== 'string' && !isBinaryBody) {
      headers.set('Content-Type', 'application/json');
      body = JSON.stringify(body);
    }

    if (method !== 'GET' && csrfToken) {
      headers.set('X-CSRF-Token', csrfToken);
    }

    const response = await fetch(path, {
      ...options,
      method,
      body,
      credentials: 'same-origin',
      headers,
    });
    const text = await response.text();
    let data = null;

    if (text) {
      try {
        data = JSON.parse(text);
      } catch {
        data = null;
      }
    }

    if (!response.ok) {
      const error = new Error(data?.message || `请求失败（${response.status}）`);
      error.code = data?.code || 'http_error';
      error.details = data?.details || null;
      throw error;
    }

    return data;
  }

  async function requestBlob(path, options = {}) {
    const method = options.method || 'GET';
    const headers = new Headers(options.headers || {});
    if (method !== 'GET' && csrfToken) {
      headers.set('X-CSRF-Token', csrfToken);
    }
    const response = await fetch(path, {
      ...options,
      method,
      credentials: 'same-origin',
      headers,
    });
    if (!response.ok) {
      let data = null;
      try {
        data = await response.json();
      } catch {
        data = null;
      }
      const error = new Error(data?.message || `请求失败（${response.status}）`);
      error.code = data?.code || 'http_error';
      error.details = data?.details || null;
      throw error;
    }
    return response.blob();
  }

  function setCsrfToken(value) {
    csrfToken = value || readCookie(CSRF_COOKIE);
  }

  function clearCsrfToken() {
    csrfToken = null;
  }

  return {
    request,
    requestBlob,
    setCsrfToken,
    clearCsrfToken,
  };
}
