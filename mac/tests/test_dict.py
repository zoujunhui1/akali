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


if __name__ == "__main__":
    unittest.main()
