import { useCallback, useEffect, useState } from "react";
import { dictJob, dictStatus, waitForJob, type DictStatus } from "./api";

const mb = (n: number) => `${(n / 1048576).toFixed(1)} MB`;

export function DictPage() {
  const [status, setStatus] = useState<DictStatus | null>(null);
  const [error, setError] = useState("");
  const [running, setRunning] = useState("");
  const [log, setLog] = useState<string[]>([]);
  const [failed, setFailed] = useState(false);

  const refresh = useCallback(() => {
    dictStatus()
      .then((s) => {
        setStatus(s);
        setError("");
      })
      .catch((e) => setError(e instanceof Error ? e.message : "读取失败"));
  }, []);
  useEffect(refresh, [refresh]);

  async function run(label: string, action: "build" | "compare" | "upload") {
    setRunning(label);
    setLog([]);
    setError("");
    try {
      const { id } = await dictJob(action);
      const { job, tail } = await waitForJob(id, 2000);
      setFailed(job.status !== "succeeded");
      setLog(tail);
    } catch (e) {
      setError(e instanceof Error ? e.message : "启动失败");
    } finally {
      setRunning("");
      refresh();
    }
  }

  const same = status?.local && status.server && status.local.sha256 === status.server.sha256;
  return (
    <div className="dict">
      <div className="toolbar"><h2>离线字典</h2></div>
      <p className="muted small">手机阅读时查词用的字典，由 ECDICT 原始数据裁出常用词。手机在“我的”页下载。</p>
      {error && <p className="error" role="alert">{error}</p>}
      {status && (
        <table>
          <tbody>
            <tr><th>这里做好的</th><td>{status.local ? `${mb(status.local.size)}，${new Date(status.local.modified * 1000).toLocaleString("zh-CN", { hour12: false })}` : "还没有"}</td></tr>
            <tr><th>服务器上的</th><td>{status.server ? mb(status.server.size) : status.server_error ? `读取失败：${status.server_error}` : "还没有"}</td></tr>
            <tr><th>文件是否相同</th><td>{same ? "逐字节相同" : "不同（内容可能还是一样，点“对比”看逐行结果）"}</td></tr>
          </tbody>
        </table>
      )}
      <div className="toolbar">
        <button disabled={running !== ""} onClick={() => run("重新制作", "build")}>重新制作</button>
        <button disabled={running !== ""} onClick={() => run("对比", "compare")}>和服务器对比</button>
        <button disabled={running !== ""} onClick={() => run("上传", "upload")}>上传到服务器</button>
      </div>
      <p className="muted small">“上传”在内容和服务器一致时不会上传，手机也就不会白白重新下载。</p>
      {running && <p className="muted">{running}中…</p>}
      {log.length > 0 && <pre className={failed ? "log error" : "log"}>{log.join("\n")}</pre>}
    </div>
  );
}
