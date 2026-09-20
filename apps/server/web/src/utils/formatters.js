// 管理端共享格式化与任务/审计文案工具。

export function isTerminalJob(job) {
  return ['succeeded', 'failed', 'cancelled'].includes(job?.status);
}

export function isActiveJob(job) {
  return ['queued', 'running'].includes(job?.status);
}

export function formatBytes(value) {
  if (value == null) return '未知';
  const units = ['字节', 'KB', 'MB', 'GB', 'TB'];
  let amount = Number(value);
  let unit = 0;
  while (amount >= 1024 && unit < units.length - 1) {
    amount /= 1024;
    unit += 1;
  }
  return `${amount.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`;
}

export function formatNumber(value) {
  if (value == null) return '—';
  return new Intl.NumberFormat('zh-CN').format(Number(value));
}

export function formatTime(value) {
  if (value == null) return '—';
  return new Date(Number(value)).toLocaleString('zh-CN', {
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function jobKindLabel(kind) {
  return {
    scan: '媒体扫描',
    backup: '数据库备份',
    bootstrap: '客户端同步',
    download: '客户端下载',
  }[kind] || '后台任务';
}

export function jobStatusLabel(statusValue) {
  return {
    queued: '排队中',
    running: '进行中',
    interrupted: '已中断',
    succeeded: '已完成',
    failed: '失败',
    cancelled: '已取消',
  }[statusValue] || statusValue || '未知';
}

export function jobStatusClass(statusValue) {
  return {
    queued: 'status-pending',
    running: 'status-running',
    interrupted: 'status-warning',
    succeeded: 'status-success',
    failed: 'status-error',
    cancelled: 'status-muted',
  }[statusValue] || 'status-muted';
}

export function progressValue(job) {
  if (!job || job.total == null || Number(job.total) <= 0) return null;
  return Math.min(100, Math.round((Number(job.current) / Number(job.total)) * 100));
}

export function progressLabel(job) {
  if (!job) return '等待任务';
  const progress = progressValue(job);
  if (progress == null) {
    return job.status === 'running' ? '正在处理' : jobStatusLabel(job.status);
  }
  return `${progress}% · ${formatNumber(job.current)} / ${formatNumber(job.total)}`;
}

export function auditActionLabel(action) {
  return {
    'admin.setup': '完成管理员初始化',
    'admin.login': '管理员登录',
    'admin.logout': '管理员注销',
    'storage.update': '更新存储目录',
    'storage.test': '测试存储读写',
    'media_library.mkdir': '创建媒体文件夹',
    'media_library.move': '移动媒体',
    'media_library.delete_media': '删除媒体',
    'media_library.delete_folder': '删除媒体文件夹',
    'media_library.upload': '上传媒体',
    'scan.start': '开始媒体扫描',
    'scan.complete': '完成媒体扫描',
    'backup.create': '创建数据库备份',
    'backup.complete': '完成数据库备份',
    'device.pair': '设备完成连接',
    'device.revoke': '撤销设备权限',
    'device.rotate': '更新设备密钥',
    'pairing-code.create': '生成用户连接二维码',
    'user.create': '创建用户',
    'user.delete': '删除用户',
    'user.library.bind': '绑定用户媒体库',
  }[action] || action || '其他操作';
}

export function auditResultLabel(result) {
  return result === 'success' ? '成功' : result === 'failure' ? '失败' : result || '—';
}

export function jobMessage(job) {
  const message = job?.message;
  if (!message) {
    return job?.status === 'running' ? '服务端正在处理' : progressLabel(job);
  }

  const scanSummary = message.match(
    /^(?:cancelled: )?discovered=(\d+), indexed=(\d+), failed=(\d+)$/,
  );
  if (scanSummary) {
    const prefix = message.startsWith('cancelled:') ? '扫描已取消' : '扫描完成';
    return `${prefix}：发现 ${formatNumber(scanSummary[1])} 项，建立索引 ${formatNumber(scanSummary[2])} 项，失败 ${formatNumber(scanSummary[3])} 项`;
  }

  return {
    'backup queued': '备份正在排队',
    'backup created and verified': '备份已生成并完成校验',
    'backup cancelled': '备份已取消',
    'retry queued': '任务已重新排队',
    cancelled: '任务已取消',
    'cancelled after lease expired': '任务已取消',
    'lease expired; queued for recovery': '任务已排队等待恢复',
    'lease expired; retry limit reached': '任务失败，已达到重试次数上限',
  }[message] || (job?.kind === 'backup' ? '数据库备份任务' : '服务端后台任务');
}

export function jobErrorMessage(message) {
  if (!message) return '';
  return {
    'backup creation or verification failed': '备份生成或校验失败，请稍后重试。',
    'job lease expired': '任务执行时间过长，服务端已暂停本次处理。',
    'storage write probe failed': '存储写入测试失败。',
    'storage read probe failed': '存储读取测试失败。',
    'storage read probe returned unexpected data': '存储读回内容与预期不一致。',
  }[message] || '任务未能完成，请检查服务端日志。';
}

export function quickCheckLabel(value) {
  if (value == null) return '—';
  return value === 'ok' ? '检查通过' : '需要检查';
}
