"""Turn an article (web page, PDF, EPUB, text) into the clean form the app reads, and upload it.

  ./.venv/bin/python reading_import.py url  <address> [--title T] [--dry-run]
  ./.venv/bin/python reading_import.py file <path.pdf|.epub|.txt|.md> [--title T] [--dry-run]
  ./.venv/bin/python reading_import.py text - [--title T] [--source S]        (text from standard input)
  ./.venv/bin/python reading_import.py draft <draft.json> [--title T]         (upload a draft written by --draft-out)

Add --draft-out FILE to write what would be uploaded into FILE instead of uploading (the admin web page uses this
for its preview step).

The clean article is a title, a source and a list of paragraphs, each a list of sentences. The app only
understands this form, so all the messy work (finding the article in a page, cleaning a PDF, splitting
sentences) happens here on the Mac. Articles go to your own server only, never into the repository.
"""
import argparse
import ipaddress
import json
import re
import socket
import subprocess
import sys
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path
from xml.etree import ElementTree

# ---------------------------------------------------------------- sentences

ABBREVIATIONS = {
    "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "mt", "vs", "etc", "inc", "ltd", "co", "corp", "no",
    "approx", "dept", "est", "gen", "gov", "sen", "rep", "rev", "lt", "col", "sgt", "capt", "fig", "al",
    "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
}
# letters-with-dots like "U.S.", "e.g.", "a.m." never end a sentence by themselves
DOTTED = re.compile(r"^(?:[A-Za-z]\.){1,}$")
CLOSERS = "\"'”’)]»"
OPENERS = "\"'“‘([«"


def _ends_sentence(tokens, i):
    """Does the token at position i (which ends with . ! or ?) really end a sentence?"""
    tok = tokens[i]
    core = tok.rstrip(CLOSERS)
    if not core or core[-1] not in ".!?":
        return False
    if i + 1 >= len(tokens):
        return True
    nxt = tokens[i + 1].lstrip(OPENERS)
    if core[-1] in "!?":
        return bool(nxt) and (nxt[0].isupper() or nxt[0].isdigit())
    word = core.rstrip(".")
    if core.endswith("...") or core.endswith("…"):
        return bool(nxt) and nxt[0].isupper()
    if word.lower() in ABBREVIATIONS:
        return False
    if len(word) == 1 and word.isalpha() and word.isupper():
        return False                      # an initial: "J. K. Rowling"
    if DOTTED.match(core):
        return bool(nxt) and nxt[0].isupper() and word.lower() not in {"u.s", "u.k", "e.g", "i.e", "a.m", "p.m"}
    return bool(nxt) and (nxt[0].isupper() or nxt[0].isdigit() or tokens[i + 1][0] in OPENERS)


def split_sentences(text):
    """Split one paragraph into sentences without breaking on Mr., U.S., 3.5, initials and the like."""
    tokens = text.split()
    sentences, start = [], 0
    for i in range(len(tokens)):
        if _ends_sentence(tokens, i):
            sentences.append(" ".join(tokens[start:i + 1]))
            start = i + 1
    if start < len(tokens):
        sentences.append(" ".join(tokens[start:]))
    return [s for s in sentences if s.strip()]


JUNK = re.compile(r"^(advertisement|share this|subscribe|sign up|read more|related articles?|follow us|click here)\b", re.I)


def to_body(paragraphs):
    """Paragraph texts -> [[sentence, ...], ...], dropping empty and obviously useless paragraphs."""
    body = []
    for p in paragraphs:
        p = re.sub(r"\[(?:\d{1,3}|[a-z]|citation needed|note \d+)\]", "", p)   # footnote marks like [7]
        p = re.sub(r"\s+", " ", p).strip()
        if len(p) < 2 or JUNK.match(p):
            continue
        sentences = split_sentences(p)
        if sentences:
            body.append(sentences)
    return body


# ---------------------------------------------------------------- sources

def paragraphs_from_text(text):
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    blocks = [b for b in re.split(r"\n\s*\n", text) if b.strip()]
    if len(blocks) <= 1 and "\n" in text.strip():   # no blank lines: one paragraph per line
        blocks = [l for l in text.split("\n") if l.strip()]
    return blocks


def _is_heading_line(line):
    line = line.strip()
    return 0 < len(line) <= 100 and not re.search(r"[.!?:,;]$", line)


def split_title(paragraphs, default):
    """Short opening lines without end punctuation (at most two, like "The Old" / "Lighthouse Keeper") are the title."""
    if len(paragraphs) > 1 or (paragraphs and "\n" in paragraphs[0].strip()):
        lines = paragraphs[0].strip().split("\n")
        heads = []
        while len(heads) < min(2, len(lines)) and _is_heading_line(lines[len(heads)]):
            heads.append(lines[len(heads)].strip())
        if heads and (len(heads) < len(lines) or len(paragraphs) > 1):
            rest = [" ".join(l.strip() for l in lines[len(heads):] if l.strip())]
            return " — ".join(heads), ([rest[0]] if rest[0] else []) + paragraphs[1:]
    return default, paragraphs


def _check_public(url):
    """Refuse addresses that point back into this machine or its network (loopback, private, link-local)."""
    parts = urllib.parse.urlparse(url)
    if parts.scheme not in ("http", "https") or not parts.hostname:
        raise SystemExit("只支持 http 或 https 开头的网址")
    try:
        infos = socket.getaddrinfo(parts.hostname, parts.port or 80, type=socket.SOCK_STREAM)
    except socket.gaierror:
        raise SystemExit("找不到这个网址的主机")
    for info in infos:
        ip = ipaddress.ip_address(info[4][0])
        if not ip.is_global:
            raise SystemExit("不能抓取内网或本机的地址")


class _CheckedRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        _check_public(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def from_url(url):
    """(title, paragraphs) of the article on a web page."""
    import trafilatura
    _check_public(url)
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (akali reading import)"})
    with urllib.request.build_opener(_CheckedRedirects).open(req, timeout=30) as resp:
        html = resp.read().decode(resp.headers.get_content_charset() or "utf-8", "replace")
    text = trafilatura.extract(html, include_comments=False, include_tables=False, favor_precision=True)
    if not text:
        raise SystemExit("没能从这个网页里找到文章正文（可能要登录，或页面是靠脚本生成的）")
    meta = trafilatura.extract_metadata(html)
    title = (meta.title if meta and meta.title else "") or url
    return title, [p for p in text.split("\n") if p.strip()]


def clean_pdf_text(raw):
    """pdftotext output -> paragraphs: drop page numbers and repeated headers, join broken lines and hyphens."""
    pages = raw.split("\f")
    counts = {}
    for page in pages:
        for line in {l.strip() for l in page.split("\n") if l.strip()}:
            counts[line] = counts.get(line, 0) + 1
    repeated = {l for l, n in counts.items() if n >= 3 and len(l) < 80 and len(pages) >= 4}
    paragraphs = []
    for page in pages:
        lines = [l.strip() for l in page.split("\n")]
        current = []
        for line in lines + [""]:
            if line and (re.fullmatch(r"\d{1,4}", line) or line in repeated):
                continue
            if line:
                current.append(line)
            elif current:
                paragraphs.append(current)
                current = []
    # a paragraph that continues on the next page (the previous one has no end punctuation) is merged
    merged = []
    for lines in paragraphs:
        text = ""
        for line in lines:
            if text.endswith("-") and line[:1].islower():
                text = text[:-1] + line
            else:
                text = (text + " " + line).strip()
        if merged and not re.search(r"[.!?\"”’)]$", merged[-1]) and text[:1].islower():
            merged[-1] += " " + text
        else:
            merged.append(text)
    return merged


def from_pdf(path):
    out = subprocess.run(["pdftotext", "-enc", "UTF-8", str(path), "-"], capture_output=True, check=True).stdout.decode("utf-8", "replace")
    paragraphs = clean_pdf_text(out)
    if sum(len(p) for p in paragraphs) < 300:
        raise SystemExit("这个 PDF 里几乎没有文字，可能是扫描版（图片）；扫描版暂时还不支持")
    return Path(path).stem, paragraphs


def from_epub(path):
    """[(title, paragraphs)], one per chapter, in reading order."""
    from bs4 import BeautifulSoup
    z = zipfile.ZipFile(path)
    ns = {"c": "urn:oasis:names:tc:opendocument:xmlns:container", "o": "http://www.idpf.org/2007/opf"}
    opf_path = ElementTree.fromstring(z.read("META-INF/container.xml")).find(".//c:rootfile", ns).get("full-path")
    opf = ElementTree.fromstring(z.read(opf_path))
    base = str(Path(opf_path).parent)
    manifest = {i.get("id"): i.get("href") for i in opf.findall(".//o:manifest/o:item", ns)}
    book = opf.findtext(".//{http://purl.org/dc/elements/1.1/}title") or Path(path).stem
    chapters = []
    for ref in opf.findall(".//o:spine/o:itemref", ns):
        href = manifest.get(ref.get("idref"))
        if not href:
            continue
        name = str(Path(base) / href) if base != "." else href
        try:
            soup = BeautifulSoup(z.read(name), "html.parser")
        except KeyError:
            continue
        for tag in soup(["script", "style", "nav"]):
            tag.decompose()
        heading = soup.find(["h1", "h2", "h3"])
        paragraphs = [p.get_text(" ", strip=True) for p in soup.find_all(["p", "h1", "h2", "h3"])]
        paragraphs = [p for p in paragraphs if p]
        if sum(len(p) for p in paragraphs) < 600:     # cover, contents, copyright page
            continue
        title = heading.get_text(" ", strip=True) if heading else f"Part {len(chapters) + 1}"
        chapters.append((f"{book} — {title}", paragraphs))
    if not chapters:
        raise SystemExit("这个 EPUB 里没找到可读的章节")
    return chapters


# ---------------------------------------------------------------- upload

def next_code(existing):
    nums = [int(c[1:]) for c in existing if re.fullmatch(r"R\d{4,6}", c)]
    return f"R{max(nums, default=0) + 1:04d}"


def summary(title, body):
    words = sum(len(s.split()) for p in body for s in p)
    sentences = sum(len(p) for p in body)
    return f"{title} · {len(body)} 段 · {sentences} 句 · {words} 词"


def build_articles(kind, target, title=None, source=None):
    """([(title, body)], source) for a web address, a file path or a piece of text; exits with a message on failure."""
    if kind == "url":
        found, paras = from_url(target)
        items, src = [(found, paras)], target
    elif kind == "file":
        path = Path(target).expanduser()
        suffix = path.suffix.lower()
        if suffix == ".pdf":
            items = [from_pdf(path)]
        elif suffix == ".epub":
            items = from_epub(path)
        elif suffix in (".txt", ".md"):
            items = [split_title(paragraphs_from_text(path.read_text(encoding="utf-8")), path.stem)]
        else:
            raise SystemExit("只支持 PDF、EPUB、txt、md 文件")
        src = path.name
    else:
        text = sys.stdin.read() if target == "-" else target
        items, src = [split_title(paragraphs_from_text(text), title or "Pasted text")], ""
    articles = []
    for found, paras in items:
        body = to_body(paras)
        if not body:
            raise SystemExit("清洗后没有剩下任何正文")
        articles.append((title if title and len(items) == 1 else found, body))
    return articles, source if source is not None else src


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("kind", choices=["url", "file", "text", "draft"])
    ap.add_argument("target", help="web address, file path, or - for standard input")
    ap.add_argument("--title")
    ap.add_argument("--source")
    ap.add_argument("--code", help="replace the article with this code (like R0007) instead of adding a new one")
    ap.add_argument("--draft-out", help="write the cleaned articles to this JSON file and upload nothing")
    ap.add_argument("--dry-run", action="store_true", help="show what would be uploaded, upload nothing")
    args = ap.parse_args(argv)

    if args.kind == "draft":
        doc = json.loads(Path(args.target).read_text(encoding="utf-8"))
        articles = [(a["title"], a["body"]) for a in doc["articles"]]
        source = doc["source"]
        if args.title and len(articles) == 1:
            articles = [(args.title, articles[0][1])]
    else:
        articles, source = build_articles(args.kind, args.target, args.title, args.source)
    for title, body in articles:
        print(summary(title, body))
        print("   ", body[0][0][:100])
    if args.draft_out:
        doc = {"source": source, "articles": [{"title": t, "body": b} for t, b in articles]}
        Path(args.draft_out).write_text(json.dumps(doc, ensure_ascii=False), encoding="utf-8")
        return
    if args.dry_run:
        return
    from akali_client import Client
    client = Client()
    existing = set(client.server_articles())
    for title, body in articles:
        code = args.code if args.code and len(articles) == 1 else next_code(existing)
        client.put_article(code, title, source, body)
        existing.add(code)
        print(f"已上传 {code}")


if __name__ == "__main__":
    main()
