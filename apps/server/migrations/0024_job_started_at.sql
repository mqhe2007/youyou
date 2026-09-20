-- 任务首次进入 running 的时刻，由 set_job_running 用 COALESCE 写入：
-- 中断恢复后再次运行不改写，保留最初的执行起点。
-- 人工重试（retry_job）视为新的执行周期，会清空该列。
-- 目的：把「排队等待」与「实际执行」分开——执行耗时 = finished_at - started_at。
-- 历史行没有该值，保持 NULL（客户端应显示为未知，而不是拿含排队的差来冒充）。
ALTER TABLE jobs ADD COLUMN started_at INTEGER;
