CREATE UNIQUE INDEX IF NOT EXISTS jobs_active_scan_idx
    ON jobs (kind)
    WHERE kind = 'scan' AND status IN ('queued', 'running', 'interrupted');
