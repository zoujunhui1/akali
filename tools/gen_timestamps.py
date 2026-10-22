"""One-off tool: write timestamps.json (per-sentence start/end) into each lesson folder.

Runs Whisper (faster-whisper) on the mp3, then aligns the recognised words to the
official transcript.txt, so the app shows the official text with Whisper's timing.

Usage: python gen_timestamps.py <elllo_dir> [--only B2-036] [--force]
Needs: pip install faster-whisper numpy, and ffmpeg on PATH.
"""
import difflib
import json
import re
import subprocess
import sys
from pathlib import Path

import numpy as np
from faster_whisper import WhisperModel

ABBR = ("mr.", "mrs.", "ms.", "dr.", "st.", "vs.", "e.g.", "i.e.")
PAD_BEFORE, PAD_AFTER = 0.15, 0.30
MAX_SENTENCE_S = 12.0  # longer sentences are cut at commas so a loop stays short
MIN_CHUNK_S = 3.0


def split_sentences(para):
    parts = re.split(r'(?<=[.!?])["”\')]*\s+', para)
    out = []
    for p in parts:
        if out and out[-1].lower().endswith(ABBR):
            out[-1] += " " + p
        else:
            out.append(p)
    return [s.strip() for s in out if s.strip()]


def strip_header(text):
    """Older transcript.txt files start with a title / level / url block before the first blank line."""
    head, sep, rest = text.strip().partition("\n\n")
    return rest if sep and "http" in head else text


def split_long(tokens, timed):
    """Return (first token, last token) ranges; cut sentences longer than MAX_SENTENCE_S at commas."""
    whole = [(0, len(tokens) - 1)]
    dur = timed[-1][2] - timed[0][1]
    if dur <= MAX_SENTENCE_S:
        return whole
    cuts = [t for t in timed[:-1] if tokens[t[0]].endswith((",", ";", ":"))]  # cut after these tokens
    n = int(dur // 10) + 1
    t0 = timed[0][1]
    chosen = []
    for k in range(1, n):
        target = t0 + dur * k / n
        ok = [c for c in cuts if c not in chosen and c[2] - t0 >= MIN_CHUNK_S and timed[-1][2] - c[2] >= MIN_CHUNK_S]
        if ok:
            chosen.append(min(ok, key=lambda c: abs(c[2] - target)))
    chosen.sort()
    ranges, first = [], 0
    for c in chosen:
        ranges.append((first, c[0]))
        first = c[0] + 1
    ranges.append((first, len(tokens) - 1))
    return [r for r in ranges if r[0] <= r[1] and any(r[0] <= t[0] <= r[1] for t in timed)]


def norm(word):
    return re.sub(r"[^a-z0-9']", "", word.lower().replace("’", "'"))


def load_audio(path):
    raw = subprocess.run(
        ["ffmpeg", "-v", "quiet", "-i", str(path), "-f", "f32le", "-ac", "1", "-ar", "16000", "-"],
        capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.float32)


def recognise(model, mp3):
    segs, info = model.transcribe(
        load_audio(mp3), language="en", word_timestamps=True,
        condition_on_previous_text=False)
    words = []
    for s in segs:
        for w in s.words:
            n = norm(w.word)
            if n:
                words.append((n, w.start, w.end))
    return words, info.duration


def align(sentences, asr_words, duration):
    """sentences: list of (paragraph_idx, text). Returns list of dicts and match ratio."""
    ref = []  # (norm word, sentence idx, token idx within the sentence)
    for si, (_, text) in enumerate(sentences):
        for ti, w in enumerate(text.split()):
            n = norm(w)
            if n:
                ref.append((n, si, ti))
    sm = difflib.SequenceMatcher(None, [r[0] for r in ref], [a[0] for a in asr_words], autojunk=False)
    start = [None] * len(ref)
    end = [None] * len(ref)
    matched = 0
    for a, b, size in sm.get_matching_blocks():
        for k in range(size):
            start[a + k] = asr_words[b + k][1]
            end[a + k] = asr_words[b + k][2]
            matched += 1
    # interpolate unmatched words between neighbouring anchors
    idx = [i for i, t in enumerate(start) if t is not None]
    if not idx:
        raise RuntimeError("nothing matched")
    for i in range(len(ref)):
        if start[i] is not None:
            continue
        prev = max((j for j in idx if j < i), default=None)
        nxt = min((j for j in idx if j > i), default=None)
        lo = end[prev] if prev is not None else 0.0
        hi = start[nxt] if nxt is not None else duration
        gap_lo = prev + 1 if prev is not None else 0
        gap_hi = nxt if nxt is not None else len(ref)
        n = gap_hi - gap_lo
        step = (hi - lo) / n
        start[i] = lo + step * (i - gap_lo)
        end[i] = start[i] + step
    result = []
    for si, (pi, text) in enumerate(sentences):
        ws = [i for i, r in enumerate(ref) if r[1] == si]
        tokens = text.split()
        # (token idx, start, end) per matched word of this sentence
        timed = [(ref[i][2], start[i], end[i]) for i in ws]
        for first, last in split_long(tokens, timed):
            part = [t for t in timed if first <= t[0] <= last]
            result.append({"paragraph": pi, "text": " ".join(tokens[first:last + 1]),
                           "start": part[0][1], "end": part[-1][2]})
    # pad, keep monotonic and non-overlapping
    for k, s in enumerate(result):
        prev_end = result[k - 1]["end"] if k else 0.0
        next_start = result[k + 1]["start"] if k + 1 < len(result) else duration
        s["start"] = round(max(s["start"] - PAD_BEFORE, (prev_end + s["start"]) / 2 if k else 0.0), 2)
        s["end"] = round(min(s["end"] + PAD_AFTER, (next_start + s["end"]) / 2 if k + 1 < len(result) else duration), 2)
    return result, matched / len(ref)


def main():
    root = Path(sys.argv[1])
    only = sys.argv[sys.argv.index("--only") + 1] if "--only" in sys.argv else None
    force = "--force" in sys.argv
    model = WhisperModel("small", device="cpu", compute_type="int8")
    for d in sorted(p for p in root.iterdir() if p.is_dir()):
        if only and not d.name.startswith(only):
            continue
        out = d / "timestamps.json"
        mp3s = sorted(d.glob("*.mp3"))
        tr = d / "transcript.txt"
        if not mp3s or not tr.exists() or (out.exists() and not force):
            continue
        text = strip_header(tr.read_text(encoding="utf-8"))
        sentences = []
        for pi, para in enumerate(p for p in text.split("\n\n") if p.strip()):
            for s in split_sentences(" ".join(para.split())):
                sentences.append((pi, s))
        words, duration = recognise(model, mp3s[0])
        result, ratio = align(sentences, words, duration)
        out.write_text(json.dumps({"version": 1, "sentences": result}, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"{d.name}: {len(result)} sentences, matched {ratio:.0%}", flush=True)


if __name__ == "__main__":
    main()
