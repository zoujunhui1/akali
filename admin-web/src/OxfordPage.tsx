import { useCallback, useEffect, useState } from "react";
import { listTopics, oxfordJob, waitForJob, type Topic } from "./api";

const SERVER_TEXT = { missing: "未上传", synced: "已同步", differs: "和服务器不一致" } as const;

export function OxfordPage() {
  const [topics, setTopics] = useState<Topic[] | null>(null);
  const [serverError, setServerError] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [onlyDiff, setOnlyDiff] = useState(false);
  const [running, setRunning] = useState("");
  const [result, setResult] = useState("");

  const refresh = useCallback(() => {
    listTopics()
      .then((r) => {
        setTopics(r.topics);
        setServerError(r.server_error);
        setError("");
      })
      .catch((e) => setError(e instanceof Error ? e.message : "读取失败"));
  }, []);
  useEffect(refresh, [refresh]);

  async function run(label: string, action: "fix" | "upload", codes: string[]) {
    setRunning(label);
    setResult("");
    setError("");
    try {
      const { id } = await oxfordJob(action, codes);
      const { job, tail } = await waitForJob(id, 2000);
      setResult(`${label}：${job.status === "succeeded" ? "完成。" : "失败。"}${tail[tail.length - 1] ?? job.error ?? ""}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "启动失败");
    } finally {
      setRunning("");
      refresh();
    }
  }

  const shown = (topics ?? []).filter((t) => !onlyDiff || t.server !== "synced");
  const synced = (topics ?? []).filter((t) => t.server === "synced").length;
  const busy = running !== "";
  const differing = (topics ?? []).filter((t) => t.server === "differs" || t.server === "missing").map((t) => t.code);
  return (
    <div className="oxford">
      <div className="toolbar"><h2>牛津图解词典{topics ? `（${topics.length} 个主题，${synced} 个已同步）` : ""}</h2></div>
      <p className="muted small">主题的页面图、发音和词条已经做好，这里只管：应用词条修正、把有变化的主题上传到服务器。重新识别和整本重做不在这里。</p>
      {error && <p className="error" role="alert">{error}</p>}
      {serverError && <p className="error">连不上第一台：{serverError}</p>}
      {result && <p className={result.includes("失败") ? "error" : "ok"}>{result}</p>}
      {running && <p className="muted">{running}中，可以到“任务”页看日志…</p>}
      <div className="toolbar">
        <button disabled={busy || differing.length === 0} onClick={() => run("上传有变化的主题", "upload", differing)}>
          上传有变化的主题{differing.length > 0 ? `（${differing.length}）` : ""}
        </button>
        <label className="inline"><input type="checkbox" checked={onlyDiff} onChange={(e) => setOnlyDiff(e.target.checked)} /> 只看和服务器不一致的</label>
      </div>
      {topics && shown.length === 0 && <p className="muted">{onlyDiff ? "没有不一致的主题。" : "还没有主题。"}</p>}
      {shown.length > 0 && (
        <table>
          <thead><tr><th>编号</th><th>主题</th><th>词条</th><th>缺号</th><th>修正</th><th>服务器</th><th /></tr></thead>
          <tbody>
            {shown.map((t) => (
              <tr key={t.code}>
                <td>{t.code}</td>
                <td>{t.title}</td>
                <td>{t.entries}</td>
                <td>{t.gaps || "—"}</td>
                <td>{t.fixes || "—"}</td>
                <td><span className={`status ${t.server === "synced" ? "succeeded" : t.server ? "failed" : ""}`}>{t.server ? SERVER_TEXT[t.server] : "—"}</span></td>
                <td className="actions">
                  {t.fixes > 0 && <button className="link" disabled={busy} onClick={() => run(`应用修正 ${t.code}`, "fix", [t.code])}>应用修正</button>}
                  <button className="link" disabled={busy} onClick={() => run(`上传 ${t.code}`, "upload", [t.code])}>上传</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
