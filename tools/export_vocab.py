"""One-off tool: read the Feishu vocab table (read-only) and write vocab.json into each lesson folder.

Each entry is located in the lesson's timestamps.json sentences: the example sentence finds the
sentence, then the entry is searched inside it. Entries that can't be located are listed, not marked.

Usage: python export_vocab.py <elllo_dir> [--report]
Feishu settings come from the environment: FEISHU_APP_ID, FEISHU_APP_SECRET, FEISHU_BITABLE_TOKEN, FEISHU_TABLE_ID
(on the Mac, the app id and secret fall back to the lark-mcp entry in ~/.claude.json). Never printed or stored.
"""
import difflib
import json
import os
import re
import sys
import urllib.request
from pathlib import Path

HOST = "https://open.feishu.cn/open-apis"

IRREGULAR = {
    "be": "be|is|are|am|was|were|been|being", "have": "have|has|had|having", "do": "do|does|did|done|doing",
    "go": "go|goes|went|gone|going", "get": "get|gets|got|gotten|getting", "take": "take|takes|took|taken|taking",
    "make": "make|makes|made|making", "come": "come|comes|came|coming", "give": "give|gives|gave|given|giving",
    "see": "see|sees|saw|seen|seeing", "say": "say|says|said|saying", "run": "run|runs|ran|running",
    "put": "put|puts|putting", "set": "set|sets|setting", "keep": "keep|keeps|kept|keeping",
    "think": "think|thinks|thought|thinking", "know": "know|knows|knew|known|knowing",
    "buy": "buy|buys|bought|buying", "bring": "bring|brings|brought|bringing", "find": "find|finds|found|finding",
    "tell": "tell|tells|told|telling", "leave": "leave|leaves|left|leaving", "pay": "pay|pays|paid|paying",
    "eat": "eat|eats|ate|eaten|eating", "fall": "fall|falls|fell|fallen|falling", "grow": "grow|grows|grew|grown|growing",
    "lose": "lose|loses|lost|losing", "hold": "hold|holds|held|holding", "stand": "stand|stands|stood|standing",
}
PLACEHOLDER = {"sb", "sth", "someone", "somebody", "something", "one's", "sb's", "sth's"}
POSSESSIVE = {"one's", "sb's", "someone's"}
WILD = r"[\w'’]+(?:\s+[\w'’]+){0,2}"
SENT_END = re.compile(r"[.?!]['\"”]?$")


def norm(text):
    return " ".join(re.sub(r"[^a-z0-9' ]", " ", text.lower().replace("’", "'")).split())


def feishu_credentials():
    """(app id, app secret) from the environment, else from the lark-mcp entry in ~/.claude.json."""
    if os.environ.get("FEISHU_APP_ID") and os.environ.get("FEISHU_APP_SECRET"):
        return os.environ["FEISHU_APP_ID"], os.environ["FEISHU_APP_SECRET"]
    cfg = json.load(open(Path.home() / ".claude.json"))
    stack = [cfg]
    while stack:
        o = stack.pop()
        if isinstance(o, dict):
            if "lark-mcp" in o:
                return o["lark-mcp"]["env"]["APP_ID"], o["lark-mcp"]["env"]["APP_SECRET"]
            stack.extend(o.values())
        elif isinstance(o, list):
            stack.extend(o)
    raise SystemExit("没有飞书凭据：请设置 FEISHU_APP_ID 和 FEISHU_APP_SECRET")


def feishu_records():
    try:
        app_token, table_id = os.environ["FEISHU_BITABLE_TOKEN"], os.environ["FEISHU_TABLE_ID"]
    except KeyError as e:
        raise SystemExit(f"缺少设置 {e}：FEISHU_BITABLE_TOKEN 和 FEISHU_TABLE_ID")
    app_id, app_secret = feishu_credentials()

    def call(url, body=None, token=None):
        req = urllib.request.Request(url, data=json.dumps(body).encode() if body else None,
                                     headers={"Content-Type": "application/json"})
        if token:
            req.add_header("Authorization", "Bearer " + token)
        return json.load(urllib.request.urlopen(req))

    token = call(f"{HOST}/auth/v3/tenant_access_token/internal",
                 {"app_id": app_id, "app_secret": app_secret})["tenant_access_token"]
    items, page = [], ""
    while True:
        url = f"{HOST}/bitable/v1/apps/{app_token}/tables/{table_id}/records?page_size=500" + (f"&page_token={page}" if page else "")
        data = call(url, token=token)["data"]
        items += [i["fields"] for i in data["items"]]
        if not data.get("has_more"):
            return items
        page = data["page_token"]


PATTERN = re.compile(r"___|\.\.\.|…|\b[XYAB]\b")


def clean_en(en):
    return en.split("→")[0].strip()


def entry_type(en, category):
    base = re.sub(r"\([^)]*\)", "", en).strip()
    words = base.split()
    if category == "跟读整句" or SENT_END.search(en.strip()) or len(words) >= 8:
        return "sentence"
    return "phrase" if len(words) >= 2 else "word"


def token_pattern(tok):
    if tok in ("...", "…"):
        return WILD
    t = tok.lower().strip(".,?!\"“”:;")
    if t in POSSESSIVE:
        return r"[\w'’]+"
    if t in PLACEHOLDER or t in ("…", "..."):
        return WILD
    alts = []
    for a in t.split("/"):
        a = re.sub(r"[^a-z0-9'’-]", "", a)
        if not a:
            continue
        if a in IRREGULAR:
            alts.append(f"(?:{IRREGULAR[a]}|[\\w]+'(?:m|re|s|ve|d))" if a in ("be", "have") else f"(?:{IRREGULAR[a]})")
        elif len(a) >= 4:
            stem = re.sub(r"(ing|ied|ies|ed|es|s|e|y)$", "", a)
            stem = stem if len(stem) >= 3 else a
            alts.append(re.escape(stem) + r"[\w'’]*")
        else:
            alts.append(re.escape(a) + r"(?:s|es|ed|ing)?")
    return "(?:" + "|".join(alts) + ")" if alts else ""


def phrase_regex(en):
    base = re.sub(r"\([^)]*\)", " ", en)
    parts = [token_pattern(t) for t in base.split()]
    parts = [p for p in parts if p]
    if not parts:
        return None
    return re.compile(r"(?<![\w'’])" + r"[\s,]+(?:[\w'’]+[\s,]+){0,2}?".join(parts) + r"(?![\w'’])", re.I)


def best_window(sentences, target, max_len=2):
    """Index range of 1..max_len consecutive sentences that best contains the target text."""
    t = norm(target)
    best = (0.0, None)
    for i in range(len(sentences)):
        for n in range(1, max_len + 1):
            if i + n > len(sentences):
                break
            w = norm(" ".join(s["text"] for s in sentences[i:i + n]))
            if not t or not w:
                continue
            score = 1.0 if t in w else difflib.SequenceMatcher(None, t, w, autojunk=False).ratio()
            if t in w:
                score = 1.0 - 0.01 * (n - 1) - 0.001 * (len(w) - len(t)) / max(len(w), 1)
            if score > best[0]:
                best = (score, (i, i + n))
    return best


def locate(sentences, fields):
    en = clean_en(fields.get("英文", ""))
    example = fields.get("例句", "").strip()
    kind = entry_type(en, fields.get("分类", ""))
    if PATTERN.search(en):  # sentence pattern with blanks: mark the whole example sentence
        score, win = best_window(sentences, example or en)
        if win and score >= 0.6 and example:
            return "sentence", [(k, sentences[k]["text"]) for k in range(*win)]
        return "sentence", None
    if kind == "sentence":
        score, win = best_window(sentences, en)
        if win and score >= 0.75:
            return kind, [(k, sentences[k]["text"]) for k in range(*win)]
        return kind, None
    rxs = [r for r in (phrase_regex(a) for a in en.split(" / ")) if r]
    if not rxs:
        return kind, None
    windows = []
    if example:
        score, win = best_window(sentences, example)
        if win and score >= 0.6:
            windows.append(range(*win))
    windows.append(range(len(sentences)))
    for window in windows:
        for k in window:
            for rx in rxs:
                m = rx.search(sentences[k]["text"])
                if m:
                    return kind, [(k, m.group(0).strip(" ,"))]
    return kind, None


def lesson_rows(records):
    """Feishu records -> {'B2-036': [row, ...]} for rows that name a lesson in the 视频 field."""
    by_lesson = {}
    for f in records:
        m = re.match(r"([BC]\d-\d{3})", f.get("视频", "") or "")
        if m and f.get("英文"):
            by_lesson.setdefault(m.group(1), []).append(f)
    return by_lesson


def build_vocab(sentences, rows):
    """Locate each Feishu row in the lesson's sentences. Returns (vocab.json document, unmatched rows)."""
    items, unmatched = [], []
    for f in rows:
        kind, hits = locate(sentences, f)
        if not hits:
            unmatched.append(f)
            continue
        for idx, text in hits:
            items.append({
                "sentence": idx, "text": text, "type": kind, "en": clean_en(f["英文"]),
                "ipa": f.get("音标", ""), "zh": f.get("中文", ""), "example": f.get("例句", ""),
                "note": f.get("备注", ""), "star": bool(f.get("重要度")),
                "mastery": (f.get("掌握") or "").replace("🔴", "").replace("🟡", "").replace("🟢", "").strip(),
            })
    return {"version": 1, "items": items}, unmatched


def main():
    root = Path(sys.argv[1])
    folders = {p.name[:6].upper(): p for p in root.iterdir() if p.is_dir() and (p / "timestamps.json").exists()}
    records = feishu_records()
    by_lesson = lesson_rows(records)
    total = found = 0
    unmatched_all = []
    for key, rows in sorted(by_lesson.items()):
        folder = folders.get(key)
        if not folder:
            print(f"{key}: no folder, {len(rows)} entries skipped")
            continue
        sentences = json.loads((folder / "timestamps.json").read_text(encoding="utf-8"))["sentences"]
        doc, unmatched = build_vocab(sentences, rows)
        total += len(rows)
        found += len(rows) - len(unmatched)
        unmatched_all += [(key, f.get("英文"), f.get("例句", "")) for f in unmatched]
        (folder / "vocab.json").write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"records {len(records)}, lessons {len(by_lesson)}, matched {found}/{total}")
    for u in unmatched_all:
        print("UNMATCHED", *u, sep=" | ")


if __name__ == "__main__":
    main()
