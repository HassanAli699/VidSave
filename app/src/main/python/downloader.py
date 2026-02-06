import os
import pathlib
import yt_dlp as yt
from java import jclass
import re

# Java bindings
FFmpegKit = jclass("com.antonkarpenko.ffmpegkit.FFmpegKit")

downloaded_files = []
java_callback = None
current_stage = None
last_reported_progress = 0

def my_hook(d):
    global last_reported_progress
    try:
        if java_callback:
            if d['status'] == 'downloading':
                percent = 0
                if d.get('_percent_str'):
                    try:
                        raw = d.get('_percent_str', '').replace('%', '').replace('~', '').strip()
                        percent = int(float(raw)) if raw.replace('.', '').isdigit() else 0
                    except:
                        percent = 0

                # Calculate combined progress (video = 0-50%, audio = 50-90%)
                if current_stage == "video":
                    total_percent = int(percent * 0.5)
                elif current_stage == "audio":
                    total_percent = 50 + int(percent * 0.4)
                else:
                    total_percent = percent

                # CRITICAL: Never go backward
                if total_percent < last_reported_progress:
                    total_percent = last_reported_progress
                else:
                    last_reported_progress = total_percent

                print(f"[Python] Progress: stage={current_stage} percent={percent} total_percent={total_percent}")
                java_callback.update_progress(total_percent, f"Downloading... {total_percent}%")

            elif d['status'] == 'finished':
                filename = d.get('filename', 'unknown')
                print(f"[Python] Download finished: {filename}")
                downloaded_files.append(filename)

                if current_stage == "video":
                    last_reported_progress = 50
                    java_callback.update_progress(50, "Video downloaded, getting audio...")
                elif current_stage == "audio":
                    last_reported_progress = 90
                    java_callback.update_progress(90, "Processing...")

    except Exception as e:
        print(f"[Python] Progress hook error: {e}")

def sanitize_filename(name):
    """
    Remove or replace characters that are problematic for filenames and FFmpeg
    """
    # Remove or replace special characters
    name = name.replace('"', '')  # Remove quotes
    name = name.replace("'", '')  # Remove single quotes
    name = name.replace('/', '_')  # Replace forward slash
    name = name.replace('\\', '_')  # Replace backslash
    name = name.replace(':', '-')  # Replace colon
    name = name.replace('*', '')  # Remove asterisk
    name = name.replace('?', '')  # Remove question mark
    name = name.replace('<', '')  # Remove less than
    name = name.replace('>', '')  # Remove greater than
    name = name.replace('|', '-')  # Replace pipe
    name = name.replace('#', '')  # Remove hash
    name = name.replace('&', 'and')  # Replace ampersand
    name = name.replace('%', '')  # Remove percent
    name = name.replace('$', '')  # Remove dollar
    name = name.replace('@', 'at')  # Replace at
    name = name.replace('!', '')  # Remove exclamation

    # Remove excessive whitespace
    name = ' '.join(name.split())

    # Limit length to avoid filesystem issues
    if len(name) > 200:
        name = name[:200]

    return name.strip()

def get_unique_filename(path):
    path = pathlib.Path(path)
    counter = 1
    while path.exists():
        path = path.with_name(f"{path.stem}_{counter}{path.suffix}")
        counter += 1
    return str(path)

def format_user_friendly_error(e):
    err_str = str(e).lower()

    if "unable to download video" in err_str or "http error" in err_str:
        return "Network error — Check your internet connection or try again later."
    elif "url" in err_str and "invalid" in err_str:
        return "Invalid link — Please check if the video URL is correct."
    elif "video unavailable" in err_str or "private" in err_str:
        return "This video is private or unavailable."
    elif "no such file" in err_str or "file not found" in err_str:
        return "File not found — The video/audio file could not be located."
    elif "ffmpeg" in err_str or "invalid argument" in err_str:
        return "Video processing failed — FFmpeg could not merge the files."
    elif "permission" in err_str:
        return "Storage permission error — Please allow storage access."
    else:
        return "Unexpected error — Please try again."

def download_video_with_progress(url, folder_path, callback):
    global downloaded_files, java_callback, current_stage, last_reported_progress
    downloaded_files = []
    java_callback = callback
    last_reported_progress = 0

    try:
        java_callback.update_progress(0, "Waiting For Response...")

        out_folder = str(folder_path)
        os.makedirs(out_folder, exist_ok=True)

        # Get video info and sanitize title
        with yt.YoutubeDL({"quiet": True}) as ydl:
            info_dict = ydl.extract_info(url, download=False)
            raw_title = str(info_dict.get('title', 'video'))
            video_title = sanitize_filename(raw_title)
            print(f"[Python] Original title: {raw_title}")
            print(f"[Python] Sanitized title: {video_title}")

        # VIDEO (0-50%)
        video_tmpl = os.path.join(out_folder, f'{video_title}_video.%(ext)s')
        current_stage = "video"
        with yt.YoutubeDL({
            "outtmpl": video_tmpl,
            "format": "bv*[ext=mp4][vcodec^=avc1]/bv*[ext=mp4]/bv*",
            "progress_hooks": [my_hook],
            "cachedir": False,
            "noplaylist": True,
            "user_agent": (
                    "Mozilla/5.0 (Linux; Android 13; Mobile) "
                    "AppleWebKit/537.36 (KHTML, like Gecko) "
                    "Chrome/120.0.0.0 Mobile Safari/537.36"
                ),
        }) as ydl:
            ydl.download([url])

        # AUDIO (50-90%)
        audio_tmpl = os.path.join(out_folder, f'{video_title}_audio.%(ext)s')
        current_stage = "audio"
        with yt.YoutubeDL({
            "outtmpl": audio_tmpl,
            "format": "ba*[ext=m4a]/ba*",
            "progress_hooks": [my_hook],
            "cachedir": False,
            "noplaylist": True,
            "user_agent": (
                                "Mozilla/5.0 (Linux; Android 13; Mobile) "
                                "AppleWebKit/537.36 (KHTML, like Gecko) "
                                "Chrome/120.0.0.0 Mobile Safari/537.36"
                            ),
        }) as ydl:
            ydl.download([url])

        java_callback.update_progress(90, "Merging video and audio...")

        # Check what we downloaded
        if len(downloaded_files) == 0:
            raise Exception("No files downloaded")

        elif len(downloaded_files) == 1:
            single_file = downloaded_files[0]
            if single_file.lower().endswith(('.mp4', '.mov', '.mkv', '.avi', '.webm')):
                # Rename to sanitized filename
                sanitized_path = os.path.join(out_folder, f"{video_title}.mp4")
                final_path = get_unique_filename(sanitized_path)
                if single_file != final_path:
                    os.rename(single_file, final_path)
                    single_file = final_path

                java_callback.update_progress(100, "Complete!")
                java_callback.completed(single_file)
                return
            else:
                raise Exception("Downloaded file is not a supported video format")

        elif len(downloaded_files) >= 2:
            # Identify video and audio files
            video_file = None
            audio_file = None

            for file in downloaded_files:
                if '_video' in os.path.basename(file):
                    video_file = file
                elif '_audio' in os.path.basename(file):
                    audio_file = file

            if not video_file:
                video_file = downloaded_files[0]
            if not audio_file:
                audio_file = downloaded_files[1]

            print(f"[Python] Video file: {video_file}")
            print(f"[Python] Audio file: {audio_file}")

            # Create sanitized output filename
            merged_output = get_unique_filename(
                os.path.join(out_folder, f"{video_title}.mp4")
            )

            print(f"[Python] Merged output: {merged_output}")

            # FFmpeg command with proper escaping
            command = (
                f'-y '
                f'-i "{video_file}" '
                f'-i "{audio_file}" '
                f'-map 0:v:0 '
                f'-map 1:a:0 '
                f'-c:v copy '
                f'-c:a aac '
                f'-b:a 128k '
                f'-ar 44100 '
                f'-ac 2 '
                f'-shortest '
                f'-movflags +faststart '
                f'"{merged_output}"'
            )

            print(f"[Python] FFmpeg command: {command}")

            session = FFmpegKit.execute(command)
            ret_code = session.getReturnCode()

            if ret_code.isValueSuccess():
                print(f"[Python] Merge successful: {merged_output}")

                # Clean up temporary files
                try:
                    if os.path.exists(video_file):
                        os.remove(video_file)
                        print(f"[Python] Deleted: {video_file}")
                    if os.path.exists(audio_file):
                        os.remove(audio_file)
                        print(f"[Python] Deleted: {audio_file}")
                except Exception as cleanup_err:
                    print(f"[Python] Cleanup warning: {cleanup_err}")

                java_callback.update_progress(100, "Complete!")
                java_callback.completed(merged_output)
                return
            else:
                error_trace = session.getFailStackTrace()
                print(f"[Python] FFmpeg failed: {error_trace}")
                raise Exception(f"FFmpeg merge failed")

        else:
            raise Exception("Unexpected download result")

    except Exception as e:
        import traceback
        print(f"[Python] Full error: {e}")
        print(traceback.format_exc())
        if java_callback:
            java_callback.error(format_user_friendly_error(e))