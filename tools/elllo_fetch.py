"""Fetch one ELLLO lesson page: main audio and transcript. Same rules as the app's old ElloParser."""
import re
import urllib.request
from pathlib import Path
from urllib.parse import urljoin

from bs4 import BeautifulSoup

from akali_client import parse_folder

UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/130 Safari/537.36"


class ElloError(Exception):
    pass


def normalize_url(text):
    """Pick the link out of pasted text and tidy it; only elllo.org lesson pages are accepted."""
    m = re.search(r"https?://[^\s]+", text.strip())
    if m:
        raw = m.group(0)
    elif text.strip().startswith("elllo.org"):
        raw = "https://" + text.strip()
    else:
        raise ElloError("没有找到链接")
    clean = raw.split("#")[0].split("?")[0].rstrip("/)。，")
    host = clean.split("://", 1)[1].split("/", 1)[0].lower()
    if host not in ("elllo.org", "www.elllo.org"):
        raise ElloError("只支持 elllo.org 的课程链接")
    if not parse_folder(slug_of(clean)):
        raise ElloError("这不是课程页面链接（形如 …/video/C1ADV/C1-033-….htm）")
    return clean.replace("http://", "https://", 1).replace("://www.", "://")


def slug_of(url):
    """https://elllo.org/video/C1ADV/C1-033-Saif-Social-Media.htm -> C1-033-Saif-Social-Media"""
    name = url.rsplit("/", 1)[-1]
    return re.sub(r"\.html?$", "", name)


def _get(url, timeout=30):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def parse(html, page_url):
    """Returns (audio_url, transcript). Transcript paragraphs are separated by a blank line."""
    soup = BeautifulSoup(html, "html.parser")
    audio_url = None
    for tag in soup.select("audio source[src], audio[src]"):
        url = urljoin(page_url, tag["src"])
        if "/audio/" in url.lower() and url.lower().endswith(".mp3"):
            audio_url = url
            break
    if not audio_url:
        raise ElloError("页面里没有找到主音频，ELLLO 可能改版了")
    paras = [" ".join(p.get_text().split()) for p in soup.select("#transcript p")]
    transcript = "\n\n".join(p for p in paras if p)
    if not transcript:
        raise ElloError("页面里没有找到文稿，ELLLO 可能改版了")
    return audio_url, transcript


def fetch_lesson(page_url, library, log=print):
    """Download the page's audio and transcript into library/<slug>/. Returns the lesson folder."""
    slug = slug_of(page_url)
    code, _title = parse_folder(slug)
    existing = next((p for p in library.iterdir() if p.is_dir() and (parse_folder(p.name) or ("",))[0] == code), None)
    folder = existing or (library / slug)
    log("读取课程页面…")
    try:
        audio_url, transcript = parse(_get(page_url).decode("utf-8", "replace"), page_url)
    except ElloError:
        raise
    except Exception as e:
        raise ElloError(f"打开页面失败：{e}")
    folder.mkdir(exist_ok=True)
    (folder / "url.txt").write_text(page_url + "\n", encoding="utf-8")
    (folder / "transcript.txt").write_text(transcript + "\n", encoding="utf-8")
    mp3 = next(iter(sorted(folder.glob("*.mp3"))), None) or folder / f"{slug}.mp3"
    log("下载音频…")
    try:
        data = _get(audio_url, timeout=120)
    except Exception as e:
        raise ElloError(f"下载音频失败：{e}")
    if not data:
        raise ElloError("下载到的音频是空的")
    tmp = mp3.with_suffix(".mp3.part")
    tmp.write_bytes(data)
    tmp.replace(mp3)
    log(f"音频 {len(data) / 1e6:.1f} MB，文稿 {len(transcript.split())} 词")
    return folder
