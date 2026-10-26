import { useEffect, useState } from "react";
import { ArticlesPage } from "./ArticlesPage";
import { DictPage } from "./DictPage";
import { JobsPage } from "./JobsPage";
import { LessonsPage } from "./LessonsPage";
import { login, loginMessage, logout, me } from "./api";

type State = { kind: "loading" } | { kind: "out" } | { kind: "in"; user: string };

export function App() {
  const [state, setState] = useState<State>({ kind: "loading" });

  useEffect(() => {
    me()
      .then((r) => setState({ kind: "in", user: r.user }))
      .catch(() => setState({ kind: "out" }));
  }, []);

  if (state.kind === "loading") return <main className="center">加载中…</main>;
  if (state.kind === "out") return <Login onDone={(user) => setState({ kind: "in", user })} />;
  return <Home user={state.user} onOut={() => setState({ kind: "out" })} />;
}

function Login({ onDone }: { onDone: (user: string) => void }) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      onDone((await login(username, password)).user);
    } catch (err) {
      setError(loginMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="center">
      <form className="card" onSubmit={submit}>
        <h1>akali 后台</h1>
        <label>
          用户名
          <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" autoFocus />
        </label>
        <label>
          密码
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" />
        </label>
        {error && <p className="error" role="alert">{error}</p>}
        <button type="submit" disabled={busy}>{busy ? "登录中…" : "登录"}</button>
      </form>
    </main>
  );
}

function Home({ user, onOut }: { user: string; onOut: () => void }) {
  const [tab, setTab] = useState<"home" | "lessons" | "articles" | "dict" | "jobs">("home");
  async function out() {
    await logout().catch(() => {});
    onOut();
  }
  return (
    <main className="page">
      <header>
        <h1>akali 后台</h1>
        <nav>
          <button className={tab === "home" ? "tab on" : "tab"} onClick={() => setTab("home")}>首页</button>
          <button className={tab === "lessons" ? "tab on" : "tab"} onClick={() => setTab("lessons")}>听力</button>
          <button className={tab === "articles" ? "tab on" : "tab"} onClick={() => setTab("articles")}>文章</button>
          <button className={tab === "dict" ? "tab on" : "tab"} onClick={() => setTab("dict")}>字典</button>
          <button className={tab === "jobs" ? "tab on" : "tab"} onClick={() => setTab("jobs")}>任务</button>
        </nav>
        <span>{user}</span>
        <button className="link" onClick={out}>退出</button>
      </header>
      {tab === "home" && <p className="muted">牛津词汇会下一步加进来。现在有"听力""文章""字典"和"任务"。</p>}
      {tab === "lessons" && <LessonsPage />}
      {tab === "articles" && <ArticlesPage />}
      {tab === "dict" && <DictPage />}
      {tab === "jobs" && <JobsPage />}
    </main>
  );
}
