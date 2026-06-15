import os
import pathlib
import yt_dlp as yt
from java import jclass
import re
from url_utils import resolve_url, needs_expansion

FFmpegKit = jclass("com.antonkarpenko.ffmpegkit.FFmpegKit")

downloaded_files = []
java_callback    = None
current_stage    = None
last_reported_progress = 0

# Special prefix Java checks to trigger browser redirect instead of error toast
CLOUDFLARE_ERROR_PREFIX = "CLOUDFLARE_BLOCK:"


def my_hook(d):
    global last_reported_progress
    try:
        if not java_callback:
            return

        if d['status'] == 'downloading':
            percent = 0
            raw = d.get('_percent_str', '').replace('%', '').replace('~', '').strip()
            try:
                percent = int(float(raw)) if raw.replace('.', '').isdigit() else 0
            except Exception:
                percent = 0

            if current_stage == "video":
                total_percent = int(percent * 0.5)
            elif current_stage == "audio":
                total_percent = 50 + int(percent * 0.4)
            else:
                total_percent = percent

            if total_percent < last_reported_progress:
                total_percent = last_reported_progress
            else:
                last_reported_progress = total_percent

            print(f"[Python] Progress: stage={current_stage} raw={percent}% total={total_percent}%")
            java_callback.update_progress(total_percent, f"Downloading... {total_percent}%")

        elif d['status'] == 'finished':
            filename = d.get('filename', 'unknown')
            print(f"[Python] Stage finished: {filename}")
            downloaded_files.append(filename)

            if current_stage == "video":
                last_reported_progress = 50
                java_callback.update_progress(50, "Video downloaded, getting audio...")
            elif current_stage == "audio":
                last_reported_progress = 90
                java_callback.update_progress(90, "Merging...")

    except Exception as e:
        print(f"[Python] Hook error: {e}")


def sanitize_filename(name):
    replacements = {
        '"': '', "'": '', '/': '_', '\\': '_', ':': '-',
        '*': '', '?': '', '<': '', '>': '', '|': '-',
        '#': '', '&': 'and', '%': '', '$': '', '@': 'at', '!': ''
    }
    for k, v in replacements.items():
        name = name.replace(k, v)
    name = ' '.join(name.split())
    return name[:200].strip()


def get_unique_filename(path):
    path = pathlib.Path(path)
    counter = 1
    while path.exists():
        path = path.with_name(f"{path.stem}_{counter}{path.suffix}")
        counter += 1
    return str(path)


def is_cloudflare_error(e):
    """Detect Cloudflare / bot-protection blocks (not CDN 403 on direct files)."""
    msg = str(e).lower()
    return (
        "cloudflare" in msg
        or "anti-bot" in msg
        or "bot detection" in msg
        or "429" in msg
        or "too many requests" in msg
        or "rate limit" in msg
        or ("challenge" in msg and "captcha" in msg)
    )


def needs_browser(e, url=""):
    """True when the user should open the in-app browser and retry."""
    from direct_download import is_direct_media_url, is_tiktok_page_url

    if url and is_direct_media_url(url):
        return False

    msg = str(e).lower()

    if "confirm you are on the latest version" in msg:
        return False

    if is_cloudflare_error(e):
        return True

    # yt-dlp [generic] 403 on a CDN file — not a browser/captcha issue
    if "[generic]" in msg and (is_direct_media_url(url) or "403" in msg):
        return False

    if "[tiktok]" in msg and is_tiktok_page_url(url):
        return any(token in msg for token in (
            "status code 0", "video not available", "unable to extract",
            "login", "cookies",
        ))

    return any(token in msg for token in (
        "sign in", "login required", "log in", "please log in",
        "confirm you're not a bot", "verify you are human",
        "members only", "age-restricted",
        "authentication required", "session expired",
    ))


def format_user_friendly_error(e, url=""):
    from direct_download import is_direct_media_url

    msg = str(e).lower()

    if needs_browser(e, url):
        if "[tiktok]" in msg or "tiktok" in (url or "").lower():
            hint = (
                "TikTok blocked direct download. Opening the in-app browser — "
                "wait for the video page to load, then tap Download and choose "
                "'This page'."
            )
        else:
            hint = (
                "This site blocked the download. Opening the in-app browser — "
                "complete any sign-in, then tap Download on that page."
            )
        return CLOUDFLARE_ERROR_PREFIX + hint

    if is_direct_media_url(url) and "403" in msg:
        return (
            "Video file blocked (403). In the browser, tap Download and select "
            "'This page' instead of the direct file link."
        )
    elif "unable to download" in msg or "http error" in msg:
        return "Network error — Check your internet connection and try again."
    elif "url" in msg and "invalid" in msg:
        return "Invalid link — Please check the video URL."
    elif "video unavailable" in msg or "private" in msg:
        return "This video is private or unavailable."
    elif "no such file" in msg or "file not found" in msg:
        return "File not found — The video/audio file could not be located."
    elif "ffmpeg" in msg or "invalid argument" in msg:
        return "Video processing failed — FFmpeg could not merge the files."
    elif "permission" in msg:
        return "Storage permission error — Please allow storage access."
    else:
        return f"Download failed — Please try again. ({str(e)[:120]})"


def build_ydl_opts(extra, cookies_str=None, user_agent=None, referer=None):
    """Build base yt-dlp options, injecting WebView cookies when available."""
    ua = (user_agent or "").strip() or (
        "Mozilla/5.0 (Linux; Android 13; Mobile) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0.0.0 Mobile Safari/537.36"
    )
    http_headers = {
        "User-Agent": ua,
        "Accept-Language": "en-US,en;q=0.9",
    }
    ref = (referer or "").strip()
    if ref:
        http_headers["Referer"] = ref
    if cookies_str and cookies_str.strip():
        http_headers["Cookie"] = cookies_str.strip()
        print(f"[Python] Injecting {len(cookies_str)} chars of WebView cookies")

    base = {
        "cachedir": False,
        "noplaylist": True,
        "http_headers": http_headers,
    }
    base.update(extra)
    return base


def _save_video_only(video_file, out_folder, video_title):
    """
    Fallback: rename/copy the raw video file when merge fails.
    Never raises — returns None on total failure.
    """
    try:
        import shutil
        src_ext   = os.path.splitext(video_file)[1].lower() or ".mp4"
        dest_name = get_unique_filename(
            os.path.join(out_folder, f"{video_title}{src_ext}")
        )
        if os.path.exists(video_file):
            try:
                os.rename(video_file, dest_name)
            except OSError:
                shutil.copy2(video_file, dest_name)
                os.remove(video_file)
            print(f"[Python] Video-only saved: {dest_name}")
            return dest_name
    except Exception as e:
        print(f"[Python] _save_video_only failed: {e}")
    return None


def download_video_with_progress(url, folder_path, callback, cookies=None,
                                 user_agent="", referer=""):
    global downloaded_files, java_callback, current_stage, last_reported_progress

    from direct_download import (
        is_direct_media_url, is_tiktok_page_url, download_direct_file,
    )

    downloaded_files       = []
    java_callback          = callback
    last_reported_progress = 0
    cookies_str            = str(cookies).strip() if cookies else ""
    user_agent_str         = str(user_agent).strip() if user_agent else ""
    referer_str            = str(referer).strip() if referer else ""

    if needs_expansion(url):
        url = resolve_url(url)
        print(f"[Python] Using expanded URL: {url}")

    out_folder = str(folder_path)
    os.makedirs(out_folder, exist_ok=True)

    # ── Direct CDN / .mp4 file (from browser video element) ───────────────
    if is_direct_media_url(url):
        try:
            print(f"[Python] Direct media download: {url[:100]}...")
            ref = referer_str or "https://www.tiktok.com/"
            download_direct_file(
                url, out_folder, callback,
                cookies_str, user_agent_str, ref,
            )
            return
        except Exception as direct_err:
            print(f"[Python] Direct download failed: {direct_err}")
            if java_callback:
                java_callback.error(format_user_friendly_error(direct_err, url))
            return

    try:
        java_callback.update_progress(0, "Waiting for response...")

        tiktok_extra = {}
        if "tiktok.com" in url.lower():
            tiktok_extra["referer"] = referer_str or "https://www.tiktok.com/"

        ref_for_ydl = referer_str or tiktok_extra.get("referer")

        # ── Fetch video metadata ──────────────────────────────────────────
        with yt.YoutubeDL(build_ydl_opts(
                {"quiet": True, **tiktok_extra}, cookies_str, user_agent_str, ref_for_ydl)) as ydl:
            info_dict   = ydl.extract_info(url, download=False)
            raw_title   = str(info_dict.get('title', 'video'))
            video_title = sanitize_filename(raw_title)
            print(f"[Python] Title: '{raw_title}' → '{video_title}'")

        # ── VIDEO download (0 → 50%) ──────────────────────────────────────
        video_tmpl    = os.path.join(out_folder, f'{video_title}_video.%(ext)s')
        current_stage = "video"

        with yt.YoutubeDL(build_ydl_opts({
            "outtmpl": video_tmpl,
            "format": "bv*[ext=mp4][vcodec^=avc1]/bv*[ext=mp4]/bv*",
            "progress_hooks": [my_hook],
            **tiktok_extra,
        }, cookies_str, user_agent_str, ref_for_ydl)) as ydl:
            ydl.download([url])

        # ── AUDIO download (50 → 90%) ─────────────────────────────────────
        audio_tmpl    = os.path.join(out_folder, f'{video_title}_audio.%(ext)s')
        current_stage = "audio"

        audio_ok = True
        try:
            with yt.YoutubeDL(build_ydl_opts({
                "outtmpl": audio_tmpl,
                "format": "ba*[ext=m4a]/ba*",
                "progress_hooks": [my_hook],
                **tiktok_extra,
            }, cookies_str, user_agent_str, ref_for_ydl)) as ydl:
                ydl.download([url])
        except Exception as audio_err:
            print(f"[Python] Audio download failed (will save video-only): {audio_err}")
            audio_ok = False

        java_callback.update_progress(90, "Merging video and audio...")

        # ── Identify downloaded files ─────────────────────────────────────
        video_file = next(
            (f for f in downloaded_files if '_video' in os.path.basename(f)
             and os.path.exists(f)),
            None
        )
        audio_file = next(
            (f for f in downloaded_files if '_audio' in os.path.basename(f)
             and os.path.exists(f)),
            None
        ) if audio_ok else None

        # ── No files at all ───────────────────────────────────────────────
        if not downloaded_files:
            raise Exception("No files downloaded")

        # ── Single file (already muxed by yt-dlp) ────────────────────────
        if len(downloaded_files) == 1:
            single = downloaded_files[0]
            if os.path.exists(single):
                ext   = os.path.splitext(single)[1].lower() or ".mp4"
                final = get_unique_filename(
                    os.path.join(out_folder, f"{video_title}{ext}")
                )
                try:
                    os.rename(single, final)
                except OSError:
                    import shutil
                    shutil.copy2(single, final)
                    os.remove(single)
                java_callback.update_progress(100, "Complete!")
                java_callback.completed(final)
            else:
                raise Exception("Downloaded file not found on disk")
            return

        # ── Two files: try to merge ───────────────────────────────────────
        if video_file and audio_file:
            merged_output = get_unique_filename(
                os.path.join(out_folder, f"{video_title}.mp4")
            )
            print(f"[Python] Merging: {video_file} + {audio_file} → {merged_output}")

            command = (
                f'-y '
                f'-i "{video_file}" '
                f'-i "{audio_file}" '
                f'-map 0:v:0 -map 1:a:0 '
                f'-c:v copy -c:a aac -b:a 128k -ar 44100 -ac 2 '
                f'-shortest -movflags +faststart '
                f'"{merged_output}"'
            )

            merge_succeeded = False
            try:
                session         = FFmpegKit.execute(command)
                ret_code        = session.getReturnCode()
                merge_succeeded = ret_code.isValueSuccess()
            except Exception as ffmpeg_err:
                print(f"[Python] FFmpegKit exception: {ffmpeg_err}")
                merge_succeeded = False

            if merge_succeeded:
                for tmp in [video_file, audio_file]:
                    try:
                        if os.path.exists(tmp):
                            os.remove(tmp)
                    except Exception as ce:
                        print(f"[Python] Cleanup warning: {ce}")
                java_callback.update_progress(100, "Complete!")
                java_callback.completed(merged_output)
                return

            # Merge failed → save video-only
            print("[Python] FFmpeg merge failed — saving video track alone")
            java_callback.update_progress(95, "Merge failed, saving video...")
            saved = _save_video_only(video_file, out_folder, video_title)
            try:
                if audio_file and os.path.exists(audio_file):
                    os.remove(audio_file)
            except Exception:
                pass
            if saved:
                java_callback.update_progress(100, "Saved (no audio)!")
                java_callback.completed(saved)
            else:
                raise Exception("Both merge and video-only save failed")
            return

        # ── Only video, no audio ──────────────────────────────────────────
        if video_file and not audio_file:
            print("[Python] No audio file — saving video track only")
            java_callback.update_progress(95, "Saving video...")
            saved = _save_video_only(video_file, out_folder, video_title)
            if saved:
                java_callback.update_progress(100, "Saved (no audio)!")
                java_callback.completed(saved)
            else:
                raise Exception("Failed to save video-only file")
            return

        raise Exception("Unexpected state: no video file available")

    except Exception as e:
        import traceback
        print(f"[Python] Error: {e}")
        print(traceback.format_exc())
        if java_callback:
            java_callback.error(format_user_friendly_error(e, url))