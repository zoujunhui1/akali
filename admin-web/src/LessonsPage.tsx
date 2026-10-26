import { useCallback, useEffect, useState } from "react";
import { deleteLesson, lessonAction, listLessons, processLesson, updateVocab, waitForJob, type LessonRow } from "./api";
import { lessonSync } from "./format";

const SYNC_TEXT = { unknown: "—", missing: "未上传", synced: "已同步", differs: "和服务器不一致" } as const;

type Running = { label: string; note: string } | null;

export function LessonsPage() {
  const [rows, setRows] = useState<LessonRow[] | null>(null);
  const [serverError, setServerError] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [url, setUrl] = useState("");
  const [running, setRunning] = useState<Running>(null);
  const [result, setResult] = useState("");

  const refresh = useCallback(() => {
    listLessons()
      .then((r) => {
        setRows(r.lessons);
        setServerError(r.server_error);
        setError("");
      })
      .catch((e) => setError(e instanceof Error ? e.message : "读取失败"));
  }, []);
  useEffect(refresh, [refresh]);

  // Starts a job, shows its last log line while it runs, and reloads the list when it ends.
  async function run(label: string, start: () => Promise<{ id: number }>) {
    setResult("");
    setError("");
    setRunning({ label, note: "已加入队列…" });
    try {
      const { id } = await start();
      const { job, tail } = await waitForJob(id, 2000);
      setResult(job.status === "succeeded" ? `${label}：完成。${tail[tail.length - 1] ?? ""}` : `${label}：失败。${tail[tail.length - 1] ?? job.error ?? ""}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "启动失败");
    } finally {
      setRunning(null);
      refresh();
    }
  }

  async function remove(r: LessonRow) {
    if (!window.confirm(`从服务器删除 ${r.code} ${r.title}？手机同步后这一课会消失，原件会保留。`)) return;
    run(`删除 ${r.code}`, () => deleteLesson(r.code));
  }

  const busy = running !== null;
  return (
    <div className="lessons">
      <div className="toolbar"><h2>处理新课</h2></div>
      <div className="form">
        <input placeholder="贴 ELLLO 课程页面网址，例如 https://elllo.org/video/…" value={url} onChange={(e) => setUrl(e.target.value)} />
        <button disabled={busy || url.trim() === ""} onClick={() => run("处理新课", () => processLesson(url.trim())).then(() => setUrl(""))}>
          抓取、识别、上传
        </button>
        <p className="muted small">会依次做：下载音频和文稿、语音识别出每句时间、对生词、上传。一课大约几分钟。</p>
      </div>
      {running && <p className="muted">{running.label}：运行中，可以到“任务”页看日志…</p>}
      {result && <p className={result.includes("失败") ? "error" : "ok"}>{result}</p>}
      {error && <p className="error" role="alert">{error}</p>}
      {serverError && <p className="error">连不上第一台：{serverError}</p>}

      <div className="toolbar">
        <h2>听力课{rows ? `（${rows.length}）` : ""}</h2>
        <span className="spacer" />
        <button disabled={busy} onClick={() => run("更新全部词表", () => updateVocab([]))}>更新全部词表</button>
      </div>
      {rows && rows.length === 0 && <p className="muted">这里还没有课。</p>}
      {rows && rows.length > 0 && (
        <table>
          <thead><tr><th>编号</th><th>标题</th><th>时间戳</th><th>生词</th><th>服务器</th><th /></tr></thead>
          <tbody>
            {rows.map((r) => {
              const sync = lessonSync(r);
              return (
                <tr key={r.code}>
                  <td>{r.code}</td>
                  <td>{r.title}</td>
                  <td>{r.local.timestamps || "无"}</td>
                  <td>{r.local.vocab ?? "无"}</td>
                  <td><span className={`status ${sync === "synced" ? "succeeded" : sync === "unknown" ? "" : "failed"}`}>{SYNC_TEXT[sync]}</span></td>
                  <td className="actions">
                    <button className="link" disabled={busy} onClick={() => run(`重做时间戳 ${r.code}`, () => lessonAction(r.code, "redo"))}>重做时间戳</button>
                    <button className="link" disabled={busy} onClick={() => run(`上传 ${r.code}`, () => lessonAction(r.code, "upload"))}>重新上传</button>
                    <button className="link" disabled={busy || sync === "missing"} onClick={() => remove(r)}>删除</button>
                    <button className="link" disabled={busy} onClick={() => run(`更新词表 ${r.code}`, () => updateVocab([r.code]))}>更新词表</button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </div>
  );
}
