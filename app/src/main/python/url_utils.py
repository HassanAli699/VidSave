"""URL helpers — expand short links before yt-dlp."""

import re

try:
    import requests
    REQUESTS_OK = True
except ImportError:
    REQUESTS_OK = False

DEFAULT_UA = (
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Mobile Safari/537.36"
)

_SHORT_PATTERNS = (
    r"pin\.it/",
    r"(vt|vm|v)\.tiktok\.com/",
    r"tiktok\.com/t/",
)


def needs_expansion(url):
    if not url:
        return False
    lower = str(url).lower()
    return any(re.search(p, lower) for p in _SHORT_PATTERNS)


def resolve_url(url):
    """Follow redirects and return final http(s) URL."""
    if not url or not REQUESTS_OK:
        return url or ""

    url = str(url).strip()
    headers = {
        "User-Agent": DEFAULT_UA,
        "Accept-Language": "en-US,en;q=0.9",
    }

    try:
        response = requests.get(
            url,
            headers=headers,
            allow_redirects=True,
            timeout=20,
        )
        final = response.url or url
        if final.startswith("http://") or final.startswith("https://"):
            print(f"[url_utils] Resolved: {url} -> {final}")
            return final
    except Exception as e:
        print(f"[url_utils] Resolve failed for {url}: {e}")

    return url
