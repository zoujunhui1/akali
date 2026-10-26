"""Picture dictionary topics from the command line, for the admin system (the web page runs these as jobs).

  python oxford_cli.py --root DIR list              JSON: every built topic and how it compares with the server
  python oxford_cli.py --root DIR fix [code ...]    apply build/fixes.json to the topics (new words get their audio)
  python oxford_cli.py --root DIR upload [code ...] send topics that differ from the server (all of them when no code)

DIR holds the book: the PDF and build/ (lessons, pages.json, fixes.json, the OCR cache, the speech model).
Topic codes look like V01-17. The server address and tokens come from AKALI_SERVER, AKALI_ADMIN_TOKEN and AKALI_APP_TOKEN.
"""
import argparse
import hashlib
import json
import os
import sys
from pathlib import Path


def topic_rows(lessons, remote, fixes, bundle_of, gaps):
    """One row per built topic, newest code last; `remote` is the server's code -> topic map (None when unknown)."""
    rows = []
    for folder in sorted(p for p in lessons.iterdir() if (p / "lesson.json").exists()):
        doc = json.loads((folder / "lesson.json").read_text(encoding="utf-8"))
        server = None
        if remote is not None:
            r = remote.get(doc["code"])
            if r is None:
                server = "missing"
            else:
                same_text = (r["title"], r["pages"], r["entries"]) == (doc["title"], doc["pages"], doc["entries"])
                same_bundle = bool(r["bundle"]) and r["bundle"]["sha256"] == hashlib.sha256(bundle_of(folder, doc)).hexdigest()
                server = "synced" if same_text and same_bundle else "differs"
        rows.append({"code": doc["code"], "title": doc["title"], "entries": len(doc["entries"]), "pages": len(doc["pages"]),
                     "gaps": len(gaps(doc["entries"])), "fixes": len(fixes.get(doc["code"], [])), "server": server})
    return rows


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", required=True, type=Path)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("list")
    for name in ("fix", "upload"):
        sub.add_parser(name).add_argument("codes", nargs="*")
    args = ap.parse_args(argv)
    os.environ["AKALI_OXFORD"] = str(args.root)   # the book tools read their folder from here

    import oxford_build as ob
    import oxford_fix
    import oxford_upload
    from akali_client import ApiError, Client

    if args.cmd == "fix":
        oxford_fix.main(args.codes)
    elif args.cmd == "upload":
        try:
            oxford_upload.main(args.codes)
        except ApiError as e:
            sys.exit(str(e))
    else:
        fixes_file = ob.ROOT / "build" / "fixes.json"
        fixes = json.loads(fixes_file.read_text(encoding="utf-8")) if fixes_file.exists() else {}
        try:
            remote, error = Client().server_vocab(), None
        except (ApiError, OSError, SystemExit) as e:
            remote, error = None, str(e)
        rows = topic_rows(ob.OUT, remote, fixes, oxford_upload.bundle_of, ob.gaps)
        print(json.dumps({"topics": rows, "server_error": error}, ensure_ascii=False))


if __name__ == "__main__":
    main()
