"""Transcribe the Oxford Picture Dictionary videos with word timestamps.

  python oxford_transcribe.py "Unit 1"        -> <OUT>/<video name>.json for every matching video

The videos read each entry aloud as "N. word", so the transcript gives both the entry list and where each
word's sound sits in the audio. Paths come from AKALI_OXFORD (default ~/Documents/english study/牛津书).
"""
import json
import os
import subprocess
import sys
from pathlib import Path

import numpy as np
from faster_whisper import WhisperModel

ROOT = Path(os.environ.get("AKALI_OXFORD", Path.home() / "Documents/english study/牛津书"))
OUT = ROOT / "build" / "transcripts"


def load_audio(path):
    # faster-whisper's own decoder is broken with the installed av, so decode through ffmpeg
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", str(path), "-ac", "1", "-ar", "16000", "-f", "f32le", "-"],
                         check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.float32)


def main():
    prefix = sys.argv[1] if len(sys.argv) > 1 else "Unit 1 "
    OUT.mkdir(parents=True, exist_ok=True)
    model = WhisperModel("small", compute_type="int8")
    for video in sorted((ROOT / "视频").glob(prefix + "*.mp4")):
        target = OUT / (video.stem + ".json")
        if target.exists():
            continue
        segs, _ = model.transcribe(load_audio(video), language="en", word_timestamps=True)
        doc = [{"start": s.start, "end": s.end, "text": s.text.strip(),
                "words": [{"w": w.word.strip(), "start": w.start, "end": w.end} for w in s.words]} for s in segs]
        target.write_text(json.dumps(doc, ensure_ascii=False), encoding="utf-8")
        print("done", video.stem, flush=True)


if __name__ == "__main__":
    main()
