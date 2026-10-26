"""Tests for the picture dictionary command line. Made-up words only. Run from the tools folder:

  ./.venv/bin/python -m unittest discover -s tests
"""
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import oxford_cli as cli  # noqa: E402


def make_topic(root, code, title, labels, pages=(5,)):
    folder = root / code
    folder.mkdir()
    entries = [{"label": l, "text": f"word {l}", "page": pages[0], "audio": f"audio/{l}.mp3"} for l in labels]
    doc = {"code": code, "title": title, "pages": list(pages), "entries": entries}
    (folder / "lesson.json").write_text(json.dumps(doc), encoding="utf-8")
    return doc


def gaps(entries):
    nums = sorted(int(e["label"]) for e in entries)
    return [str(n) for n in range(nums[0], nums[-1] + 1) if n not in nums]


class RowsTest(unittest.TestCase):
    def rows(self, remote, fixes=None):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            a = make_topic(root, "V01-01", "Made Up One", ["1", "2", "4"])
            make_topic(root, "V01-02", "Made Up Two", ["1", "2"])
            (root / "stray").mkdir()
            self.sha = {"V01-01": b"one", "V01-02": b"two"}
            bundle = lambda folder, doc: self.sha[doc["code"]]
            return cli.topic_rows(root, remote, fixes or {}, bundle, gaps), a

    def test_without_a_server_answer_the_comparison_is_unknown(self):
        rows, _ = self.rows(None)
        self.assertEqual([r["code"] for r in rows], ["V01-01", "V01-02"])
        self.assertEqual([r["server"] for r in rows], [None, None])
        self.assertEqual((rows[0]["entries"], rows[0]["gaps"], rows[1]["gaps"]), (3, 1, 0))

    def test_missing_synced_and_differs(self):
        import hashlib
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            one = make_topic(root, "V01-01", "Made Up One", ["1", "2"])
            two = make_topic(root, "V01-02", "Made Up Two", ["1", "2"])
            make_topic(root, "V01-03", "Made Up Three", ["1"])
            bundle = lambda folder, doc: b"x"
            sha = hashlib.sha256(b"x").hexdigest()
            remote = {
                "V01-01": {**{k: one[k] for k in ("title", "pages", "entries")}, "bundle": {"sha256": sha}},
                "V01-02": {**{k: two[k] for k in ("title", "pages", "entries")}, "bundle": {"sha256": "other"}},
            }
            rows = cli.topic_rows(root, remote, {"V01-02": [["drop", 5, "1"]]}, bundle, gaps)
        self.assertEqual([(r["code"], r["server"], r["fixes"]) for r in rows],
                         [("V01-01", "synced", 0), ("V01-02", "differs", 1), ("V01-03", "missing", 0)])


if __name__ == "__main__":
    unittest.main()
