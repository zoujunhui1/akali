"""Build vocabulary lessons from the Oxford Picture Dictionary PDF.

  ./.venv-tts/bin/python oxford_build.py 1        build every topic of unit 1
  ./.venv-tts/bin/python oxford_build.py 2-12     build units 2 to 12 (page ranges come from build/pages.json,
                                                   made by oxford_pages.py)

For each topic: page images (JPEG), the numbered entries read from the PDF text, and one spoken mp3 per
entry made with Kokoro (voice af_heart). Output goes to <AKALI_OXFORD>/build/lessons/<code>/ and is
meant to stay on this Mac and the private server, never in the repo.

Needs poppler (pdftoppm, pdftotext), ffmpeg, and the Kokoro model files in <AKALI_OXFORD>/build/tts-model.
"""
import hashlib
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

PAGES_FILE = ROOT / "build" / "pages.json"


def units():
    """Unit -> [(title, first page, last page)]: the page table from oxford_pages.py, unit 1 as hand-checked."""
    table = dict(UNITS)
    if PAGES_FILE.exists():
        for unit, items in json.loads(PAGES_FILE.read_text(encoding="utf-8"))["topics"].items():
            table.setdefault(int(unit), [(i["title"], i["first"], i["last"]) for i in items])
    return table


def gaps(entries):
    """Labels missing inside the run of numbers and letters of one lesson. A run may start above 1
    (numbering can continue from the previous topic), so only holes between its ends count."""
    nums = sorted(int(e["label"]) for e in entries if e["label"].isdigit())
    letters = sorted(e["label"] for e in entries if e["label"].isalpha())
    missing = [str(n) for n in range(nums[0], nums[-1] + 1) if n not in nums] if nums else []
    if letters:
        missing += [chr(c) for c in range(ord(letters[0]), ord(letters[-1]) + 1) if chr(c) not in letters]
    return missing


LABEL = re.compile(r"^(?:([0-9]{1,2})[.:,]|([A-Za-z])[.:])\s+(\S.*)$")
LABEL_ONLY = re.compile(r"^([0-9]{1,2})[.,:]$")
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
    rows = [r for r in rows if r[1] < stop_y - 0.005]
    # a number the OCR read as its own line ("28." then "screen" beside it): put them back together
    joined, used = [], set()
    for i, (x, y, t) in enumerate(rows):
        if i in used:
            continue
        if LABEL_ONLY.match(t.strip()):
            beside = [(rx - x, j) for j, (rx, ry, rt) in enumerate(rows)
                      if j != i and j not in used and abs(ry - y) < 0.012 and 0 < rx - x < 0.1 and not LABEL.match(rt.strip())]
            if beside:
                j = min(beside)[1]
                used.add(j)
                t = t.strip() + " " + rows[j][2].strip()
        joined.append((x, y, t))
    out, spots = [], []                   # spots[i] = (x, y) of out[i]'s label line
    for x, y, t in joined:
        m = LABEL.match(t.strip())
        if m and t.strip()[len(m.group(1) or m.group(2))] == "," and not m.group(3)[0].isalpha():
            m = None                      # "32, 40, 48" is a row of numbers, not an entry
        if m:
            out.append([m.group(1) or m.group(2), m.group(3)])
            spots.append((x, y))
        elif t[:1].islower() and out:
            # a wrapped entry: continues the nearest label line above it in the same column
            near = [(y - sy, i) for i, (sx, sy) in enumerate(spots) if 0 < y - sy < 0.045 and abs(x - sx) < 0.06]
            if near:
                i = min(near)[1]
                out[i][1] += " " + t.strip()
                spots[i] = (spots[i][0], y)   # a third line would continue from here
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


# Characters the OCR mixes up in labels: a repeated label may really be its look-alike.
LOOKALIKE = {"L": "I", "T": "I", "B": "8", "8": "B", "S": "5", "5": "S", "I": "1", "1": "I", "G": "6", "6": "G", "O": "0", "Z": "2", "2": "Z"}


def lesson_entries(first, last):
    kept, seen, repeats = {}, set(), []
    for page in range(first, last + 1):
        kept[page] = []
        for label, text in fix_labels(raw_entries(page)):
            text = re.sub(r"\s*/$", "", text).strip()
            if len(text) < 2 or len(text.split()) > 9 or text.isupper():
                continue                          # long or shouting text is not an entry
            if label.isdigit() and "?" in text:
                continue                          # numbered questions are exercises
            if label in seen:
                repeats.append((page, label, text))   # an exercise number, or a label the OCR misread
                continue
            seen.add(label)
            kept[page].append((label, text))
    # a repeat becomes its look-alike when that label is missing from inside the run of numbers/letters
    numbers = sorted(int(l) for l in seen if l.isdigit())
    letters = sorted(l for l in seen if l.isalpha())
    for page, label, text in repeats:
        alt = LOOKALIKE.get(label)
        if alt is None or alt in seen or len(text.split()) > 6:
            continue
        inside = (alt.isdigit() and numbers and numbers[0] < int(alt) < numbers[-1]) or \
                 (alt.isalpha() and letters and letters[0] < alt < letters[-1])
        if inside:
            seen.add(alt)
            kept[page].append((alt, text))
    entries = []
    for page in range(first, last + 1):
        page_entries = sorted(kept[page], key=order_key)
        run = [k[0] for k in page_entries if k[0].isalpha()]
        while len(run) > 3 and ord(run[-1]) - ord(run[-2]) > 3:
            page_entries = [k for k in page_entries if k[0] != run.pop()]   # a stray letter after the run (S after I)
        entries += [{"label": a, "text": b, "page": page} for a, b in page_entries]
    return entries


def render_pages(first, last, folder):
    for page in range(first, last + 1):
        target = folder / f"page-{page}.jpg"
        if not target.exists():
            subprocess.run(["pdftoppm", "-r", "110", "-f", str(page), "-l", str(page), "-jpeg", "-jpegopt", "quality=78",
                            "-singlefile", str(PDF), str(target.with_suffix(""))], check=True)


def speak_text(text):
    return re.sub(r"\s*/\s*", ", ", text)


def parse_units(arg):
    """"3" -> [3]; "2-12" -> [2..12]."""
    if "-" in arg:
        a, b = arg.split("-")
        return list(range(int(a), int(b) + 1))
    return [int(arg)]


def main():
    import soundfile as sf
    from kokoro_onnx import Kokoro
    tts = Kokoro(str(MODEL / "kokoro.onnx"), str(MODEL / "voices.bin"))
    table = units()
    for unit in parse_units(sys.argv[1]):
        build_unit(unit, table[unit], tts, sf)


def build_unit(unit, topics, tts, sf):
    for index, (title, first, last) in enumerate(topics, 1):
        code = f"V{unit:02d}-{index:02d}"
        folder = OUT / code
        (folder / "audio").mkdir(parents=True, exist_ok=True)
        render_pages(first, last, folder)
        entries = lesson_entries(first, last)
        for e in entries:
            name = f"{e['page']}-{e['label']}-{hashlib.sha1(speak_text(e['text']).encode()).hexdigest()[:6]}"
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
        missing = gaps(entries)
        print(code, title, len(entries), "entries" + (f"   CHECK missing labels: {' '.join(missing)}" if missing else ""), flush=True)


if __name__ == "__main__":
    main()
