"""
page_scraper.py — Find downloadable videos on a web page.

Priority:
1. Entries parsed from WebView JavaScript (visible DOM)
2. yt-dlp extract_info on the page URL (accurate metadata)
3. HTML parsing (anchors, video tags, iframes)

Returns JSON list: [{"url","title","thumb","duration"}, ...]
"""

import json
import re
import traceback
from urllib.parse import urljoin, urlparse, unquote

try:
    from url_utils import resolve_url, needs_expansion
except ImportError:
    def needs_expansion(u):
        return False
    def resolve_url(u):
        return u

try:
    import requests
    from bs4 import BeautifulSoup
    DEPS_OK = True
except ImportError as _import_err:
    DEPS_OK = False
    _IMPORT_ERR_MSG = str(_import_err)

try:
    import yt_dlp as yt
    YTDLP_OK = True
except ImportError:
    YTDLP_OK = False

MAX_RESULTS = 30

VIDEO_EXTENSIONS = re.compile(
    r"\.(mp4|m3u8|mpd|webm|mov|avi|mkv|flv|ts|3gp|m4v|ogv)(?:\?|$)",
    re.IGNORECASE,
)

VIDEO_HOST_HINTS = (
    "youtube.com", "youtu.be", "tiktok.com", "instagram.com", "facebook.com",
    "fb.watch", "dailymotion.com", "vimeo.com", "twitter.com", "x.com",
    "pinterest.com", "pin.it", "reddit.com", "rumble.com", "twitch.tv",
    "streamable.com", "bilibili.com", "snapchat.com",
)

SKIP_URL_RE = re.compile(
    r"(/login|/signin|/sign-in|/signup|/register|/privacy|/terms|/about|"
    r"/contact|/help|/faq|/cookie|/account|/settings|/profile|/search[/?]|"
    r"/cdn-cgi/|javascript:|mailto:|tel:|/share[/?]|/intent/|"
    r"facebook\.com/sharer|twitter\.com/intent|api\.|/ads[/?]|/ad[/?])",
    re.IGNORECASE,
)

DEFAULT_UA = (
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Mobile Safari/537.36"
)


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


def _entry(url, title="", thumb="", duration=0, score=0):
    return {
        "url": url,
        "title": (title or "").strip()[:200],
        "thumb": (thumb or "").strip(),
        "duration": int(duration or 0),
        "_score": score,
    }


def _normalize_key(url):
    return url.split("#")[0].split("?")[0].rstrip("/").lower()


def _is_blob(url):
    return url.startswith("blob:")


def _should_skip(url):
    if not url or _is_blob(url):
        return True
    lower = url.lower()
    if not lower.startswith("http"):
        return True
    if SKIP_URL_RE.search(lower):
        return True
    path = urlparse(lower).path or "/"
    if path in ("", "/"):
        return True
    return False


def _is_direct_video(url):
    return bool(VIDEO_EXTENSIONS.search(url))


def _is_video_page(url):
    lower = url.lower()
    if _is_direct_video(url):
        return True
    if any(h in lower for h in VIDEO_HOST_HINTS):
        return True
    for pat in ("/video/", "/watch", "/reel/", "/shorts/", "/play/", "/embed/", "v=", "/v/"):
        if pat in lower:
            return True
    return False


def _score_url(url, title="", has_thumb=False, from_ytdlp=False, is_current=False):
    score = 0
    if is_current:
        score += 100
    if from_ytdlp:
        score += 80
    if _is_direct_video(url):
        score += 70
    if _is_video_page(url):
        score += 40
    if title and len(title) > 3 and title.lower() not in ("video", "watch", "play", "click here"):
        score += 20
    if has_thumb:
        score += 15
    if _should_skip(url):
        score -= 200
    return score


def _extract_title_from_url(url):
    try:
        path = urlparse(url).path.strip("/")
        if not path:
            return "Current page"
        title = re.sub(r"[-_]", " ", path.split("/")[-1])
        title = re.sub(r"\.[a-z0-9]+$", "", title, flags=re.IGNORECASE)
        title = unquote(title).strip()
        return title[:100] if title else "Video"
    except Exception:
        return "Video"


def _entry_from_ytdlp_info(info, page_url):
    if not info:
        return None
    url = (
        info.get("webpage_url")
        or info.get("original_url")
        or info.get("url")
        or page_url
    )
    if _should_skip(url) and not _is_direct_video(url):
        return None
    title = info.get("title") or _extract_title_from_url(url)
    thumb = info.get("thumbnail") or ""
    if not thumb:
        thumbs = info.get("thumbnails") or []
        if thumbs:
            thumb = thumbs[-1].get("url") or ""
    duration = int(info.get("duration") or 0)
    return _entry(url, title, thumb, duration, _score_url(url, title, bool(thumb), True))


def _extract_with_ytdlp(page_url, cookies_str):
    if not YTDLP_OK:
        return []
    out = []
    headers = {"User-Agent": DEFAULT_UA}
    if cookies_str:
        headers["Cookie"] = cookies_str
    opts = {
        "quiet": True,
        "no_warnings": True,
        "no_download": True,
        "skip_download": True,
        "cachedir": False,
        "http_headers": headers,
        "noplaylist": False,
    }
    try:
        with yt.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(page_url, download=False)
        if not info:
            return out
        if info.get("_type") in ("playlist", "multi_video") or info.get("entries"):
            for item in info.get("entries") or []:
                e = _entry_from_ytdlp_info(item, page_url)
                if e:
                    out.append(e)
        else:
            e = _entry_from_ytdlp_info(info, page_url)
            if e:
                out.append(e)
    except Exception as e:
        print(f"[Scraper] yt-dlp extract failed: {e}")
    return out


def _parse_js_entries(js_json, page_url):
    out = []
    if not js_json:
        return out
    try:
        data = json.loads(js_json)
        if isinstance(data, dict) and data.get("error"):
            return out
        if not isinstance(data, list):
            return out
        for item in data:
            url = (item.get("url") or "").strip()
            if _should_skip(url):
                continue
            title = item.get("title") or _extract_title_from_url(url)
            thumb = item.get("thumb") or ""
            is_current = _normalize_key(url) == _normalize_key(page_url)
            out.append(_entry(
                url, title, thumb, item.get("duration") or 0,
                _score_url(url, title, bool(thumb), False, is_current),
            ))
    except Exception as e:
        print(f"[Scraper] JS JSON parse error: {e}")
    return out


def _process_anchor(link, page_url, entries, seen):
    href = (link.get("href") or "").strip()
    if not href or href.startswith("#"):
        return

    try:
        abs_url = urljoin(page_url, href)
    except Exception:
        return

    if _should_skip(abs_url) or not _is_video_page(abs_url):
        return

    key = _normalize_key(abs_url)
    if key in seen:
        return

    title = (link.get_text(" ", strip=True) or "").strip()
    if not title:
        title = link.get("aria-label") or link.get("title") or ""
    title = re.sub(r"\s+", " ", title).strip()

    thumb = ""
    img = link.find("img")
    if img:
        thumb = img.get("src") or img.get("data-src") or img.get("data-lazy-src") or ""
        if thumb and not thumb.startswith("http"):
            thumb = urljoin(page_url, thumb)
        if not title:
            title = img.get("alt") or ""

    if not title or len(title) < 2:
        title = _extract_title_from_url(abs_url)

    seen.add(key)
    entries.append(_entry(
        abs_url, title, thumb, 0,
        _score_url(abs_url, title, bool(thumb)),
    ))


def _scrape_html(html, page_url):
    soup = BeautifulSoup(html, "html.parser")
    page_title = (soup.title.string or "").strip() if soup.title else ""
    entries = []
    seen = set()

    for video_tag in soup.find_all("video"):
        src = (video_tag.get("src") or "").strip()
        poster = video_tag.get("poster") or ""
        if src and not _is_blob(src):
            abs_url = urljoin(page_url, src)
            key = _normalize_key(abs_url)
            if key not in seen:
                seen.add(key)
                entries.append(_entry(abs_url, page_title, poster, 0, _score_url(abs_url, page_title, bool(poster))))
        for source in video_tag.find_all("source"):
            src = (source.get("src") or "").strip()
            if src and not _is_blob(src):
                abs_url = urljoin(page_url, src)
                key = _normalize_key(abs_url)
                if key not in seen:
                    seen.add(key)
                    entries.append(_entry(abs_url, page_title, poster, 0, _score_url(abs_url, page_title)))

    video_container_re = re.compile(
        r"video|reel|clip|shorts|player|thumb|media|post-item|feed-item|grid-item",
        re.IGNORECASE,
    )
    for container in soup.find_all(class_=video_container_re):
        for link in container.find_all("a", href=True):
            _process_anchor(link, page_url, entries, seen)

    for link in soup.find_all("a", href=True):
        _process_anchor(link, page_url, entries, seen)

    for iframe in soup.find_all("iframe", src=True):
        src = (iframe.get("src") or "").strip()
        if src.startswith("http") and _is_video_page(src):
            key = _normalize_key(src)
            if key not in seen:
                seen.add(key)
                entries.append(_entry(src, page_title, "", 0, _score_url(src, page_title)))

    og_video = soup.find("meta", property="og:video") or soup.find("meta", property="og:video:url")
    if og_video and og_video.get("content"):
        vurl = og_video["content"].strip()
        if vurl.startswith("http"):
            key = _normalize_key(vurl)
            if key not in seen:
                seen.add(key)
                og_img = soup.find("meta", property="og:image")
                thumb = og_img["content"].strip() if og_img and og_img.get("content") else ""
                entries.append(_entry(vurl, page_title, thumb, 0, _score_url(vurl, page_title, bool(thumb))))

    return entries


def _merge_entries(page_url, *lists):
    merged = {}
    page_key = _normalize_key(page_url)

    for lst in lists:
        for item in lst or []:
            url = (item.get("url") or "").strip()
            if _should_skip(url):
                continue
            key = _normalize_key(url)
            score = item.get("_score", _score_url(url, item.get("title"), bool(item.get("thumb"))))
            if _normalize_key(url) == page_key:
                score += 100
            existing = merged.get(key)
            if existing is None or score > existing.get("_score", 0):
                item["_score"] = score
                merged[key] = item

    results = sorted(merged.values(), key=lambda x: x.get("_score", 0), reverse=True)
    for r in results:
        r.pop("_score", None)
    return results[:MAX_RESULTS]


def scrape_videos_combined(page_url, cookies="", html_content="", js_entries_json=""):
    """
    Combined scraper used by BrowserFragment.
    """
    if not page_url or not str(page_url).startswith("http"):
        return json.dumps({"error": "Invalid URL provided."})

    cookies_str = str(cookies).strip() if cookies else ""
    html = (html_content or "").strip()
    js_entries_json = js_entries_json or "[]"

    if needs_expansion(page_url):
        page_url = resolve_url(page_url)
        print(f"[Scraper] Expanded page URL: {page_url}")

    js_entries = _parse_js_entries(js_entries_json, page_url)
    ytdlp_entries = _extract_with_ytdlp(page_url, cookies_str)

    html_entries = []
    if html:
        try:
            html_entries = _scrape_html(html, page_url)
        except Exception as e:
            print(f"[Scraper] HTML parse error: {e}")
    elif DEPS_OK:
        try:
            response = requests.get(
                page_url,
                headers=_build_headers(cookies_str, page_url),
                timeout=25,
                allow_redirects=True,
            )
            if response.status_code < 400:
                html_entries = _scrape_html(response.text, page_url)
            elif response.status_code == 403:
                # Still return JS / yt-dlp results; caller shows browser hint if empty
                print("[Scraper] HTTP 403 — relying on WebView DOM + yt-dlp")
        except Exception as e:
            print(f"[Scraper] Fetch error: {e}")

    entries = _merge_entries(page_url, js_entries, ytdlp_entries, html_entries)

    if not entries:
        page_title = ""
        if html:
            try:
                soup = BeautifulSoup(html, "html.parser")
                page_title = (soup.title.string or "").strip() if soup.title else ""
            except Exception:
                pass
        if not page_title:
            page_title = _extract_title_from_url(page_url)
        entries = [_entry(page_url, page_title, "", 0, 100)]

    print(f"[Scraper] Combined result: {len(entries)} video(s)")
    return json.dumps(entries)


def scrape_videos_from_page(page_url, cookies=""):
    """Legacy entry — fetch page remotely."""
    return scrape_videos_combined(page_url, cookies, "", "")
