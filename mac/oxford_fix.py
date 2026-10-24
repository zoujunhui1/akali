"""Apply hand corrections to built picture dictionary lessons, then give new or changed words their audio.

  ./.venv-tts/bin/python oxford_fix.py          apply <AKALI_OXFORD>/build/fixes.json

fixes.json (kept next to the book, never in the repo) maps a lesson code to a list of steps:
  ["replace", page, [[label, text], ...]]   all words of that page, in this order
  ["add", page, label, text]
  ["rename", page, old_label, new_label]
  ["text", page, label, text]
  ["drop", page, label]
Running it again after the lessons were rebuilt applies the same steps to the fresh result.
"""
import hashlib
import json
import subprocess
import sys

import oxford_build as ob

FIXES = ob.ROOT / "build" / "fixes.json"


def sort_key(e):
    label = e["label"]
    return (e["page"], label.isalpha(), int(label) if label.isdigit() else 0, label)


def apply_steps(entries, steps):
    resort = False
    for step in steps:
        op, page = step[0], step[1]
        if op == "replace":
            first = next((i for i, e in enumerate(entries) if e["page"] == page), len(entries))
            rest = [e for e in entries if e["page"] != page]
            entries = rest[:first] + [{"label": l, "text": t, "page": page} for l, t in step[2]] + rest[first:]
            continue
        label = step[2]
        found = next((e for e in entries if e["page"] == page and e["label"] == label), None)
        if op == "add":
            if found:
                continue   # applied on an earlier run
            entries.append({"label": label, "text": step[3], "page": page})
            resort = True
        elif op == "rename":
            if not found:
                if any(e["page"] == page and e["label"] == step[3] for e in entries):
                    continue   # applied on an earlier run
                raise SystemExit(f"not found: page {page} {label}")
            found["label"] = step[3]
            resort = True
        elif op == "text":
            if not found:
                raise SystemExit(f"not found: page {page} {label}")
            found["text"] = step[3]
        elif op == "drop":
            if found:
                entries.remove(found)
    if resort:
        entries.sort(key=sort_key)
    return entries


def speak(tts, sf, text, mp3):
    samples, rate = tts.create(ob.speak_text(text), voice=ob.VOICE, speed=ob.SPEED, lang="en-us")
    wav = mp3.with_suffix(".wav")
    sf.write(wav, samples, rate)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(wav), "-ac", "1", "-b:a", "64k", str(mp3)], check=True)
    wav.unlink()


def main():
    import soundfile as sf
    from kokoro_onnx import Kokoro
    fixes = json.loads(FIXES.read_text(encoding="utf-8"))
    wanted = set(sys.argv[1:])
    tts = None
    for code, steps in fixes.items():
        if wanted and code not in wanted:
            continue
        folder = ob.OUT / code
        path = folder / "lesson.json"
        doc = json.loads(path.read_text(encoding="utf-8"))
        doc["entries"] = apply_steps(doc["entries"], steps)
        keep = set()
        for e in doc["entries"]:
            name = f"{e['page']}-{e['label']}-{hashlib.sha1(ob.speak_text(e['text']).encode()).hexdigest()[:6]}"
            mp3 = folder / "audio" / f"{name}.mp3"
            if not mp3.exists():
                if tts is None:
                    tts = Kokoro(str(ob.MODEL / "kokoro.onnx"), str(ob.MODEL / "voices.bin"))
                speak(tts, sf, e["text"], mp3)
            e["audio"] = f"audio/{name}.mp3"
            keep.add(mp3.name)
        for old in (folder / "audio").glob("*.mp3"):
            if old.name not in keep:
                old.unlink()
        path.write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")
        missing = ob.gaps(doc["entries"])
        print(code, doc["title"], len(doc["entries"]), "entries" + (f"   still missing: {' '.join(missing)}" if missing else ""), flush=True)


if __name__ == "__main__":
    main()
