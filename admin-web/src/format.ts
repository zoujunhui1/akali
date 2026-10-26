import type { Job, LessonRow } from "./api";

const TEXT: Record<Job["status"], string> = {
  queued: "排队中",
  running: "运行中",
  succeeded: "成功",
  failed: "失败",
  cancelled: "已取消",
  interrupted: "中断",
};

export const statusText = (s: Job["status"]) => TEXT[s] ?? s;

// queued and running jobs can still change, so the page keeps refreshing them
export const isActive = (s: Job["status"]) => s === "queued" || s === "running";

// "3 秒", "2 分 5 秒"; a job that has not finished is measured up to `now`
export function duration(job: Pick<Job, "startedAt" | "finishedAt">, now = Date.now()): string {
  if (!job.startedAt) return "—";
  const end = job.finishedAt ? Date.parse(job.finishedAt) : now;
  const secs = Math.max(0, Math.round((end - Date.parse(job.startedAt)) / 1000));
  if (secs < 60) return `${secs} 秒`;
  if (secs < 3600) return `${Math.floor(secs / 60)} 分 ${secs % 60} 秒`;
  return `${Math.floor(secs / 3600)} 小时 ${Math.floor((secs % 3600) / 60)} 分`;
}

export function clock(iso: string): string {
  return new Date(iso).toLocaleString("zh-CN", { hour12: false });
}

// How a lesson on the server compares with the folder: not there, the same, or different.
export function lessonSync(row: Pick<LessonRow, "local" | "server">): "unknown" | "missing" | "synced" | "differs" {
  const s = row.server;
  if (!s) return "unknown";
  if (!s.present) return "missing";
  const same = s.audio === row.local.audio && s.timestamps === row.local.timestamps && (s.vocab ?? 0) === (row.local.vocab ?? 0);
  return same ? "synced" : "differs";
}
