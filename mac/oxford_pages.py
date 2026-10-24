"""Work out which PDF pages belong to which picture dictionary topic.

  ./.venv-tts/bin/python oxford_pages.py          OCR every page title, write <AKALI_OXFORD>/build/pages.json

Topic names and their order come from the video file names ("Unit 2 People-03 Describing Hair.mp4");
a topic starts on the page whose heading matches its name and runs until the next topic starts.
Pages that match no topic stay with the topic before them. Anything uncertain is listed in the output so it
can be checked by eye.
"""
import difflib
import json
import os
import re
import sys
from pathlib import Path

import oxford_build as ob

VIDEOS = ob.ROOT / "视频"
OUT = ob.ROOT / "build" / "pages.json"
FIRST_PAGE, LAST_PAGE = 9, 254   # the appendix (Verb Guide, Index) starts on page 255
VIDEO_NAME = re.compile(r"^Unit (\d+) (.+?)-(\d+) (.+)\.mp4$")


def topics_from_videos():
    """{unit: [(number, title)]} in book order."""
    units = {}
    for f in VIDEOS.glob("*.mp4"):
        m = VIDEO_NAME.match(f.name)
        if m:
            units.setdefault(int(m.group(1)), []).append((int(m.group(3)), m.group(4).strip()))
    return {u: sorted(t) for u, t in sorted(units.items())}


def norm(text):
    return re.sub(r"[^a-z0-9 ]", "", text.lower()).strip()


def heading_candidates(page):
    """Texts near the top of the page that could be its heading."""
    return [t for _, y, t in ob.ocr_page(page) if y < 0.1 and 2 < len(t) < 60]


def best_match(page, titles):
    """(title index, ratio) of the topic whose name the page heading looks most like."""
    best = (None, 0.0)
    for text in heading_candidates(page):
        a = norm(text)
        for i, title in enumerate(titles):
            b = norm(title)
            r = difflib.SequenceMatcher(None, a, b).ratio()
            short, long_ = sorted((a, b), key=len)
            if short and short in long_ and len(short) >= 4 and len(short) / len(long_) >= 0.8:
                r = max(r, 0.9)      # e.g. "Time" inside "Time." but not "Mammals" inside "Sea Mammals"
            if r > best[1]:
                best = (i, r)
    return best


def main():
    topics = topics_from_videos()
    flat = [(u, n, t) for u, ts in topics.items() for n, t in ts]
    titles = [t for _, _, t in flat]
    print(f"{len(flat)} topics in {len(topics)} units; reading headings of pages {FIRST_PAGE}-{LAST_PAGE}...", flush=True)
    starts = {}   # topic index -> first page that looks like its heading
    cursor = 0    # topics appear in book order, so only look at the topic we expect next and a few after it
    for page in range(FIRST_PAGE, LAST_PAGE + 1):
        window = range(cursor, min(cursor + 3, len(flat)))
        sub = [titles[i] for i in window]
        idx, ratio = best_match(page, sub)
        if idx is not None and ratio >= 0.85:
            real = window[idx]
            if real not in starts:
                starts[real] = page
                cursor = real + 1 if real >= cursor else cursor
        if page % 25 == 0:
            print(f"  page {page}, topics found so far: {len(starts)}", flush=True)
    result, problems = {}, []
    order = sorted(starts)
    for k, i in enumerate(order):
        u, n, t = flat[i]
        first = starts[i]
        last = starts[order[k + 1]] - 1 if k + 1 < len(order) else LAST_PAGE
        result.setdefault(str(u), []).append({"number": n, "title": t, "first": first, "last": last})
    for i, (u, n, t) in enumerate(flat):
        if i not in starts:
            problems.append(f"Unit {u} #{n} {t}: heading not found")
    for u, items in result.items():
        for it in items:
            if it["last"] - it["first"] >= 3:
                problems.append(f"Unit {u} #{it['number']} {it['title']}: spans {it['last'] - it['first'] + 1} pages ({it['first']}-{it['last']})")
    OUT.write_text(json.dumps({"topics": result, "problems": problems}, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"found {len(starts)} of {len(flat)} topics; wrote {OUT}")
    for p in problems:
        print("CHECK:", p)


if __name__ == "__main__":
    main()
