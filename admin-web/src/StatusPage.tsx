import { useCallback, useEffect, useState } from "react";
import { getStatus, type Status } from "./api";
import { bytes } from "./format";

export function StatusPage() {
  const [status, setStatus] = useState<Status | null>(null);
  const [error, setError] = useState("");

  const refresh = useCallback(() => {
    getStatus()
      .then((s) => {
        setStatus(s);
        setError("");
      })
      .catch((e) => setError(e instanceof Error ? e.message : "读取失败"));
  }, []);
  useEffect(() => {
    refresh();
    const t = setInterval(refresh, 15000);
    return () => clearInterval(t);
  }, [refresh]);

  const s = status?.server;
  return (
    <div className="status-page">
      <div className="toolbar">
        <h2>服务器状态</h2>
        <span className="spacer" />
        <button onClick={refresh}>刷新</button>
      </div>
      {error && <p className="error" role="alert">{error}</p>}
      {status && (
        <table>
          <tbody>
            <tr>
              <th>第一台（手机用的服务）</th>
              <td>
                <span className={`status ${status.online ? "succeeded" : "failed"}`}>{status.online ? "在线" : "连不上"}</span>
                {status.online && status.latency_ms !== undefined && <span className="muted small"> 响应 {status.latency_ms} 毫秒</span>}
                {status.error && <div className="error">{status.error}</div>}
              </td>
            </tr>
            {s && (
              <>
                <tr><th>听力课</th><td>{s.lessons} 课</td></tr>
                <tr><th>牛津词汇</th><td>{s.vocab_topics} 个主题，收藏 {s.favorites} 个词，已会 {s.mastered} 个</td></tr>
                <tr><th>阅读</th><td>{s.articles} 篇文章，生词本 {s.notebook} 个词</td></tr>
                <tr><th>离线字典</th><td>{s.dict ? bytes(s.dict.size) : "还没有"}</td></tr>
                <tr>
                  <th>AI 今天用量</th>
                  <td>{s.ai.configured ? `${s.ai.used_today} / ${s.ai.limit} 次` : "还没有配置 AI"}</td>
                </tr>
                <tr><th>第一台的文件</th><td>{bytes(s.audio_bytes)}{s.disk_free_bytes !== undefined && `，磁盘还剩 ${bytes(s.disk_free_bytes)}`}</td></tr>
                <tr><th>数据版本号</th><td>{s.rev}（每次内容变化加一，手机靠它同步）</td></tr>
              </>
            )}
            {status.local_disk_free_bytes !== undefined && (
              <tr><th>第二台（后台管理机）</th><td>磁盘还剩 {bytes(status.local_disk_free_bytes)}</td></tr>
            )}
          </tbody>
        </table>
      )}
    </div>
  );
}
