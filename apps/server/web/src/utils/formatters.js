// 管理端共享格式化与任务/审计文案工具。

export function isTerminalJob(job) {
  return ['succeeded', 'failed', 'cancelled'].includes(job?.status);
}

export function isActiveJob(job) {
  return ['queued', 'running', 'interrupted'].includes(job?.status);
}

export function runningJobOf(items) {
  return (items || []).find((job) => job?.status === 'running') || null;
}

// worker 单线程按 created_at 先进先出取任务，等待队列展示必须同序。
export function waitingJobs(items) {
  return (items || [])
    .filter((job) => ['queued', 'interrupted'].includes(job?.status))
    .sort((a, b) => Number(a.createdAt) - Number(b.createdAt));
}

// 执行耗时：只算真正开始执行之后的部分，不含排队等待。
// 升级前落库的老记录没有 startedAt，返回 null 交给调用方显示为未知，
// 不要用 createdAt 顶替——那正好会把排队时间算回来。
export function jobRunDurationMs(job) {
  if (job?.startedAt == null || job?.finishedAt == null) return null;
  return Math.max(0, Number(job.finishedAt) - Number(job.startedAt));
}

// 正在运行的任务已跑了多久。起点由服务端在 set_job_running 落库，
// 因此中途刷新页面也准，不需要前端自己记观测时刻。
export function jobElapsedMs(job, now) {
  if (job?.status !== 'running' || job?.startedAt == null) return null;
  return Math.max(0, Number(now) - Number(job.startedAt));
}

export function jobScopeLabel(job) {
  if (job?.kind !== 'scan') return '';
  return job.checkpoint?.scopePath || '全部';
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

export function formatDuration(ms) {
  if (ms == null || Number.isNaN(Number(ms)) || Number(ms) < 0) return '—';
  const totalSeconds = Math.round(Number(ms) / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (hours > 0) return `${hours} 小时 ${minutes} 分`;
  if (minutes > 0) return `${minutes} 分 ${seconds} 秒`;
  return `${seconds} 秒`;
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

// 终态任务若 message 仍停在入队占位文案（服务端显式取消排队任务时不改写 message），
// 与「已取消」状态并列会得到「已取消 / 备份正在排队」这种自相矛盾的结果列。
const PRE_START_MESSAGES = new Set(['backup queued', 'retry queued', 'queued']);

export function jobOutcomeLabel(job) {
  if (isTerminalJob(job) && PRE_START_MESSAGES.has(job?.message)) return '未开始执行';
  return jobMessage(job);
}

export function jobMessage(job) {
  const message = job?.message;
  if (!message) {
    return job?.status === 'running' ? '服务端正在处理' : progressLabel(job);
  }

  const scanProgress = message.match(/^discovered=(\d+), indexed=(\d+)$/);
  if (scanProgress) {
    return `已发现 ${formatNumber(scanProgress[1])} 项，已建立索引 ${formatNumber(scanProgress[2])} 项`;
  }

  const scanSummary = message.match(
    /^(?:cancelled: )?discovered=(\d+), indexed=(\d+), failed=(\d+)(?:, skipped=(\d+))?$/,
  );
  if (scanSummary) {
    const prefix = message.startsWith('cancelled:') ? '扫描已取消' : '扫描完成';
    const skipped = scanSummary[4] ? `，跳过 ${formatNumber(scanSummary[4])} 项` : '';
    return `${prefix}：发现 ${formatNumber(scanSummary[1])} 项，建立索引 ${formatNumber(scanSummary[2])} 项，失败 ${formatNumber(scanSummary[3])} 项${skipped}`;
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
  }[message] || (job?.kind === 'backup' ? '数据库备份任务' : jobKindLabel(job?.kind));
}

// 扫描作业的跳过/失败明细（来自作业检查点；运行中的扫描还没有终态明细）。
export function jobScanReport(job) {
  const checkpoint = job?.checkpoint;
  if (!checkpoint || checkpoint.kind !== 'scan' || !['completed', 'interrupted'].includes(checkpoint.phase)) return null;
  const failures = Array.isArray(checkpoint.failures) ? checkpoint.failures : [];
  const skippedUnsupported = Number(checkpoint.skippedUnsupported || 0);
  const skippedIgnored = Number(checkpoint.skippedIgnored || 0);
  const skippedFailed = Number(checkpoint.skippedFailed || 0);
  const retried = Number(checkpoint.retried || 0);
  const failed = Number(checkpoint.failed || 0);
  const resumedFromJobId = checkpoint.resumedFromJobId || null;
  if (!failures.length && !failed && !skippedUnsupported && !skippedIgnored && !skippedFailed && !retried && !resumedFromJobId) {
    return null;
  }
  return {
    failures,
    failed,
    skippedUnsupported,
    skippedIgnored,
    skippedFailed,
    retried,
    scopePath: checkpoint.scopePath || '',
    resumedFromJobId,
    resumedAtDiscovered: Number(checkpoint.resumedAtDiscovered || 0),
    resumedAtIndexed: Number(checkpoint.resumedAtIndexed || 0),
    discovered: Number(checkpoint.discovered || 0),
    indexed: Number(checkpoint.indexed || 0),
  };
}

export function jobScanSkipSummary(report) {
  if (!report) return '';
  const parts = [];
  if (report.resumedFromJobId) {
    parts.push(`检查点续跑：此前已处理 ${formatNumber(report.resumedAtIndexed)} 项，累计 ${formatNumber(report.indexed)} 项`);
  }
  if (report.skippedUnsupported) parts.push(`不支持格式 ${formatNumber(report.skippedUnsupported)}`);
  if (report.skippedIgnored) parts.push(`垃圾文件 ${formatNumber(report.skippedIgnored)}`);
  if (report.skippedFailed) parts.push(`已知失败 ${formatNumber(report.skippedFailed)}`);
  if (report.failed) parts.push(`本次失败 ${formatNumber(report.failed)}`);
  if (report.retried) parts.push(`锁竞争重试 ${formatNumber(report.retried)}`);
  return parts.join(' · ');
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

// lastError 是历史痕迹：任务被服务端重启打断后自动恢复成功，它也不会被清空，
// 于是「已完成」「进行中」底下会挂出一行「任务未能完成」的红字。
// 只在任务确实没跑起来的时候展示——失败、被中断、被取消。
export function jobShowsError(job) {
  if (!job?.lastError) return false;
  return !['running', 'succeeded'].includes(job.status);
}

export function quickCheckLabel(value) {
  if (value == null) return '—';
  return value === 'ok' ? '检查通过' : '需要检查';
}
