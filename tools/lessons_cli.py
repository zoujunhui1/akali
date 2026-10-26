"""Listening lessons from the command line, for the admin system (the web page runs these as jobs).

  python lessons_cli.py --library DIR list                 JSON: every lesson folder and what the server has
  python lessons_cli.py --library DIR process <elllo url>  fetch, recognise speech, make vocab, upload
  python lessons_cli.py --library DIR redo <code>          recognise speech again, remake vocab, upload (not the audio)
  python lessons_cli.py --library DIR upload <code>        upload what the folder holds
  python lessons_cli.py --library DIR vocab [code ...]     remake vocab from the Feishu table and upload it
  python lessons_cli.py --library DIR delete <code>        delete the lesson on the server (the folder stays)

The server address and tokens come from the environment (AKALI_SERVER, AKALI_ADMIN_TOKEN, AKALI_APP_TOKEN); the Feishu
table from FEISHU_* (see export_vocab.py). Lesson folders are named like B2-036-Eulices-Dream-Job.
"""
import argparse
import json
import sys
from pathlib import Path

import elllo_fetch
import export_vocab
from akali_client import ApiError, Client, parse_folder, strip_header


def say(text):
    print(text, flush=True)


def read_json(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def folders(library):
    """code -> folder for every lesson folder in the library."""
    out = {}
    for p in sorted(library.iterdir()):
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


def lesson_rows(library, remote):
    """One row per lesson: what the folder holds and (when `remote` is known) what the server holds."""
    rows = []
    for code, folder in folders(library).items():
        server = None
        if remote is not None:
            r = remote.get(code)
            server = {
                "present": r is not None,
                "audio": bool(r and r["audio"]),
                "timestamps": len((r["timestamps"] or {}).get("sentences", [])) if r else 0,
                "vocab": len((r["vocab"] or {}).get("items", [])) if r and r["vocab"] else None,
            }
        rows.append({"code": code, "title": parse_folder(folder.name)[1], "local": local_state(folder), "server": server})
    rows.sort(key=lambda r: (r["code"][:2], r["code"][3:]), reverse=True)
    return rows


def upload_folder(client, code, folder, with_audio=True):
    _, title = parse_folder(folder.name)
    url_file = folder / "url.txt"
    url = url_file.read_text(encoding="utf-8").strip() if url_file.exists() else None
    client.put_lesson(code, title, url or None, strip_header((folder / "transcript.txt").read_text(encoding="utf-8")))
    if with_audio:
        mp3 = next(iter(sorted(folder.glob("*.mp3"))), None)
        if mp3 is None:
            raise SystemExit(f"{code} 没有音频文件")
        client.put_audio(code, mp3.read_bytes())
    for name, put in (("timestamps.json", client.put_timestamps), ("vocab.json", client.put_vocab)):
        doc = read_json(folder / name)
        if doc:
            put(code, doc)
    say(f"{code} 已上传到服务器")


def make_vocab(code, folder, rows_by_lesson):
    """Rebuild vocab.json for one lesson from the Feishu rows. Returns True if a file was written."""
    rows = rows_by_lesson.get(code, [])
    ts = read_json(folder / "timestamps.json")
    if not rows:
        say(f"{code}：飞书词表里还没有这一课的词")
        return False
    if not ts:
        say(f"{code}：还没有时间戳，先不能对词")
        return False
    doc, unmatched = export_vocab.build_vocab(ts["sentences"], rows)
    (folder / "vocab.json").write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")
    say(f"{code}：飞书 {len(rows)} 条，对上 {len(rows) - len(unmatched)} 条")
    for f in unmatched:
        say(f"   没对上：{f.get('英文')}")
    return True


def recognise(folder):
    import gen_timestamps  # loads the speech model's libraries, so only when needed
    say("加载语音识别模型并识别，一课大约一两分钟…")
    done = gen_timestamps.timestamps_for_folder(gen_timestamps.load_model(), folder, force=True)
    say(f"逐句时间戳：{done[0]} 句，和文稿匹配 {done[1]:.0%}")


def soft_vocab(code, folder, fallback):
    try:
        make_vocab(code, folder, export_vocab.lesson_rows(export_vocab.feishu_records()))
    except (Exception, SystemExit) as e:  # the words usually come after listening, so this is not fatal
        say(f"{fallback}（{e}）")


def cmd_list(library, args):
    try:
        remote, error = Client().server_lessons(), None
    except (ApiError, OSError, SystemExit) as e:
        remote, error = None, str(e)
    print(json.dumps({"lessons": lesson_rows(library, remote), "server_error": error}, ensure_ascii=False))


def cmd_process(library, args):
    client = Client()
    page = elllo_fetch.normalize_url(args.url)
    code = parse_folder(elllo_fetch.slug_of(page))[0]
    folder = elllo_fetch.fetch_lesson(page, library, say)
    recognise(folder)
    soft_vocab(code, folder, "这一步先跳过词表")
    upload_folder(client, code, folder)
    say("完成。手机上点“立即同步”就能看到。")


def need_folder(library, code):
    folder = folders(library).get(code)
    if not folder:
        raise SystemExit(f"这里没有 {code}")
    return folder


def cmd_redo(library, args):
    client = Client()
    folder = need_folder(library, args.code)
    recognise(folder)
    soft_vocab(args.code, folder, "词表没更新，请稍后点“更新词表”")  # sentence numbers changed, so vocab must follow
    upload_folder(client, args.code, folder, with_audio=False)


def cmd_upload(library, args):
    upload_folder(Client(), args.code, need_folder(library, args.code))


def cmd_vocab(library, args):
    client = Client()
    all_folders = folders(library)
    rows = export_vocab.lesson_rows(export_vocab.feishu_records())
    n = 0
    for code in args.codes or sorted(all_folders):
        folder = all_folders.get(code)
        if folder and (args.codes or code in rows) and make_vocab(code, folder, rows):
            client.put_vocab(code, read_json(folder / "vocab.json"))
            n += 1
    say(f"词表已更新并上传：{n} 课")


def cmd_delete(library, args):
    Client().delete(args.code)
    say(f"{args.code} 已从服务器删除（原件还在，手机同步后会移除这一课）")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--library", required=True, type=Path)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("list").set_defaults(fn=cmd_list)
    p = sub.add_parser("process"); p.add_argument("url"); p.set_defaults(fn=cmd_process)
    for name, fn in (("redo", cmd_redo), ("upload", cmd_upload), ("delete", cmd_delete)):
        p = sub.add_parser(name); p.add_argument("code"); p.set_defaults(fn=fn)
    p = sub.add_parser("vocab"); p.add_argument("codes", nargs="*"); p.set_defaults(fn=cmd_vocab)
    args = ap.parse_args(argv)
    args.library.mkdir(parents=True, exist_ok=True)
    try:
        args.fn(args.library, args)
    except (ApiError, elllo_fetch.ElloError) as e:
        sys.exit(str(e))


if __name__ == "__main__":
    main()
