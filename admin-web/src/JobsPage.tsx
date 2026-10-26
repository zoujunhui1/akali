import { useCallback, useEffect, useRef, useState } from "react";
import { cancelJob, getJob, listJobs, startSelfCheck, type Job, type LogLine } from "./api";
import { clock, duration, isActive, statusText } from "./format";

export function JobsPage() {
  const [jobs, setJobs] = useState<Job[]>([]);
  const [selected, setSelected] = useState<number | null>(null);
  const [error, setError] = useState("");

  const refresh = useCallback(() => {
    listJobs()
      .then((r) => setJobs(r.jobs))
      .catch(() => setError("读取任务列表失败"));
  }, []);

  useEffect(() => {
    refresh();
    const t = setInterval(refresh, 3000);
    return () => clearInterval(t);
  }, [refresh]);

  async function start(heavy: boolean) {
    setError("");
    try {
      const { id } = await startSelfCheck(heavy);
      setSelected(id);
      refresh();
    } catch {
      setError("启动失败");
    }
  }

  return (
    <div className="jobs">
      <div className="toolbar">
        <h2>任务</h2>
        <span className="spacer" />
        <button onClick={() => start(false)}>自检任务（轻）</button>
        <button onClick={() => start(true)}>自检任务（重）</button>
      </div>
      {error && <p className="error" role="alert">{error}</p>}
      {jobs.length === 0 ? (
        <p className="muted">还没有任务。点右上角的自检任务，可以看看队列怎么工作。</p>
      ) : (
        <table>
          <thead>
            <tr><th>#</th><th>任务</th><th>状态</th><th>开始</th><th>用时</th></tr>
          </thead>
          <tbody>
            {jobs.map((j) => (
              <tr key={j.id} className={j.id === selected ? "on" : ""} onClick={() => setSelected(j.id)}>
                <td>{j.id}</td>
                <td>{j.title}{j.heavy && <span className="tag">重</span>}</td>
                <td><span className={`status ${j.status}`}>{statusText(j.status)}</span></td>
                <td>{j.startedAt ? clock(j.startedAt) : "—"}</td>
                <td>{duration(j)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {selected !== null && <Detail id={selected} onChanged={refresh} />}
    </div>
  );
}

// Shows one job with its log; asks only for log lines newer than the ones already shown.
function Detail({ id, onChanged }: { id: number; onChanged: () => void }) {
  const [job, setJob] = useState<Job | null>(null);
  const [lines, setLines] = useState<LogLine[]>([]);
  const last = useRef(0);
  const box = useRef<HTMLPreElement>(null);

  useEffect(() => {
    setJob(null);
    setLines([]);
    last.current = 0;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout>;
    async function poll() {
      try {
        const r = await getJob(id, last.current);
        if (stopped) return;
        setJob(r.job);
        if (r.logs.length) {
          last.current = r.logs[r.logs.length - 1].id;
          setLines((old) => [...old, ...r.logs]);
        }
        if (!isActive(r.job.status) && r.logs.length === 0) return; // finished and nothing more to read
      } catch {
        /* try again on the next round */
      }
      if (!stopped) timer = setTimeout(poll, 2000);
    }
    poll();
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, [id]);

  useEffect(() => {
    box.current?.scrollTo({ top: box.current.scrollHeight });
  }, [lines]);

  async function cancel() {
    await cancelJob(id).catch(() => {});
    onChanged();
  }

  if (!job) return <p className="muted">加载中…</p>;
  return (
    <section className="detail">
      <div className="toolbar">
        <h3>#{job.id} {job.title}</h3>
        <span className={`status ${job.status}`}>{statusText(job.status)}</span>
        <span className="spacer" />
        {isActive(job.status) && <button className="danger" onClick={cancel}>取消</button>}
      </div>
      {job.error && <p className="error">失败原因：{job.error}</p>}
      <pre ref={box} className="log">
        {lines.length === 0 ? "（还没有日志）" : lines.map((l) => `${clock(l.at).slice(-8)}  ${l.line}`).join("\n")}
      </pre>
    </section>
  );
}
