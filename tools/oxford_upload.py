"""Upload the built picture dictionary topics to the backend.

  python oxford_upload.py            every topic in <AKALI_OXFORD>/build/lessons
  python oxford_upload.py V01-17     one topic

Each topic is sent as its entries (title, pages, entry texts) plus one zip with the page images and
audio clips. Topics the server already has in the same state are skipped.
"""
import hashlib
import io
import json
import os
import sys
import zipfile
from pathlib import Path

from akali_client import Client

ROOT = Path(os.environ.get("AKALI_OXFORD", Path.home() / "Documents/english study/牛津书"))
LESSONS = ROOT / "build" / "lessons"


def bundle_of(folder, doc):
    """Zip of the page images and audio clips, byte-for-byte the same every time for the same files."""
    names = [f"page-{p}.jpg" for p in doc["pages"]] + sorted({e["audio"] for e in doc["entries"]})
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_STORED) as z:
        for name in names:
            info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
            z.writestr(info, (folder / name).read_bytes())
    return buf.getvalue()


def upload(client, remote, folder, say=print):
    doc = json.loads((folder / "lesson.json").read_text(encoding="utf-8"))
    code = doc["code"]
    server = remote.get(code)
    data = bundle_of(folder, doc)
    same_text = server and (server["title"], server["pages"], server["entries"]) == (doc["title"], doc["pages"], doc["entries"])
    same_bundle = server and server["bundle"] and server["bundle"]["sha256"] == hashlib.sha256(data).hexdigest()
    if same_text and same_bundle:
        say(f"{code} 服务器上已是最新")
        return False
    if not same_text:
        client.put_vocab_lesson(code, doc)
    if not same_bundle:
        client.put_vocab_bundle(code, data)
    say(f"{code} {doc['title']}：{len(doc['entries'])} 条，文件 {len(data) / 1024:.0f} KB 已上传")
    return True


def main(argv=None):
    client = Client()
    remote = client.server_vocab()
    wanted = sys.argv[1:] if argv is None else argv
    folders = sorted(p for p in LESSONS.iterdir() if (p / "lesson.json").exists() and (not wanted or p.name in wanted))
    sent = sum(upload(client, remote, f) for f in folders)
    print(f"完成：{len(folders)} 课，上传 {sent} 课")


if __name__ == "__main__":
    main()
