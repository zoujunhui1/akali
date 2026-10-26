"""Tests for the picture dictionary tools. Run from the mac folder:

  ./.venv/bin/python -m unittest discover -s tests

They use made-up words only; the real book text never goes in the repository.
"""
import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import oxford_build as ob  # noqa: E402
import oxford_fix as fix  # noqa: E402


def entry(page, label, text):
    return {"page": page, "label": label, "text": text}


class GapsTest(unittest.TestCase):
    def test_finds_a_hole_inside_the_numbers(self):
        es = [entry(1, str(n), "w") for n in (1, 2, 4, 5)]
        self.assertEqual(ob.gaps(es), ["3"])

    def test_numbering_may_start_above_one(self):
        es = [entry(1, str(n), "w") for n in (13, 14, 15)]
        self.assertEqual(ob.gaps(es), [])

    def test_finds_a_hole_inside_the_letters(self):
        es = [entry(1, c, "w") for c in "ABDE"]
        self.assertEqual(ob.gaps(es), ["C"])

    def test_huge_numbers_do_not_blow_up(self):
        # a number table has labels like 1000000000; checking every number up to it used to eat all the memory
        es = [entry(1, "1", "one"), entry(1, "2", "two"), entry(1, "1000000000", "big")]
        self.assertEqual(ob.gaps(es), [])


class ApplyStepsTest(unittest.TestCase):
    def base(self):
        return [entry(5, "1", "one"), entry(5, "2", "two"), entry(5, "4", "four"), entry(6, "A", "ay")]

    def test_add_fills_the_hole_in_order(self):
        out = fix.apply_steps(self.base(), [["add", 5, "3", "three"]])
        self.assertEqual([e["label"] for e in out if e["page"] == 5], ["1", "2", "3", "4"])
        self.assertEqual(ob.gaps(out), [])

    def test_text_replaces_the_words(self):
        out = fix.apply_steps(self.base(), [["text", 5, "2", "TWO"]])
        self.assertEqual(next(e for e in out if e["label"] == "2")["text"], "TWO")

    def test_rename_moves_a_wrong_label(self):
        out = fix.apply_steps(self.base(), [["rename", 5, "4", "3"]])
        self.assertEqual([e["label"] for e in out if e["page"] == 5], ["1", "2", "3"])

    def test_replace_swaps_a_whole_page_in_the_given_order(self):
        out = fix.apply_steps(self.base(), [["replace", 5, [["10", "ten"], ["9", "nine"]]]])
        self.assertEqual([e["label"] for e in out if e["page"] == 5], ["10", "9"])
        self.assertEqual([e["label"] for e in out if e["page"] == 6], ["A"])

    def test_running_the_same_steps_twice_gives_the_same_result(self):
        steps = [["add", 5, "3", "three"], ["rename", 6, "A", "B"], ["text", 5, "1", "ONE"], ["drop", 5, "2"]]
        once = fix.apply_steps(self.base(), copy.deepcopy(steps))
        twice = fix.apply_steps(copy.deepcopy(once), copy.deepcopy(steps))
        self.assertEqual(once, twice)

    def test_text_on_a_missing_word_is_an_error(self):
        with self.assertRaises(SystemExit):
            fix.apply_steps(self.base(), [["text", 5, "99", "x"]])


class SpeakTextTest(unittest.TestCase):
    def test_slashes_become_pauses(self):
        self.assertEqual(ob.speak_text("a penny / 1 cent"), "a penny, 1 cent")


if __name__ == "__main__":
    unittest.main()
