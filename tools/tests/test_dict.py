"""Tests for the dictionary builder, using a tiny made-up source. Run from the mac folder:

  ./.venv/bin/python -m unittest discover -s tests
"""
import csv
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import dict_build as db  # noqa: E402

FIELDS = ["word", "phonetic", "definition", "translation", "pos", "collins", "oxford", "tag", "bnc", "frq", "exchange", "detail", "audio"]


def row(word, translation="n. 东西", tag="", bnc="0", frq="0", exchange="", collins="", oxford="", phonetic="x"):
    r = dict.fromkeys(FIELDS, "")
    r.update(word=word, translation=translation, tag=tag, bnc=bnc, frq=frq, exchange=exchange, collins=collins, oxford=oxford, phonetic=phonetic)
    return r


class BuildTest(unittest.TestCase):
    def build(self, rows):
        d = tempfile.mkdtemp()
        src, out = Path(d) / "src.csv", Path(d) / "out.db"
        with open(src, "w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, FIELDS)
            w.writeheader()
            w.writerows(rows)
        counts = db.build(src, out)
        return counts, sqlite3.connect(out)

    def test_only_common_words_with_a_meaning_are_kept(self):
        (words, _), conn = self.build([
            row("common", frq="500"),
            row("tagged", tag="cet4"),
            row("rare"),                                   # no sign of use
            row("nomeaning", translation="", frq="5"),     # no Chinese meaning
            row("two words", frq="5"),                     # not a single word
        ])
        self.assertEqual(words, 2)
        self.assertEqual(sorted(r[0] for r in conn.execute("select word from words")), ["common", "tagged"])

    def test_inflected_forms_point_to_the_base_word(self):
        (_, forms), conn = self.build([
            row("run", frq="200", exchange="p:ran/i:running/d:run/0:run/3:runs"),
            row("running", frq="300", exchange="0:run/1:i"),
            row("go", frq="100", exchange="p:went/d:gone/i:going/3:goes"),
        ])
        lemma = lambda w: (conn.execute("select lemma from forms where form=?", (w,)).fetchone() or [None])[0]
        self.assertEqual((lemma("ran"), lemma("running"), lemma("runs"), lemma("went"), lemma("gone")), ("run", "run", "run", "go", "go"))
        self.assertIsNone(lemma("run"))                    # a base word is not its own form

    def test_forms_of_words_that_are_not_in_the_dictionary_are_dropped(self):
        _, conn = self.build([row("kept", frq="5", exchange="p:keptt/0:ghost")])
        self.assertEqual(conn.execute("select count(*) from forms where lemma='ghost'").fetchone()[0], 0)

    def test_meaning_is_trimmed_to_a_few_lines_and_rank_prefers_frq(self):
        _, conn = self.build([row("many", translation="\\n".join(f"line {i}" for i in range(10)), frq="42", bnc="7")])
        meaning, rank = conn.execute("select meaning, rank from words where word='many'").fetchone()
        self.assertEqual(len(meaning.split("\n")), db.MAX_LINES)
        self.assertEqual(rank, 42)

    def test_english_definitions_and_the_forms_line_are_kept(self):
        r = row("walk", frq="9", exchange="p:walked/d:walked/i:walking/3:walks/s:walk")
        r["definition"] = "\\n".join(f"n. made up meaning {i}" for i in range(9))
        _, conn = self.build([r])
        definition, forms = conn.execute("select definition, forms from words where word='walk'").fetchone()
        self.assertEqual(len(definition.split("\n")), db.MAX_DEFINITION_LINES)
        self.assertEqual(forms, "过去式 walked · 过去分词 walked · 现在分词 walking · 第三人称单数 walks")  # "walk" itself is left out

    def test_an_inflected_entry_has_no_forms_line(self):
        _, conn = self.build([row("run", frq="1", exchange="p:ran/i:running"), row("ran", frq="2", exchange="0:run")])
        self.assertEqual(conn.execute("select forms from words where word='ran'").fetchone()[0], "")


class DiffTest(unittest.TestCase):
    def make(self, d, name, rows):
        src, out = Path(d) / (name + ".csv"), Path(d) / (name + ".db")
        with open(src, "w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, FIELDS)
            w.writeheader()
            w.writerows(rows)
        db.build(src, out)
        return out

    def test_same_content_has_no_differences_and_changes_are_counted(self):
        with tempfile.TemporaryDirectory() as d:
            base = [row("one", frq="5", exchange="p:oned"), row("two", frq="6")]
            a, b = self.make(d, "a", base), self.make(d, "b", base)
            self.assertEqual(db.diff_databases(a, b), {"words": (0, 0, 2), "forms": (0, 0, 1)})
            c = self.make(d, "c", base[:1] + [row("three", frq="7")])
            self.assertEqual(db.diff_databases(a, c)["words"], (1, 1, 2))   # "two" only in a, "three" only in c


class FakeServer:
    """A stand-in for the client: holds one dictionary file and records uploads."""
    def __init__(self, data):
        self.data, self.uploads = data, []

    def server_dict(self):
        return None if self.data is None else {"sha256": db.sha256_of(self.data), "size": len(self.data)}

    def download_dict(self):
        return self.data

    def put_dict(self, data):
        self.uploads.append(data)


class UploadTest(unittest.TestCase):
    def upload(self, server, path, **kw):
        import akali_client
        from unittest import mock
        with mock.patch.object(akali_client, "Client", lambda: server):
            db.upload(path, **kw)

    def test_identical_bytes_or_identical_rows_are_not_uploaded(self):
        with tempfile.TemporaryDirectory() as d:
            base = [row("one", frq="5"), row("two", frq="6")]
            mine = DiffTest().make(d, "a", base)
            same_bytes = FakeServer(mine.read_bytes())
            self.upload(same_bytes, mine)
            self.assertEqual(same_bytes.uploads, [])
            # same rows, different bytes: rebuild after changing the file's internals
            other = Path(d) / "other.db"
            conn = sqlite3.connect(mine)
            conn.execute("PRAGMA user_version = 7")
            conn.commit()
            conn.close()
            other.write_bytes(mine.read_bytes())
            same_rows = FakeServer(other.read_bytes())
            clean = DiffTest().make(d, "c", base)
            self.assertNotEqual(clean.read_bytes(), other.read_bytes())
            self.upload(same_rows, clean)
            self.assertEqual(same_rows.uploads, [])

    def test_changed_rows_missing_server_file_and_force_do_upload(self):
        with tempfile.TemporaryDirectory() as d:
            mine = DiffTest().make(d, "a", [row("one", frq="5")])
            theirs = DiffTest().make(d, "b", [row("one", frq="5"), row("two", frq="6")])
            changed = FakeServer(theirs.read_bytes())
            self.upload(changed, mine)
            self.assertEqual(len(changed.uploads), 1)
            empty = FakeServer(None)
            self.upload(empty, mine)
            self.assertEqual(len(empty.uploads), 1)
            same = FakeServer(mine.read_bytes())
            self.upload(same, mine, force=True)
            self.assertEqual(len(same.uploads), 1)


class CliTest(unittest.TestCase):
    def test_build_command_reads_the_source_and_writes_the_file(self):
        with tempfile.TemporaryDirectory() as d:
            src, out = Path(d) / "s.csv", Path(d) / "o.db"
            with open(src, "w", encoding="utf-8", newline="") as f:
                w = csv.DictWriter(f, FIELDS)
                w.writeheader()
                w.writerow(row("one", frq="5"))
            db.main(["build", "--source", str(src), "--out", str(out)])
            self.assertTrue(out.exists())
            with self.assertRaises(SystemExit):
                db.main(["build", "--source", str(Path(d) / "missing.csv"), "--out", str(out)])


if __name__ == "__main__":
    unittest.main()
