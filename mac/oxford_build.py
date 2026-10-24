"""Build vocabulary lessons from the Oxford Picture Dictionary PDF.

  ./.venv-tts/bin/python oxford_build.py 1        build every topic of unit 1

For each topic: page images (JPEG), the numbered entries read from the PDF text, and one spoken mp3 per
entry made with Kokoro (voice af_heart). Output goes to <AKALI_OXFORD>/build/lessons/<code>/ and is
meant to stay on this Mac and the private server, never in the repo.

Needs poppler (pdftoppm, pdftotext), ffmpeg, and the Kokoro model files in <AKALI_OXFORD>/build/tts-model.
"""
import json
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(os.environ.get("AKALI_OXFORD", Path.home() / "Documents/english study/牛津书"))
PDF = ROOT / "Oxford Picture Dictionary Edition 3.pdf"
OUT = ROOT / "build" / "lessons"
MODEL = ROOT / "build" / "tts-model"
VOICE, SPEED = "af_heart", 0.9

# Unit -> list of (topic title, first PDF page, last PDF page), in book order.
UNITS = {
    1: [
        ("Meeting and Greeting", 9, 10), ("Personal Information", 11, 11), ("School", 12, 12),
        ("A Classroom", 13, 14), ("Studying", 15, 16), ("Succeeding in School", 17, 17),
        ("A Day at School", 18, 18), ("Everyday Conversation", 19, 19), ("Weather", 20, 20),
        ("The Telephone", 21, 22), ("Numbers", 23, 23), ("Measurements", 24, 24), ("Time", 25, 26),
        ("The Calendar", 27, 28), ("Calendar Events", 29, 29), ("Describing Things", 30, 30),
        ("Colors", 31, 31), ("Prepositions", 32, 32), ("Money", 33, 33), ("Shopping", 34, 34),
        ("Same and Different", 35, 36),
    ],
}

LABEL = re.compile(r"^([0-9]{1,2}|[A-Za-z])[.:]\s+(\S.*)$")
# Lines after which the page only has practice exercises, not vocabulary.
STOP = re.compile(r"^(Tell your partner|Pair practice|Survey your|Use the new words|Look at pages|Take turns|"
                  r"Ways to talk|Internet Research|Report:|Think about|What do you|Role play|Write |Read the )", re.I)


def ocr_page(page):
    """[(x, y, text)] for the page, top to bottom, read with macOS Vision (cached in build/ocr)."""
    cache = ROOT / "build" / "ocr" / f"{page}.json"
    if cache.exists():
        return [tuple(r) for r in json.loads(cache.read_text())]
    import Quartz, Vision
    from Foundation import NSURL
    cache.parent.mkdir(parents=True, exist_ok=True)
    png = cache.with_suffix("")
    subprocess.run(["pdftoppm", "-r", "200", "-f", str(page), "-l", str(page), "-png", "-singlefile", str(PDF), str(png)], check=True)
    png = png.with_suffix(".png")
    src = Quartz.CGImageSourceCreateWithURL(NSURL.fileURLWithPath_(str(png)), None)
    image = Quartz.CGImageSourceCreateImageAtIndex(src, 0, None)
    req = Vision.VNRecognizeTextRequest.alloc().init()
    req.setRecognitionLevel_(Vision.VNRequestTextRecognitionLevelAccurate)
    req.setRecognitionLanguages_(["en-US"])
    Vision.VNImageRequestHandler.alloc().initWithCGImage_options_(image, None).performRequests_error_([req], None)
    rows = []
    for o in req.results():
        b = o.boundingBox()
        rows.append((round(b.origin.x, 4), round(1 - b.origin.y - b.size.height, 4), o.topCandidates_(1)[0].string()))
    rows.sort(key=lambda r: (r[1], r[0]))
    cache.write_text(json.dumps(rows), encoding="utf-8")
    png.unlink()
    return rows


def clean(text):
    text = text.replace("''", '"').replace("\u00ad", "")
    text = text.replace("\u201c", '"').replace("\u201d", '"')
    text = re.sub(r"\s+[Il|]\s+", " / ", text)          # OCR may turn "/" into I
    text = re.sub(r"\s+", " ", text).strip()
    if text.count('"') % 2:
        text += '"'                                      # a quote the OCR lost at the end
    elif not text.endswith('"'):
        text = re.sub(r"\.$", "", text)
    return text


def raw_entries(page):
    """(label, text) pairs from the page, without the practice exercises at the bottom."""
    rows = ocr_page(page)
    stop_y = min([y for _, y, t in rows if STOP.match(t)], default=2)
    out, last = [], None                  # last = (x, y) of the previous entry line
    for x, y, t in rows:
        if y >= stop_y - 0.005:
            continue
        m = LABEL.match(t.strip())
        if m:
            out.append([m.group(1), m.group(2)])
            last = (x, y)
        elif last and t[:1].islower() and 0 < y - last[1] < 0.04 and abs(x - last[0]) < 0.06:
            out[-1][1] += " " + t.strip()  # a wrapped entry
            last = (x, y)
    return [(a, clean(b)) for a, b in out]


def fix_labels(entries):
    """Repair common OCR slips in labels (S for 5, 8 for B) using what is missing in the sequence."""
    labels = [a for a, _ in entries]
    fixed = []
    for a, b in entries:
        if a in ("S", "s") and "5" not in labels:
            a = "5"
        elif a == "8" and labels.count("8") > 1 or (a == "8" and "B" not in labels and any(x.isalpha() for x in labels)):
            a = "B"
        fixed.append((a.upper() if a.isalpha() else a, b))
    return fixed


def order_key(item):
    label = item[0]
    return (1, label) if label.isalpha() else (0, int(label))


def lesson_entries(first, last):
    entries, seen = [], set()
    for page in range(first, last + 1):
        kept = []
        for label, text in fix_labels(raw_entries(page)):
            text = re.sub(r"\s*/$", "", text).strip()
            if label in seen or len(text) < 2 or len(text.split()) > 9 or text.isupper():
                continue                          # repeated labels are exercises; long or shouting text is not an entry
            if label.isdigit() and "?" in text:
                continue                          # numbered questions are exercises
            seen.add(label)
            kept.append((label, text))
        kept.sort(key=order_key)
        letters = [k[0] for k in kept if k[0].isalpha()]
        while len(letters) > 3 and ord(letters[-1]) - ord(letters[-2]) > 3:
            kept = [k for k in kept if k[0] != letters.pop()]   # a stray letter after the run (S after I)
        entries += [{"label": a, "text": b, "page": page} for a, b in kept]
    return entries


def render_pages(first, last, folder):
    for page in range(first, last + 1):
        target = folder / f"page-{page}.jpg"
        if not target.exists():
            subprocess.run(["pdftoppm", "-r", "110", "-f", str(page), "-l", str(page), "-jpeg", "-jpegopt", "quality=78",
                            "-singlefile", str(PDF), str(target.with_suffix(""))], check=True)


def speak_text(text):
    return re.sub(r"\s*/\s*", ", ", text)


def main():
    unit = int(sys.argv[1])
    import soundfile as sf
    from kokoro_onnx import Kokoro
    tts = Kokoro(str(MODEL / "kokoro.onnx"), str(MODEL / "voices.bin"))
    for index, (title, first, last) in enumerate(UNITS[unit], 1):
        code = f"V{unit:02d}-{index:02d}"
        folder = OUT / code
        (folder / "audio").mkdir(parents=True, exist_ok=True)
        render_pages(first, last, folder)
        entries = lesson_entries(first, last)
        for e in entries:
            name = f"{e['page']}-{e['label']}"
            mp3 = folder / "audio" / f"{name}.mp3"
            if not mp3.exists():
                samples, rate = tts.create(speak_text(e["text"]), voice=VOICE, speed=SPEED, lang="en-us")
                wav = mp3.with_suffix(".wav")
                sf.write(wav, samples, rate)
                subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(wav), "-ac", "1", "-b:a", "64k", str(mp3)], check=True)
                wav.unlink()
            e["audio"] = f"audio/{name}.mp3"
        doc = {"code": code, "unit": unit, "title": title, "pages": list(range(first, last + 1)), "entries": entries}
        (folder / "lesson.json").write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")
        print(code, title, len(entries), "entries", flush=True)


if __name__ == "__main__":
    main()
