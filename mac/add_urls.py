"""One-off: write url.txt (ELLLO source link) into each lesson folder.

Lesson number (e.g. B2-036) is the key; the real URL is taken from ELLLO's
level index page, then verified with a request. Use --write to save files.
"""
import re
import sys
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path("/Users/zjh/Documents/english study/elllo")
LEVEL_DIR = {"B2": "B2INT", "C1": "C1ADV"}
UA = {"User-Agent": "Mozilla/5.0"}


def fetch(url, method="GET"):
    req = urllib.request.Request(url, headers=UA, method=method)
    return urllib.request.urlopen(req, timeout=20)


def index_map(level):
    base = f"https://elllo.org/video/{LEVEL_DIR[level]}/"
    html = fetch(base + "index.htm").read().decode("utf-8", "ignore")
    found = {}
    for href in re.findall(r'href="([^"]+\.htm)"', html):
        m = re.search(rf"({level}-\d{{3}})-[^/]*\.htm$", href)
        if m:
            found.setdefault(m.group(1), base + urllib.parse.quote(href.split("/")[-1]))
    return found


def main(write):
    maps = {lv: index_map(lv) for lv in LEVEL_DIR}
    ok, bad = 0, []
    for folder in sorted(p for p in ROOT.iterdir() if p.is_dir()):
        m = re.match(r"(B2|C1)-\d{3}", folder.name)
        if not m:
            bad.append((folder.name, "name does not match"))
            continue
        url = maps[m.group(1)].get(m.group(0))
        if not url:
            bad.append((folder.name, "not in index page"))
            continue
        try:
            status = fetch(url, "HEAD").status
        except Exception as e:
            status = str(e)
        if status != 200:
            bad.append((folder.name, f"{url} -> {status}"))
            continue
        ok += 1
        if write:
            (folder / "url.txt").write_text(url + "\n", encoding="utf-8")
    print(f"verified: {ok}, failed: {len(bad)}")
    for name, why in bad:
        print(" ", name, "|", why)


main("--write" in sys.argv)
