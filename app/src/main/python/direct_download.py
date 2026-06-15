"""Detect direct media URLs and download with browser cookies/headers."""

import os
import re

DIRECT_MEDIA_PATTERNS = (
    r"\.(mp4|m3u8|webm|mov|m4v)(\?|$)",
    r"mime_type=video",
    r"tiktok\.com/video/tos/",
    r"v\d+-webapp-prime\.tiktok\.com",
    r"googlevideo\.com/videoplayback",
)


def is_direct_media_url(url):
    if not url:
        return False
    lower = str(url).lower()
    return any(re.search(p, lower) for p in DIRECT_MEDIA_PATTERNS)


def is_tiktok_page_url(url):
    if not url:
        return False
    lower = str(url).lower()
    return "tiktok.com" in lower and (
        "/video/" in lower or "/@" in lower
    ) and not is_direct_media_url(url)


def download_direct_file(url, folder_path, callback, cookies_str="", user_agent="", referer=""):
    """Stream-download a direct video URL using WebView cookies and headers."""
    import requests

    ua = (user_agent or "").strip() or (
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0.0.0 Mobile Safari/537.36"
    )
    ref = (referer or "").strip() or "https://www.tiktok.com/"

    headers = {
        "User-Agent": ua,
        "Accept": "*/*",
        "Accept-Language": "en-US,en;q=0.9",
        "Referer": ref,
        "Origin": "https://www.tiktok.com" if "tiktok" in ref.lower() or "tiktok" in url.lower() else ref,
        "Sec-Fetch-Dest": "video",
        "Sec-Fetch-Mode": "no-cors",
        "Sec-Fetch-Site": "cross-site",
    }
    if cookies_str and cookies_str.strip():
        headers["Cookie"] = cookies_str.strip()
        print(f"[direct] Using {len(cookies_str)} chars of cookies")

    print(f"[direct] GET {url[:120]}...")
    callback.update_progress(5, "Connecting…")

    response = requests.get(
        url,
        headers=headers,
        stream=True,
        timeout=90,
        allow_redirects=True,
    )
    if response.status_code == 403:
        raise Exception(
            "HTTP 403 — TikTok blocked the direct file. Choose 'This page' in the download list."
        )
    response.raise_for_status()

    total = int(response.headers.get("content-length") or 0)
    ext = ".mp4"
    ctype = (response.headers.get("content-type") or "").lower()
    if "webm" in ctype:
        ext = ".webm"

    from downloader import get_unique_filename, sanitize_filename

    out_name = sanitize_filename("tiktok_video") + ext
    out_path = get_unique_filename(os.path.join(str(folder_path), out_name))

    downloaded = 0
    chunk_size = 256 * 1024
    with open(out_path, "wb") as out_file:
        for chunk in response.iter_content(chunk_size=chunk_size):
            if not chunk:
                continue
            out_file.write(chunk)
            downloaded += len(chunk)
            if total > 0:
                pct = min(99, int(downloaded * 100 / total))
            else:
                pct = min(99, downloaded // (1024 * 512))  # rough estimate
            callback.update_progress(pct, f"Downloading… {pct}%")

    print(f"[direct] Saved to {out_path}")
    callback.update_progress(100, "Complete!")
    callback.completed(out_path)
