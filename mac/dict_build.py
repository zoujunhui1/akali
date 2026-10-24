"""Build the small offline English-Chinese dictionary the app uses for tap-to-look-up while reading.

  ./.venv/bin/python dict_build.py [ecdict.csv] [dict.db] [--upload]

Source: ECDICT (MIT license, https://github.com/skywind3000/ECDICT), kept next to the reading files, never in
this repository. Only the common words are kept so the file stays small; inflected forms (ran -> run) are
mapped to their base word. With --upload the file goes to the server and the app downloads it from there.
"""
import csv
import os
import re
import sqlite3
import sys
from pathlib import Path

ROOT = Path(os.environ.get("AKALI_READING", Path.home() / "Documents/english study/阅读"))
SOURCE = ROOT / "ecdict" / "ecdict.csv"
OUTPUT = ROOT / "build" / "dict.db"
MAX_LINES = 6
MAX_DEFINITION_LINES = 5
FORM_KEYS = "pdi3srt"  # past, past participle, -ing, third person, plural, comparative, superlative
FORM_LABELS = {"p": "过去式", "d": "过去分词", "i": "现在分词", "3": "第三人称单数", "s": "复数", "r": "比较级", "t": "最高级"}

SCHEMA = """
CREATE TABLE words (word TEXT PRIMARY KEY, phonetic TEXT, meaning TEXT, tag TEXT, rank INTEGER, collins INTEGER,
                    definition TEXT, forms TEXT);
CREATE TABLE forms (form TEXT PRIMARY KEY, lemma TEXT NOT NULL);
"""


def keep(row):
    """Common words only: a Chinese meaning plus some sign that people actually use the word."""
    if not row["translation"].strip():
        return False
    word = row["word"]
    if not re.fullmatch(r"[A-Za-z][A-Za-z'.-]*", word) or len(word) > 30:
        return False
    return bool(
        int(row["frq"] or 0) > 0 or int(row["bnc"] or 0) > 0 or row["tag"].strip()
        or int(row["collins"] or 0) > 0 or row["oxford"].strip() == "1"
    )


def clean_meaning(text):
    lines = [l.strip() for l in text.replace("\\n", "\n").split("\n") if l.strip()]
    return "\n".join(lines[:MAX_LINES])


def clean_definition(text):
    """English definitions, one per line (ECDICT writes them like 'v. continue to live through hardship')."""
    lines = [l.strip() for l in text.replace("\\n", "\n").split("\n") if l.strip()]
    return "\n".join(lines[:MAX_DEFINITION_LINES])


def forms_line(ex, word):
    """'p:ran/d:run/i:running' -> '过去式 ran · 过去分词 run · 现在分词 running'; forms equal to the word itself are left out."""
    seen, out = set(), []
    for k in FORM_KEYS[:2] + FORM_KEYS[2:]:
        v = ex.get(k)
        if v and v.lower() != word and (k, v.lower()) not in seen:
            seen.add((k, v.lower()))
            out.append(f"{FORM_LABELS[k]} {v}")
    return " · ".join(out)


def rank_of(row):
    """Lower is more common; 0 means unknown. Uses the contemporary-corpus rank, then the BNC rank."""
    for key in ("frq", "bnc"):
        v = int(row[key] or 0)
        if v > 0:
            return v
    return 0


def parse_exchange(text):
    """'p:ran/i:running/0:run' -> {'p': 'ran', 'i': 'running', '0': 'run'}"""
    out = {}
    for part in text.split("/"):
        if ":" in part:
            k, v = part.split(":", 1)
            out.setdefault(k, v)
    return out


def build(source, output):
    csv.field_size_limit(10**8)
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        output.unlink()
    db = sqlite3.connect(output)
    db.executescript(SCHEMA)
    forms = {}
    count = 0
    with open(source, encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            if not keep(row):
                continue
            word = row["word"].lower()
            ex = parse_exchange(row["exchange"])
            db.execute(
                "INSERT OR IGNORE INTO words VALUES (?,?,?,?,?,?,?,?)",
                (word, row["phonetic"].strip(), clean_meaning(row["translation"]), row["tag"].strip(),
                 rank_of(row), int(row["collins"] or 0), clean_definition(row["definition"]), forms_line(ex, word)),
            )
            count += 1
            lemma = ex.get("0")
            if lemma and lemma.lower() != word:       # this entry is itself an inflected form
                forms.setdefault(word, lemma.lower())
            for k in FORM_KEYS:                       # this entry lists its inflected forms
                form = ex.get(k)
                if form and form.lower() != word:
                    forms.setdefault(form.lower(), word)
    db.executemany("INSERT OR IGNORE INTO forms VALUES (?,?)", forms.items())
    # forms whose base word is not in the dictionary are useless
    db.execute("DELETE FROM forms WHERE lemma NOT IN (SELECT word FROM words)")
    db.commit()
    db.execute("VACUUM")
    n_words = db.execute("SELECT COUNT(*) FROM words").fetchone()[0]
    n_forms = db.execute("SELECT COUNT(*) FROM forms").fetchone()[0]
    db.close()
    return n_words, n_forms


def upload(path):
    """Send the dictionary file to the server (skipped when the server already has the same file)."""
    import hashlib
    from akali_client import Client
    data = Path(path).read_bytes()
    client = Client()
    have = client.server_dict()
    if have and have["sha256"] == hashlib.sha256(data).hexdigest():
        print("服务器上的字典已是最新")
        return
    client.put_dict(data)
    print(f"字典已上传（{len(data) / 1048576:.1f} MB）")


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    src = Path(args[0]) if args else SOURCE
    out = Path(args[1]) if len(args) > 1 else OUTPUT
    words, forms = build(src, out)
    print(f"{words} words, {forms} forms -> {out} ({out.stat().st_size / 1048576:.1f} MB)")
    if "--upload" in sys.argv:
        upload(out)
