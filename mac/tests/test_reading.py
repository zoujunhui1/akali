"""Tests for the reading import tool. Made-up text only. Run from the mac folder:

  ./.venv/bin/python -m unittest discover -s tests
"""
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import reading_import as ri  # noqa: E402


class SplitSentencesTest(unittest.TestCase):
    def test_plain_sentences(self):
        self.assertEqual(ri.split_sentences("It was dark. The wind blew! Was anyone home? No."),
                         ["It was dark.", "The wind blew!", "Was anyone home?", "No."])

    def test_titles_and_initials_do_not_end_a_sentence(self):
        self.assertEqual(ri.split_sentences("Mr. Brown met Dr. J. K. Smith at noon. They talked."),
                         ["Mr. Brown met Dr. J. K. Smith at noon.", "They talked."])

    def test_dotted_abbreviations_and_decimals(self):
        self.assertEqual(ri.split_sentences("The U.S. grew 3.5 percent, e.g. in May. Then it slowed."),
                         ["The U.S. grew 3.5 percent, e.g. in May.", "Then it slowed."])

    def test_quotes_and_brackets_after_the_full_stop(self):
        self.assertEqual(ri.split_sentences('She said, "Come in." He did. (It rained.) Nobody cared.'),
                         ['She said, "Come in."', "He did.", "(It rained.)", "Nobody cared."])

    def test_a_sentence_ending_with_an_abbreviation_before_a_capital(self):
        self.assertEqual(ri.split_sentences("We left at 6 p.m. The train was late."),
                         ["We left at 6 p.m. The train was late."])  # known limit: ambiguous, kept together

    def test_lowercase_after_a_full_stop_continues(self):
        self.assertEqual(ri.split_sentences("See Fig. 3 for details. It is clear."), ["See Fig. 3 for details.", "It is clear."])

    def test_text_without_a_final_stop(self):
        self.assertEqual(ri.split_sentences("First line. Second line without a stop"), ["First line.", "Second line without a stop"])


class BodyTest(unittest.TestCase):
    def test_paragraphs_become_lists_of_sentences_and_junk_is_dropped(self):
        body = ri.to_body(["Hello there.  How are you?", "Advertisement", "  ", "Fine."])
        self.assertEqual(body, [["Hello there.", "How are you?"], ["Fine."]])

    def test_footnote_marks_are_removed(self):
        self.assertEqual(ri.to_body(["Tea is old.[7] It is popular.[citation needed]"]), [["Tea is old.", "It is popular."]])

    def test_plain_text_paragraphs(self):
        self.assertEqual(ri.paragraphs_from_text("One.\n\nTwo.\n\n\nThree."), ["One.", "Two.", "Three."])
        self.assertEqual(ri.paragraphs_from_text("One.\nTwo."), ["One.", "Two."])  # no blank lines: one per line


class TitleTest(unittest.TestCase):
    def test_a_short_first_line_without_a_full_stop_is_the_title(self):
        self.assertEqual(ri.split_title(["The Old Lighthouse", "It was dark."], "x"), ("The Old Lighthouse", ["It was dark."]))

    def test_a_sentence_or_a_single_paragraph_is_not_a_title(self):
        self.assertEqual(ri.split_title(["It was dark.", "Then rain."], "file"), ("file", ["It was dark.", "Then rain."]))
        self.assertEqual(ri.split_title(["Only one line"], "file"), ("file", ["Only one line"]))

    def test_two_short_lines_stuck_to_the_first_paragraph_make_a_two_part_title(self):
        paras = ["The Old\nLighthouse Keeper\nIt was dark. Rain fell.", "Then morning."]
        self.assertEqual(ri.split_title(paras, "x"), ("The Old — Lighthouse Keeper", ["It was dark. Rain fell.", "Then morning."]))


class PdfCleaningTest(unittest.TestCase):
    def test_joins_lines_and_hyphens_and_drops_page_numbers_and_running_headers(self):
        pages = []
        for n in range(1, 5):
            pages.append(f"Daily Paper\n\nOn page {n} the quick brown fox jumps over the lazy do-\ng and keeps running for {n} hours\nacross field number {n}.\n\n{n}\n")
        text = "\f".join(pages)
        paragraphs = ri.clean_pdf_text(text)
        self.assertTrue(all("Daily Paper" not in p for p in paragraphs))
        self.assertEqual(paragraphs[0], "On page 1 the quick brown fox jumps over the lazy dog and keeps running for 1 hours across field number 1.")
        self.assertEqual(len(paragraphs), 4)

    def test_a_paragraph_continued_on_the_next_page_is_merged(self):
        text = "The story goes on and on\f\nand finally it ends."
        self.assertEqual(ri.clean_pdf_text(text), ["The story goes on and on and finally it ends."])


class EpubTest(unittest.TestCase):
    def make_epub(self, directory, chapters):
        path = Path(directory) / "book.epub"
        with zipfile.ZipFile(path, "w") as z:
            z.writestr("META-INF/container.xml",
                       '<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles>'
                       '<rootfile full-path="OEBPS/content.opf"/></rootfiles></container>')
            items = "".join(f'<item id="c{i}" href="c{i}.xhtml"/>' for i in range(len(chapters)))
            spine = "".join(f'<itemref idref="c{i}"/>' for i in range(len(chapters)))
            z.writestr("OEBPS/content.opf",
                       '<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/">'
                       f'<metadata><dc:title>Made Up Book</dc:title></metadata><manifest>{items}</manifest><spine>{spine}</spine></package>')
            for i, (head, text) in enumerate(chapters):
                z.writestr(f"OEBPS/c{i}.xhtml", f"<html><body><h1>{head}</h1>{text}</body></html>")
        return path

    def test_chapters_in_order_and_short_pages_skipped(self):
        long_text = "".join(f"<p>{'Word ' * 40}.</p>" for _ in range(3))
        with tempfile.TemporaryDirectory() as d:
            path = self.make_epub(d, [("Cover", "<p>Short.</p>"), ("One", long_text), ("Two", long_text)])
            chapters = ri.from_epub(path)
        self.assertEqual([t for t, _ in chapters], ["Made Up Book — One", "Made Up Book — Two"])
        self.assertTrue(all(len(p) >= 3 for _, p in chapters))


class CodeTest(unittest.TestCase):
    def test_next_code(self):
        self.assertEqual(ri.next_code(set()), "R0001")
        self.assertEqual(ri.next_code({"R0001", "R0007", "R0003"}), "R0008")

    def test_summary(self):
        self.assertEqual(ri.summary("T", [["One two.", "Three."], ["Four."]]), "T · 2 段 · 3 句 · 4 词")


if __name__ == "__main__":
    unittest.main()
