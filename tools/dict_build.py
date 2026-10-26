"""Build the small offline English-Chinese dictionary the app uses for tap-to-look-up while reading.

  python dict_build.py build [--source ecdict.csv] [--out dict.db]   make the dictionary file
  python dict_build.py compare [--out dict.db]                        compare it with the file on the server, row by row
  python dict_build.py upload [--out dict.db] [--force]               send it to the server (skipped when the content is the same)
  python dict_build.py status [--out dict.db]                         JSON: the local file and the server's

Source: ECDICT (MIT license, https://github.com/skywind3000/ECDICT), kept next to the reading files, never in
this repository. Only the common words are kept so the file stays small; inflected forms (ran -> run) are
mapped to their base word. Uploaded files are downloaded by the app from the server.\nThe folder comes from AKALI_READING (default ~/Documents/english study/阅读): ecdict/ecdict.csv in, build/dict.db out.
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


def sha256_of(data):
    import hashlib
    return hashlib.sha256(data).hexdigest()


def upload(path, force=False):
    """Send the dictionary file to the server, unless the server already has the same content.

    Two files built by different SQLite versions can hold exactly the same rows but differ in bytes, so the rows
    are compared when the bytes differ; a phone would otherwise download a new copy for nothing.
    """
    import tempfile
    from akali_client import Client
    data = Path(path).read_bytes()
    client = Client()
    have = client.server_dict()
    if have and not force:
        if have["sha256"] == sha256_of(data):
            print("服务器上的字典已是最新")
            return
        with tempfile.TemporaryDirectory() as d:
            theirs = Path(d) / "server.db"
            theirs.write_bytes(client.download_dict())
            if all(extra == 0 and missing == 0 for extra, missing, _ in diff_databases(path, theirs).values()):
                print("服务器上的字典内容一致，不用上传")
                return
    client.put_dict(data)
    print(f"字典已上传（{len(data) / 1048576:.1f} MB）")


def diff_databases(mine, theirs):
    """Row-by-row differences between two dictionary files: {table: (only in mine, only in theirs, rows in mine)}."""
    db = sqlite3.connect(mine)
    db.execute("ATTACH DATABASE ? AS other", (str(theirs),))
    out = {}
    for table in ("words", "forms"):
        a = db.execute(f"SELECT COUNT(*) FROM (SELECT * FROM main.{table} EXCEPT SELECT * FROM other.{table})").fetchone()[0]
        b = db.execute(f"SELECT COUNT(*) FROM (SELECT * FROM other.{table} EXCEPT SELECT * FROM main.{table})").fetchone()[0]
        out[table] = (a, b, db.execute(f"SELECT COUNT(*) FROM main.{table}").fetchone()[0])
    db.close()
    return out


def compare(path):
    """Print how the local file differs from the one on the server; returns True when every row is the same."""
    import tempfile
    from akali_client import Client
    if not Path(path).exists():
        raise SystemExit("还没有做好的字典，先重新制作")
    if Client().server_dict() is None:
        raise SystemExit("服务器上还没有字典")
    with tempfile.TemporaryDirectory() as d:
        theirs = Path(d) / "server.db"
        theirs.write_bytes(Client().download_dict())
        result = diff_databases(path, theirs)
    same = True
    for table, (extra, missing, rows) in result.items():
        print(f"{table}：本地 {rows} 行；本地多出 {extra} 行，本地缺少 {missing} 行")
        same = same and extra == 0 and missing == 0
    print("逐行一致" if same else "有差别")
    return same


def status(path):
    from akali_client import Client
    path = Path(path)
    local = None
    if path.exists():
        local = {"size": path.stat().st_size, "modified": int(path.stat().st_mtime), "sha256": sha256_of(path.read_bytes())}
    try:
        server, error = Client().server_dict(), None
    except (Exception, SystemExit) as e:
        server, error = None, str(e)
    import json
    print(json.dumps({"local": local, "server": server, "server_error": error}))


def main(argv=None):
    import argparse
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("build", "compare", "upload", "status"):
        p = sub.add_parser(name)
        p.add_argument("--out", type=Path, default=OUTPUT)
        if name == "build":
            p.add_argument("--source", type=Path, default=SOURCE)
        if name == "upload":
            p.add_argument("--force", action="store_true", help="upload even when the server's content is the same")
    args = ap.parse_args(argv)
    if args.cmd == "build":
        if not args.source.exists():
            raise SystemExit(f"找不到 ECDICT 原始数据：{args.source}")
        words, forms = build(args.source, args.out)
        print(f"{words} words, {forms} forms -> {args.out} ({args.out.stat().st_size / 1048576:.1f} MB)")
    elif args.cmd == "compare":
        if not compare(args.out):
            sys.exit(1)
    elif args.cmd == "upload":
        if not args.out.exists():
            raise SystemExit("还没有做好的字典，先重新制作")
        upload(args.out, args.force)
    else:
        status(args.out)


if __name__ == "__main__":
    main()
