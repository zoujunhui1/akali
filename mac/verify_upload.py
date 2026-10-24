"""Check the server against the local folders: text, timestamps, vocab, and every audio byte."""
import json
import sys
from pathlib import Path

from akali_client import Client, parse_folder, sha256, strip_header


def main():
    root = Path(sys.argv[1])
    client = Client()
    remote = client.server_lessons()
    problems, checked = [], 0
    local_codes = set()
    for folder in sorted(p for p in root.iterdir() if p.is_dir()):
        parsed = parse_folder(folder.name)
        mp3s = sorted(folder.glob("*.mp3"))
        if not parsed or not mp3s:
            continue
        code, title = parsed
        local_codes.add(code)
        r = remote.get(code)
        if r is None:
            problems.append(f"{code}: missing on server")
            continue
        checked += 1
        if r["title"] != title:
            problems.append(f"{code}: title {r['title']!r} != {title!r}")
        if r["transcript"] != strip_header((folder / "transcript.txt").read_text(encoding="utf-8")):
            problems.append(f"{code}: transcript differs")
        local_audio = mp3s[0].read_bytes()
        if not r["audio"] or r["audio"]["sha256"] != sha256(local_audio):
            problems.append(f"{code}: audio record differs")
        elif sha256(client.download_audio(code)) != sha256(local_audio):
            problems.append(f"{code}: downloaded audio differs")
        for name, key, field in (("timestamps.json", "timestamps", "sentences"), ("vocab.json", "vocab", "items")):
            f = folder / name
            if f.exists():
                if json.loads(f.read_text(encoding="utf-8"))[field] != (r[key] or {}).get(field):
                    problems.append(f"{code}: {name} differs")
            elif r[key]:
                problems.append(f"{code}: server has {name} but local folder doesn't")
    extra = set(remote) - local_codes
    if extra:
        problems.append(f"on server but not local: {sorted(extra)}")
    print(f"checked {checked} lessons on the server, {len(problems)} problems")
    for p in problems:
        print(" -", p)


if __name__ == "__main__":
    main()
