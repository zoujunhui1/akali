"""Small client for the akali backend (admin side), shared by the Mac tools.

Settings come from environment variables or mac/.env (set AKALI_ENV_FILE=mac/.env.dev to talk to a local server):
  AKALI_SERVER        e.g. http://127.0.0.1:18080
  AKALI_ADMIN_TOKEN   write access (upload / delete)
  AKALI_APP_TOKEN     read access, used to see what the server already has
"""
import hashlib
import json
import os
import re
import urllib.error
import urllib.request
from pathlib import Path


def load_env():
    path = Path(os.environ.get("AKALI_ENV_FILE") or Path(__file__).with_name(".env"))
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                os.environ.setdefault(k.strip(), v.strip().strip('"'))


class ApiError(Exception):
    pass


class Client:
    def __init__(self):
        load_env()
        try:
            self.server = os.environ["AKALI_SERVER"].rstrip("/")
            self.admin = os.environ["AKALI_ADMIN_TOKEN"]
            self.app = os.environ["AKALI_APP_TOKEN"]
        except KeyError as e:
            raise SystemExit(f"missing setting {e}: set it in the environment or mac/.env")

    def _call(self, method, path, token, body=None, content_type=None):
        req = urllib.request.Request(self.server + path, data=body, method=method)
        req.add_header("Authorization", "Bearer " + token)
        if content_type:
            req.add_header("Content-Type", content_type)
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                return resp.read()
        except urllib.error.HTTPError as e:
            raise ApiError(f"{method} {path} -> {e.code}: {e.read().decode('utf-8', 'replace')[:200]}")

    def _put_json(self, path, doc):
        self._call("PUT", path, self.admin, json.dumps(doc, ensure_ascii=False).encode("utf-8"), "application/json")

    # ---- read (app token) ----
    def server_lessons(self):
        """code -> {'rev', 'audio': {'size','sha256'} | None, 'has_timestamps', 'has_vocab'}"""
        out = json.loads(self._call("GET", "/api/v1/lessons?content=1", self.app))
        return {
            l["code"]: {"audio": l["audio"], "timestamps": l.get("timestamps"), "vocab": l.get("vocab"),
                        "transcript": l.get("transcript"), "title": l["title"], "source_url": l["source_url"]}
            for l in out["lessons"]
        }

    def download_audio(self, code):
        return self._call("GET", f"/api/v1/lessons/{code}/audio", self.app)

    def server_vocab(self):
        """code -> the picture dictionary topic as the server has it (entries, pages, bundle info)."""
        out = json.loads(self._call("GET", "/api/v1/vocab", self.app))
        return {l["code"]: l for l in out["lessons"]}

    # ---- write (admin token) ----
    def put_lesson(self, code, title, source_url, transcript):
        self._put_json(f"/admin/lessons/{code}", {"title": title, "source_url": source_url, "transcript": transcript})

    def put_audio(self, code, data):
        self._call("PUT", f"/admin/lessons/{code}/audio", self.admin, data, "audio/mpeg")

    def put_timestamps(self, code, doc):
        self._put_json(f"/admin/lessons/{code}/timestamps", doc)

    def put_vocab(self, code, doc):
        self._put_json(f"/admin/lessons/{code}/vocab", doc)

    def put_vocab_lesson(self, code, doc):
        self._put_json(f"/admin/vocab/{code}", {k: doc[k] for k in ("title", "pages", "entries")})

    def put_vocab_bundle(self, code, data):
        self._call("PUT", f"/admin/vocab/{code}/bundle", self.admin, data, "application/zip")

    def delete_vocab(self, code):
        self._call("DELETE", f"/admin/vocab/{code}", self.admin)

    def delete(self, code):
        self._call("DELETE", f"/admin/lessons/{code}", self.admin)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


# ---- local lesson folders (elllo/<folder>/) ----
FOLDER_RE = re.compile(r"^([ABC][12])-(\d{1,3})(?:[-_](.*))?$", re.I)


def parse_folder(name):
    """B2-036-Eulices-Dream-Job -> ('B2-036', 'Eulices Dream Job'); None if not a lesson folder."""
    m = FOLDER_RE.match(name)
    if not m:
        return None
    level, number, rest = m.group(1).upper(), int(m.group(2)), m.group(3) or ""
    words = [w for w in re.split(r"[-_]", rest) if w]
    caps = rest == rest.upper()
    title = " ".join((w.lower() if caps else w)[:1].upper() + (w.lower() if caps else w)[1:] for w in words)
    code = f"{level}-{number:03d}"
    return code, title or code


def strip_header(text):
    """Older transcript.txt files start with a title / level / url block before the first blank line."""
    head, sep, rest = text.strip().partition("\n\n")
    return (rest if sep and "http" in head else text).strip()
