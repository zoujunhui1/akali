"""Upload the existing lesson folders (elllo/) to the backend: text, audio, timestamps, vocab.

Usage: python upload_existing.py <elllo_dir> [--force-audio] [--only B2-036]
Safe to run again: audio is only uploaded when the server doesn't already hold the same file.
"""
import json
import sys
from pathlib import Path

from akali_client import ApiError, Client, parse_folder, sha256, strip_header


def main():
    root = Path(sys.argv[1])
    force = "--force-audio" in sys.argv
    only = sys.argv[sys.argv.index("--only") + 1] if "--only" in sys.argv else None
    client = Client()
    remote = client.server_lessons()
    done = skipped_audio = failed = 0
    for folder in sorted(p for p in root.iterdir() if p.is_dir()):
        parsed = parse_folder(folder.name)
        if not parsed or (only and parsed[0] != only):
            continue
        code, title = parsed
        mp3s = sorted(folder.glob("*.mp3"))
        transcript_file = folder / "transcript.txt"
        if not mp3s or not transcript_file.exists():
            print(f"{code}: skipped, no mp3 or transcript")
            failed += 1
            continue
        try:
            url_file = folder / "url.txt"
            url = url_file.read_text(encoding="utf-8").strip() if url_file.exists() else None
            client.put_lesson(code, title, url or None, strip_header(transcript_file.read_text(encoding="utf-8")))
            audio = mp3s[0].read_bytes()
            have = (remote.get(code) or {}).get("audio")
            if force or not have or have["sha256"] != sha256(audio):
                client.put_audio(code, audio)
            else:
                skipped_audio += 1
            for name, put in (("timestamps.json", client.put_timestamps), ("vocab.json", client.put_vocab)):
                f = folder / name
                if f.exists():
                    put(code, json.loads(f.read_text(encoding="utf-8")))
            done += 1
        except (ApiError, OSError, ValueError) as e:
            print(f"{code}: FAILED {e}")
            failed += 1
    print(f"uploaded {done} lessons ({skipped_audio} audio files already on the server), failed {failed}")


if __name__ == "__main__":
    main()
