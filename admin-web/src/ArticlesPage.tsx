import { useCallback, useEffect, useState } from "react";
import {
  deleteArticle, getDraft, listArticles, previewArticle, previewArticleFile, uploadDraft, waitForJob,
  type Draft, type ServerArticle,
} from "./api";

type Mode = "url" | "text" | "file";
type Step =
  | { kind: "idle" }
  | { kind: "working"; what: string }
  | { kind: "draft"; id: number; draft: Draft }
  | { kind: "done"; message: string }
  | { kind: "failed"; message: string };

export function ArticlesPage() {
  const [list, setList] = useState<ServerArticle[] | null>(null);
  const [listError, setListError] = useState("");
  const [mode, setMode] = useState<Mode>("url");
  const [target, setTarget] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [title, setTitle] = useState("");
  const [step, setStep] = useState<Step>({ kind: "idle" });

  const refresh = useCallback(() => {
    listArticles()
      .then((r) => {
        setList(r.articles);
        setListError("");
      })
      .catch((e) => setListError(e instanceof Error ? e.message : "读取失败"));
  }, []);
  useEffect(refresh, [refresh]);

  const busy = step.kind === "working";
  const ready = mode === "file" ? file !== null : target.trim() !== "";

  async function preview() {
    setStep({ kind: "working", what: "正在整理文章…" });
    try {
      const { id } =
        mode === "file" && file ? await previewArticleFile(file, title) : await previewArticle(mode === "text" ? "text" : "url", target, title);
      const { job, tail } = await waitForJob(id);
      if (job.status !== "succeeded") {
        setStep({ kind: "failed", message: tail[tail.length - 1] ?? "整理失败" });
        return;
      }
      setStep({ kind: "draft", id, draft: await getDraft(id) });
    } catch (e) {
      setStep({ kind: "failed", message: e instanceof Error ? e.message : "整理失败" });
    }
  }

  async function upload(id: number, newTitle: string) {
    setStep({ kind: "working", what: "正在上传…" });
    try {
      const { id: jobId } = await uploadDraft(id, newTitle);
      const { job, tail } = await waitForJob(jobId);
      if (job.status !== "succeeded") {
        setStep({ kind: "failed", message: tail[tail.length - 1] ?? "上传失败" });
        return;
      }
      setStep({ kind: "done", message: tail.filter((l) => l.startsWith("已上传")).join("、") || "已上传" });
      setTarget("");
      setFile(null);
      setTitle("");
      refresh();
    } catch (e) {
      setStep({ kind: "failed", message: e instanceof Error ? e.message : "上传失败" });
    }
  }

  async function remove(a: ServerArticle) {
    if (!window.confirm(`从服务器删除《${a.title}》（${a.code}）？手机同步后也会消失。`)) return;
    try {
      await deleteArticle(a.code);
      refresh();
    } catch (e) {
      setListError(e instanceof Error ? e.message : "删除失败");
    }
  }

  return (
    <div className="articles">
      <div className="toolbar"><h2>添加文章</h2></div>
      <div className="modes">
        {(["url", "text", "file"] as Mode[]).map((m) => (
          <button key={m} className={mode === m ? "tab on" : "tab"} disabled={busy} onClick={() => setMode(m)}>
            {{ url: "网址", text: "粘贴文字", file: "文件" }[m]}
          </button>
        ))}
      </div>
      <div className="form">
        {mode === "url" && <input placeholder="https://…" value={target} onChange={(e) => setTarget(e.target.value)} />}
        {mode === "text" && <textarea rows={8} placeholder="把文章粘贴到这里" value={target} onChange={(e) => setTarget(e.target.value)} />}
        {mode === "file" && (
          <input type="file" accept=".pdf,.epub,.txt,.md" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
        )}
        <input placeholder="标题（可不填，自动识别）" value={title} onChange={(e) => setTitle(e.target.value)} />
        <button disabled={busy || !ready} onClick={preview}>整理并预览</button>
      </div>

      {step.kind === "working" && <p className="muted">{step.what}</p>}
      {step.kind === "failed" && <p className="error" role="alert">{step.message}</p>}
      {step.kind === "done" && <p className="ok">{step.message}</p>}
      {step.kind === "draft" && <DraftCard id={step.id} draft={step.draft} onUpload={upload} onDrop={() => setStep({ kind: "idle" })} />}

      <div className="toolbar"><h2>服务器上的文章</h2></div>
      {listError && <p className="error" role="alert">{listError}</p>}
      {list && list.length === 0 && <p className="muted">还没有文章。</p>}
      {list && list.length > 0 && (
        <table>
          <thead><tr><th>编号</th><th>标题</th><th>词数</th><th /></tr></thead>
          <tbody>
            {list.map((a) => (
              <tr key={a.code}>
                <td>{a.code}</td>
                <td>{a.title}{a.source && <div className="muted small">{a.source}</div>}</td>
                <td>{a.word_count}</td>
                <td><button className="link" onClick={() => remove(a)}>删除</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function DraftCard({ id, draft, onUpload, onDrop }: { id: number; draft: Draft; onUpload: (id: number, title: string) => void; onDrop: () => void }) {
  const single = draft.articles.length === 1;
  const [title, setTitle] = useState(draft.articles[0]?.title ?? "");
  return (
    <section className="card wide">
      <h3>预览（还没有上传）</h3>
      {single ? (
        <label>标题<input value={title} onChange={(e) => setTitle(e.target.value)} /></label>
      ) : (
        <p className="muted">共 {draft.articles.length} 篇，会按章节分别上传。</p>
      )}
      <ul className="drafts">
        {draft.articles.map((a, i) => (
          <li key={i}>
            {!single && <strong>{a.title}</strong>}
            <div className="muted small">{a.paragraphs} 段 · {a.sentences} 句 · {a.words} 词</div>
            <div className="small">{a.first}</div>
          </li>
        ))}
      </ul>
      <div className="toolbar">
        <button onClick={() => onUpload(id, single ? title.trim() : "")}>上传到服务器</button>
        <button className="link" onClick={onDrop}>放弃</button>
      </div>
    </section>
  );
}
