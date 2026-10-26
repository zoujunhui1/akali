"""Tests for the lessons command line. Made-up lesson folders only. Run from the tools folder:

  ./.venv/bin/python -m unittest discover -s tests
"""
import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import lessons_cli as lc  # noqa: E402


class FakeClient:
    def __init__(self):
        self.calls = []

    def server_lessons(self):
        return {"B2-001": {"audio": {"size": 1, "sha256": "x"}, "timestamps": {"sentences": [1, 2]}, "vocab": None}}

    def put_lesson(self, *a): self.calls.append(("lesson", a[0], a[1], a[2], a[3]))
    def put_audio(self, code, data): self.calls.append(("audio", code, len(data)))
    def put_timestamps(self, code, doc): self.calls.append(("timestamps", code))
    def put_vocab(self, code, doc): self.calls.append(("vocab", code))
    def delete(self, code): self.calls.append(("delete", code))


def make_lesson(root, name, ts=False, vocab=False, mp3=True):
    d = root / name
    d.mkdir()
    (d / "transcript.txt").write_text("Made Up Title\nhttp://x\n\nFirst line. Second line.\n", encoding="utf-8")
    (d / "url.txt").write_text("https://elllo.org/video/B2/x.htm\n", encoding="utf-8")
    if mp3:
        (d / f"{name}.mp3").write_bytes(b"abc")
    if ts:
        (d / "timestamps.json").write_text(json.dumps({"sentences": [{}, {}, {}]}), encoding="utf-8")
    if vocab:
        (d / "vocab.json").write_text(json.dumps({"items": [{}]}), encoding="utf-8")
    return d


def run(root, *argv):
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        lc.main(["--library", str(root), *argv])
    return out.getvalue()


class ListTest(unittest.TestCase):
    def test_rows_show_local_and_server_state_newest_first(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            make_lesson(root, "B2-001-Made-Up-One", ts=True)
            make_lesson(root, "B2-002-Made-Up-Two", ts=True, vocab=True, mp3=False)
            (root / "notes").mkdir()
            with mock.patch.object(lc, "Client", FakeClient):
                doc = json.loads(run(root, "list"))
        self.assertIsNone(doc["server_error"])
        rows = {r["code"]: r for r in doc["lessons"]}
        self.assertEqual([r["code"] for r in doc["lessons"]], ["B2-002", "B2-001"])
        self.assertEqual(rows["B2-001"]["title"], "Made Up One")
        self.assertEqual(rows["B2-001"]["local"], {"audio": True, "transcript": True, "timestamps": 3, "vocab": None})
        self.assertEqual(rows["B2-001"]["server"], {"present": True, "audio": True, "timestamps": 2, "vocab": None})
        self.assertFalse(rows["B2-002"]["server"]["present"])
        self.assertEqual(rows["B2-002"]["local"]["vocab"], 1)

    def test_server_down_still_lists_local_lessons(self):
        class Down(FakeClient):
            def server_lessons(self):
                raise lc.ApiError("GET -> 502")
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            make_lesson(root, "B2-001-Made-Up-One")
            with mock.patch.object(lc, "Client", Down):
                doc = json.loads(run(root, "list"))
        self.assertIn("502", doc["server_error"])
        self.assertIsNone(doc["lessons"][0]["server"])


class UploadTest(unittest.TestCase):
    def test_upload_sends_text_audio_and_the_json_files_that_exist(self):
        fake = FakeClient()
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            make_lesson(root, "B2-001-Made-Up-One", ts=True)
            with mock.patch.object(lc, "Client", lambda: fake):
                run(root, "upload", "B2-001")
        kinds = [c[0] for c in fake.calls]
        self.assertEqual(kinds, ["lesson", "audio", "timestamps"])
        self.assertEqual(fake.calls[0][1:], ("B2-001", "Made Up One", "https://elllo.org/video/B2/x.htm", "First line. Second line."))

    def test_unknown_code_is_refused(self):
        with tempfile.TemporaryDirectory() as d, mock.patch.object(lc, "Client", FakeClient):
            with self.assertRaises(SystemExit):
                run(Path(d), "upload", "B2-009")

    def test_delete_only_touches_the_server(self):
        fake = FakeClient()
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            folder = make_lesson(root, "B2-001-Made-Up-One")
            with mock.patch.object(lc, "Client", lambda: fake):
                run(root, "delete", "B2-001")
            self.assertTrue(folder.exists())
        self.assertEqual(fake.calls, [("delete", "B2-001")])


class UrlTest(unittest.TestCase):
    def test_only_elllo_lesson_pages_are_accepted(self):
        for bad in ("https://example.test/video/B2/B2-001-x.htm", "file:///etc/passwd", "https://elllo.org/"):
            with self.assertRaises(lc.elllo_fetch.ElloError):
                lc.elllo_fetch.normalize_url(bad)


if __name__ == "__main__":
    unittest.main()
