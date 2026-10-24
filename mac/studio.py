"""akali studio: a page on this Mac for adding lessons and keeping their data up to date.

  python studio.py            then open http://127.0.0.1:18765

Everything heavy happens here (fetching ELLLO, Whisper, reading the Feishu table); the results are
uploaded to the backend, and the phone only syncs from there. Listens on this Mac only.

Settings: mac/.env (AKALI_SERVER, AKALI_ADMIN_TOKEN, AKALI_APP_TOKEN); the lesson folders live in
AKALI_LIBRARY (default ~/Documents/english study/elllo).
"""
import json
import os
import queue
import threading
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import elllo_fetch
import export_vocab
import gen_timestamps
from akali_client import ApiError, Client, parse_folder, strip_header

PORT = int(os.environ.get("AKALI_STUDIO_PORT", "18765"))
LIBRARY = Path(os.environ.get("AKALI_LIBRARY", Path.home() / "Documents/english study/elllo"))
PAGE = Path(__file__).with_name("studio.html")


# ---------------------------------------------------------------- library

def read_json(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def folders():
    """code -> folder for every lesson folder in the library."""
    out = {}
    for p in sorted(LIBRARY.iterdir()):
        parsed = parse_folder(p.name) if p.is_dir() else None
        if parsed:
            out[parsed[0]] = p
    return out


def local_state(folder):
    mp3 = next(iter(sorted(folder.glob("*.mp3"))), None)
    ts = read_json(folder / "timestamps.json")
    vocab = read_json(folder / "vocab.json")
    return {
        "audio": bool(mp3),
        "transcript": (folder / "transcript.txt").exists(),
        "timestamps": len(ts["sentences"]) if ts else 0,
        "vocab": len(vocab["items"]) if vocab else None,
    }


_remote_cache = {"at": 0.0, "data": None, "error": None}


def remote_lessons(force=False):
    if force or time.time() - _remote_cache["at"] > 5:
        try:
            _remote_cache["data"] = Client().server_lessons()
            _remote_cache["error"] = None
        except Exception as e:  # server down, wrong token...
            _remote_cache["data"], _remote_cache["error"] = None, str(e)
        _remote_cache["at"] = time.time()
    return _remote_cache["data"], _remote_cache["error"]


def lesson_list():
    remote, error = remote_lessons()
    rows = []
    for code, folder in folders().items():
        local = local_state(folder)
        _, title = parse_folder(folder.name)
        server = None
        if remote is not None:
            r = remote.get(code)
            server = {
                "present": r is not None,
                "audio": bool(r and r["audio"]),
                "timestamps": len((r["timestamps"] or {}).get("sentences", [])) if r else 0,
                "vocab": len((r["vocab"] or {}).get("items", [])) if r and r["vocab"] else None,
            }
        rows.append({"code": code, "title": title, "local": local, "server": server})
    rows.sort(key=lambda r: (r["code"][:2], r["code"][3:]), reverse=True)
    return {"lessons": rows, "server_error": error}


# ---------------------------------------------------------------- jobs

class Job:
    def __init__(self, job_id, title):
        self.id, self.title = job_id, title
        self.state, self.log, self.error = "queued", [], None
        self.started = self.finished = None

    def say(self, text):
        self.log.append(text)

    def to_json(self):
        return {"id": self.id, "title": self.title, "state": self.state, "log": self.log[-40:], "error": self.error}


jobs = []
_queue = queue.Queue()
_model = None


def whisper_model(job):
    global _model
    if _model is None:
        job.say("加载语音识别模型（第一次会慢一点）…")
        _model = gen_timestamps.load_model()
    return _model


def submit(title, fn):
    job = Job(len(jobs) + 1, title)
    jobs.append(job)
    _queue.put((job, fn))
    return job


def worker():
    while True:
        job, fn = _queue.get()
        job.state, job.started = "running", time.time()
        try:
            fn(job)
            job.state = "done"
        except (ApiError, elllo_fetch.ElloError, OSError, ValueError, KeyError, RuntimeError) as e:
            job.state, job.error = "error", str(e)
        except Exception as e:
            job.state, job.error = "error", f"{type(e).__name__}: {e}"
            job.say(traceback.format_exc(limit=3))
        job.finished = time.time()
        remote_lessons(force=True)


# ---------------------------------------------------------------- tasks

def upload_folder(job, client, code, folder, with_audio=True):
    _, title = parse_folder(folder.name)
    url_file = folder / "url.txt"
    url = url_file.read_text(encoding="utf-8").strip() if url_file.exists() else None
    client.put_lesson(code, title, url or None, strip_header((folder / "transcript.txt").read_text(encoding="utf-8")))
    if with_audio:
        mp3 = next(iter(sorted(folder.glob("*.mp3"))), None)
        if mp3 is None:
            raise RuntimeError(f"{code} 没有音频文件")
        client.put_audio(code, mp3.read_bytes())
    for name, put in (("timestamps.json", client.put_timestamps), ("vocab.json", client.put_vocab)):
        doc = read_json(folder / name)
        if doc:
            put(code, doc)
    job.say(f"{code} 已上传到服务器")


def make_vocab(job, code, folder, rows_by_lesson):
    """Rebuild vocab.json for one lesson from the Feishu rows. Returns True if a file was written."""
    rows = rows_by_lesson.get(code, [])
    ts = read_json(folder / "timestamps.json")
    if not rows:
        job.say(f"{code}：飞书词表里还没有这一课的词")
        return False
    if not ts:
        job.say(f"{code}：还没有时间戳，先不能对词")
        return False
    doc, unmatched = export_vocab.build_vocab(ts["sentences"], rows)
    (folder / "vocab.json").write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")
    job.say(f"{code}：飞书 {len(rows)} 条，对上 {len(rows) - len(unmatched)} 条")
    for f in unmatched:
        job.say(f"   没对上：{f.get('英文')}")
    return True


def task_process(url):
    def run(job):
        client = Client()
        page = elllo_fetch.normalize_url(url)
        code = parse_folder(elllo_fetch.slug_of(page))[0]
        folder = elllo_fetch.fetch_lesson(page, LIBRARY, job.say)
        job.say("语音识别中，一课大约要几分钟…")
        done = gen_timestamps.timestamps_for_folder(whisper_model(job), folder, force=True)
        job.say(f"逐句时间戳：{done[0]} 句，和文稿匹配 {done[1]:.0%}")
        try:
            make_vocab(job, code, folder, export_vocab.lesson_rows(export_vocab.feishu_records()))
        except Exception as e:  # Feishu is optional here: the words usually come after listening
            job.say(f"这一步先跳过词表（{e}）")
        upload_folder(job, client, code, folder)
        job.say("完成。手机上点“立即同步”就能看到。")
    return run


def task_vocab(codes):
    def run(job):
        client = Client()
        all_folders = folders()
        rows = export_vocab.lesson_rows(export_vocab.feishu_records())
        targets = codes or sorted(all_folders)
        n = 0
        for code in targets:
            folder = all_folders.get(code)
            if not folder:
                continue
            if codes or code in rows:
                if make_vocab(job, code, folder, rows):
                    doc = read_json(folder / "vocab.json")
                    client.put_vocab(code, doc)
                    n += 1
        job.say(f"词表已更新并上传：{n} 课")
    return run


def task_redo(code):
    def run(job):
        client = Client()
        folder = folders().get(code)
        if not folder:
            raise RuntimeError(f"本地没有 {code}")
        job.say("重新做语音识别…")
        done = gen_timestamps.timestamps_for_folder(whisper_model(job), folder, force=True)
        job.say(f"逐句时间戳：{done[0]} 句，和文稿匹配 {done[1]:.0%}")
        try:  # sentence numbers changed, so the vocab marks have to follow
            make_vocab(job, code, folder, export_vocab.lesson_rows(export_vocab.feishu_records()))
        except Exception as e:
            job.say(f"词表没更新（{e}），请稍后点“更新词表”")
        upload_folder(job, client, code, folder, with_audio=False)
    return run


def task_upload(code):
    def run(job):
        folder = folders().get(code)
        if not folder:
            raise RuntimeError(f"本地没有 {code}")
        upload_folder(job, Client(), code, folder)
    return run


# ---------------------------------------------------------------- http

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _send(self, status, body, ctype="application/json; charset=utf-8"):
        data = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _local_only(self):
        """Only this Mac's own page may talk to us (guards against other websites poking localhost)."""
        host = self.headers.get("Host", "")
        origin = self.headers.get("Origin")
        ok_hosts = {f"127.0.0.1:{PORT}", f"localhost:{PORT}"}
        if host not in ok_hosts or (origin and origin.split("://", 1)[-1] not in ok_hosts):
            self._send(403, {"error": "forbidden"})
            return False
        return True

    def do_GET(self):
        if not self._local_only():
            return
        if self.path == "/":
            self._send(200, PAGE.read_bytes(), "text/html; charset=utf-8")
        elif self.path == "/api/lessons":
            remote_lessons(force=True)
            self._send(200, lesson_list())
        elif self.path == "/api/jobs":
            self._send(200, {"jobs": [j.to_json() for j in reversed(jobs[-12:])]})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        if not self._local_only():
            return
        if not (self.headers.get("Content-Type") or "").startswith("application/json"):
            return self._send(415, {"error": "json only"})
        try:
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")
        except ValueError:
            return self._send(400, {"error": "bad json"})
        code = str(body.get("code", ""))
        if self.path == "/api/process":
            try:
                page = elllo_fetch.normalize_url(str(body.get("url", "")))
            except elllo_fetch.ElloError as e:
                return self._send(400, {"error": str(e)})
            lesson = parse_folder(elllo_fetch.slug_of(page))
            job = submit(f"处理新课 {lesson[0]} {lesson[1]}", task_process(page))
        elif self.path == "/api/vocab":
            job = submit(f"更新词表 {code}" if code else "更新全部词表", task_vocab([code] if code else []))
        elif self.path == "/api/redo" and code in folders():
            job = submit(f"重新识别 {code}", task_redo(code))
        elif self.path == "/api/upload" and code in folders():
            job = submit(f"重新上传 {code}", task_upload(code))
        else:
            return self._send(404, {"error": "unknown action"})
        self._send(200, job.to_json())


def main():
    if not LIBRARY.is_dir():
        raise SystemExit(f"lesson folder not found: {LIBRARY}")
    Client()  # fail early if the settings are missing
    threading.Thread(target=worker, daemon=True).start()
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print(f"akali studio: http://127.0.0.1:{PORT}   (library: {LIBRARY})", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
