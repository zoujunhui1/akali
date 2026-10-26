// Every call goes to our own address under /api; writes carry the header the backend requires.
export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

export async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    headers: { "Content-Type": "application/json", "X-Requested-With": "akali-admin" },
    body: body === undefined ? undefined : JSON.stringify(body),
    credentials: "same-origin",
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : {};
  if (!res.ok) throw new ApiError(res.status, data.error ?? `请求失败（${res.status}）`);
  return data as T;
}

export const me = () => call<{ user: string }>("GET", "/api/auth/me");
export const login = (username: string, password: string) =>
  call<{ user: string }>("POST", "/api/auth/login", { username, password });
export const logout = () => call<object>("POST", "/api/auth/logout");

// The backend's English reasons, in plain Chinese for the page.
export function loginMessage(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 401) return "用户名或密码不对";
    if (e.status === 429) return "输错次数太多，请过一会儿再试";
    if (e.status === 400) return "请填写用户名和密码";
  }
  return "登录失败，请稍后再试";
}

export type Job = {
  id: number;
  kind: string;
  title: string;
  heavy: boolean;
  status: "queued" | "running" | "succeeded" | "failed" | "cancelled" | "interrupted";
  error?: string;
  createdAt: string;
  startedAt?: string;
  finishedAt?: string;
};
export type LogLine = { id: number; at: string; line: string };

export const listJobs = () => call<{ jobs: Job[] }>("GET", "/api/jobs");
export const getJob = (id: number, after = 0) =>
  call<{ job: Job; logs: LogLine[] }>("GET", `/api/jobs/${id}?after=${after}`);
export const cancelJob = (id: number) => call<object>("POST", `/api/jobs/${id}/cancel`);
export const startSelfCheck = (heavy: boolean) => call<{ id: number }>("POST", "/api/jobs/selfcheck", { heavy });

// ---- articles ----
export type ServerArticle = { code: string; title: string; source: string; word_count: number };
export type DraftItem = { title: string; paragraphs: number; sentences: number; words: number; first: string };
export type Draft = { source: string; articles: DraftItem[] };

export const listArticles = () => call<{ articles: ServerArticle[] }>("GET", "/api/articles");
export const deleteArticle = (code: string) => call<object>("DELETE", `/api/articles/${code}`);
export const previewArticle = (kind: "url" | "text", target: string, title: string) =>
  call<{ id: number }>("POST", "/api/articles/preview", { kind, target, title });
export const getDraft = (id: number) => call<Draft>("GET", `/api/articles/drafts/${id}`);
export const uploadDraft = (draft: number, title: string) =>
  call<{ id: number }>("POST", "/api/articles/upload", { draft, title });

export async function previewArticleFile(file: File, title: string): Promise<{ id: number }> {
  const form = new FormData();
  form.append("file", file);
  form.append("title", title);
  const res = await fetch("/api/articles/preview", {
    method: "POST",
    headers: { "X-Requested-With": "akali-admin" },
    body: form,
    credentials: "same-origin",
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : {};
  if (!res.ok) throw new ApiError(res.status, data.error ?? `请求失败（${res.status}）`);
  return data as { id: number };
}

// Polls a job until it is no longer queued or running; returns its final state and the last log lines.
export async function waitForJob(id: number, delayMs = 1000): Promise<{ job: Job; tail: string[] }> {
  const lines: string[] = [];
  let after = 0;
  for (;;) {
    const r = await getJob(id, after);
    for (const l of r.logs) {
      lines.push(l.line);
      after = l.id;
    }
    if (r.job.status !== "queued" && r.job.status !== "running") {
      if (r.logs.length === 0) return { job: r.job, tail: lines.slice(-5) };
    } else {
      await new Promise((ok) => setTimeout(ok, delayMs));
    }
  }
}

// ---- listening lessons ----
export type LocalState = { audio: boolean; transcript: boolean; timestamps: number; vocab: number | null };
export type ServerState = { present: boolean; audio: boolean; timestamps: number; vocab: number | null };
export type LessonRow = { code: string; title: string; local: LocalState; server: ServerState | null };

export const listLessons = () => call<{ lessons: LessonRow[]; server_error: string | null }>("GET", "/api/lessons");
export const processLesson = (url: string) => call<{ id: number }>("POST", "/api/lessons/process", { url });
export const lessonAction = (code: string, action: "redo" | "upload") =>
  call<{ id: number }>("POST", `/api/lessons/${code}/${action}`, {});
export const deleteLesson = (code: string) => call<{ id: number }>("DELETE", `/api/lessons/${code}`);
export const updateVocab = (codes: string[]) => call<{ id: number }>("POST", "/api/lessons/vocab", { codes });

// ---- offline dictionary ----
export type DictStatus = {
  local: { size: number; modified: number; sha256: string } | null;
  server: { size: number; sha256: string } | null;
  server_error: string | null;
};
export const dictStatus = () => call<DictStatus>("GET", "/api/dict");
export const dictJob = (action: "build" | "compare" | "upload") => call<{ id: number }>("POST", `/api/dict/${action}`, {});
