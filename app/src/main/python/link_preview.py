"""
link_preview.py — Fetch accurate title, thumbnail, and description for a URL.
Uses yt-dlp first (best for video pages), then Open Graph / HTML fallback.
"""

import json
import re
import traceback
from urllib.parse import urlparse, quote

try:
    from url_utils import resolve_url, needs_expansion
except ImportError:
    def needs_expansion(u):
        return False
    def resolve_url(u):
        return u

try:
    import yt_dlp as yt
    YTDLP_OK = True
except ImportError:
    YTDLP_OK = False

try:
    import requests
    from bs4 import BeautifulSoup
    DEPS_OK = True
except ImportError as _e:
    DEPS_OK = False
    _IMPORT_ERR = str(_e)

DEFAULT_UA = (
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Mobile Safari/537.36"
)


def _domain(url):
    try:
        return urlparse(url).netloc.replace("www.", "")
    except Exception:
        return ""


def _build_headers(cookies_str, referer):
    h = {
        "User-Agent": DEFAULT_UA,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.5",
        "Referer": referer or "",
    }
    if cookies_str and cookies_str.strip():
        h["Cookie"] = cookies_str.strip()
    return h


def _clean_text(value):
    if not value:
        return ""
    return re.sub(r"\s+", " ", str(value)).strip()


def _fallback_preview(url, domain):
    platform = domain.split(".")[0].capitalize() if domain else "Video"
    return {
        "title": platform + " link",
        "description": url,
        "thumbnail": "https://www.google.com/s2/favicons?sz=128&domain=" + domain,
        "domain": domain,
    }


def _preview_from_ytdlp(url, cookies_str):
    if not YTDLP_OK:
        return None

    headers = {"User-Agent": DEFAULT_UA}
    if cookies_str and cookies_str.strip():
        headers["Cookie"] = cookies_str.strip()

    opts = {
        "quiet": True,
        "no_warnings": True,
        "no_download": True,
        "skip_download": True,
        "noplaylist": True,
        "cachedir": False,
        "http_headers": headers,
    }

    with yt.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)

    title = _clean_text(info.get("title"))
    if not title:
        return None

    thumb = info.get("thumbnail") or ""
    if not thumb:
        thumbs = info.get("thumbnails") or []
        if thumbs:
            thumb = thumbs[-1].get("url") or ""

    description = _clean_text(
        info.get("description")
        or info.get("uploader")
        or info.get("channel")
        or ""
    )
    if len(description) > 200:
        description = description[:197] + "..."

    return {
        "title": title,
        "description": description,
        "thumbnail": thumb,
        "domain": _domain(url),
    }


def _preview_from_html(url, cookies_str):
    if not DEPS_OK:
        return None

    response = requests.get(
        url,
        headers=_build_headers(cookies_str, url),
        timeout=15,
        allow_redirects=True,
    )
    if response.status_code >= 400:
        return None

    html = response.text[:120_000]
    soup = BeautifulSoup(html, "html.parser")

    def meta(prop, name=None):
        if prop:
            tag = soup.find("meta", property=prop) or soup.find("meta", attrs={"name": prop})
            if tag and tag.get("content"):
                return _clean_text(tag["content"])
        if name:
            tag = soup.find("meta", attrs={"name": name})
            if tag and tag.get("content"):
                return _clean_text(tag["content"])
        return ""

    title = (
        meta("og:title")
        or meta("twitter:title")
        or _clean_text(soup.title.string if soup.title else "")
    )
    description = meta("og:description") or meta("description") or meta("twitter:description")
    thumbnail = meta("og:image") or meta("twitter:image") or meta("twitter:image:src")

    if not title:
        return None

    return {
        "title": title,
        "description": description,
        "thumbnail": thumbnail,
        "domain": _domain(url),
    }


def get_link_preview(url, cookies=""):
    """
    Return JSON preview object for Java LinkPreviewFetcher.
    """
    if not url or not str(url).startswith("http"):
        return json.dumps({"error": "Invalid URL"})

    cookies_str = str(cookies).strip() if cookies else ""
    if needs_expansion(url):
        url = resolve_url(url)
    domain = _domain(url)

    # YouTube oEmbed is fast and reliable
    if "youtube.com" in url or "youtu.be" in url:
        try:
            import requests as req
            api = "https://www.youtube.com/oembed?url=" + quote(url, safe="") + "&format=json"
            r = req.get(api, timeout=8)
            if r.status_code == 200:
                data = r.json()
                return json.dumps({
                    "title": _clean_text(data.get("title")),
                    "description": _clean_text(data.get("author_name")),
                    "thumbnail": data.get("thumbnail_url") or "",
                    "domain": domain,
                })
        except Exception as e:
            print(f"[Preview] YouTube oEmbed failed: {e}")

    try:
        preview = _preview_from_ytdlp(url, cookies_str)
        if preview and preview.get("title"):
            return json.dumps(preview)
    except Exception as e:
        print(f"[Preview] yt-dlp failed: {e}")
        print(traceback.format_exc())

    try:
        preview = _preview_from_html(url, cookies_str)
        if preview and preview.get("title"):
            return json.dumps(preview)
    except Exception as e:
        print(f"[Preview] HTML fallback failed: {e}")

    return json.dumps(_fallback_preview(url, domain))
