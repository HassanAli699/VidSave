package com.hassan.vidsavedownloader.helpers;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class VideoExtractorHelper {

    private static final String TAG = "VideoExtractorHelper";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Data model ─────────────────────────────────────────────────────────

    public static class VideoEntry {
        public final String url;
        public final String title;
        public final String thumb;
        public final int    durationSecs;

        public VideoEntry(String url, String title, String thumb, int durationSecs) {
            this.url          = url;
            this.title        = title;
            this.thumb        = thumb;
            this.durationSecs = durationSecs;
        }

        /** e.g. "3:45" or "1:02:30" */
        public String formattedDuration() {
            if (durationSecs <= 0) return "";
            int h = durationSecs / 3600;
            int m = (durationSecs % 3600) / 60;
            int s = durationSecs % 60;
            if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
            return String.format("%d:%02d", m, s);
        }
    }

    public interface ExtractionCallback {
        void onSuccess(List<VideoEntry> videos);
        void onError(String message);
    }

    // ── Constructor ────────────────────────────────────────────────────────

    public VideoExtractorHelper(Context context) {
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(context));
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Runs yt-dlp extract_info on a background thread, then delivers results
     * on the main thread via {@code callback}.
     *
     * @param pageUrl  The URL currently shown in the WebView
     * @param cookies  Cookie header string from CookieManager (may be null)
     */
    public void extractFromPage(String pageUrl,
                                String cookies,
                                ExtractionCallback callback) {
        new Thread(() -> {
            try {
                Python py = Python.getInstance();
                PyObject module = py.getModule("video_extractor");

                PyObject result = module.callAttr(
                        "extract_videos_from_page",
                        pageUrl,
                        cookies != null ? cookies : ""
                );

                String json = result != null ? result.toString() : "{}";
                Log.d(TAG, "Raw result: " + json.substring(0, Math.min(json.length(), 200)));

                mainHandler.post(() -> parseAndDeliver(json, pageUrl, callback));

            } catch (Exception e) {
                Log.e(TAG, "extractFromPage failed", e);
                mainHandler.post(() ->
                        callback.onError("Extraction failed: " + e.getMessage()));
            }
        }).start();
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private void parseAndDeliver(String json,
                                 String fallbackUrl,
                                 ExtractionCallback callback) {
        try {
            String trimmed = json.trim();

            // Error object from Python
            if (trimmed.startsWith("{")) {
                JSONObject obj = new JSONObject(trimmed);
                if (obj.has("error")) {
                    callback.onError(obj.getString("error"));
                    return;
                }
            }

            JSONArray arr = new JSONArray(trimmed);
            List<VideoEntry> entries = new ArrayList<>();

            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String url = o.optString("url", "").trim();
                if (url.isEmpty()) continue;

                entries.add(new VideoEntry(
                        url,
                        o.optString("title", ""),
                        o.optString("thumb", ""),
                        o.optInt("duration", 0)
                ));
            }

            if (entries.isEmpty()) {
                callback.onError("No downloadable videos found on this page.");
            } else {
                callback.onSuccess(entries);
            }

        } catch (Exception e) {
            Log.e(TAG, "JSON parse error", e);
            // Last resort: hand back the raw page URL so the user can still try
            List<VideoEntry> fallback = new ArrayList<>();
            fallback.add(new VideoEntry(fallbackUrl, "", "", 0));
            callback.onSuccess(fallback);
        }
    }
}