package com.hassan.vidsavedownloader.utils;

import android.net.Uri;
import android.util.Log;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Expands short links (TikTok, Pinterest, etc.) to their final http(s) URL.
 */
public class UrlResolver {

    private static final String TAG = "UrlResolver";

    private static final String MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/124.0.0.0 Mobile Safari/537.36";

    private static final Pattern TIKTOK_SHORT = Pattern.compile(
            "https?://((vt|vm|v)\\.tiktok\\.com/|www\\.tiktok\\.com/t/)",
            Pattern.CASE_INSENSITIVE);

    private UrlResolver() {}

    /** @return true if the URL should be expanded before download / browser load. */
    public static boolean needsExpansion(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase(Locale.US);
        return lower.contains("pin.it/")
                || TIKTOK_SHORT.matcher(lower).find();
    }

    /** Expand short links; returns the original URL on failure. */
    public static String resolve(String inputUrl) {
        if (inputUrl == null || inputUrl.trim().isEmpty()) return inputUrl;
        try {
            return resolveThrows(inputUrl.trim());
        } catch (Exception e) {
            Log.w(TAG, "Could not expand URL, using original: " + inputUrl, e);
            return inputUrl.trim();
        }
    }

    public static String resolveThrows(String inputUrl) throws IOException {
        String current = inputUrl.trim();
        String lastHttp = isHttpUrl(current) ? current : null;

        for (int i = 0; i < 10; i++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(12_000);
            conn.setReadTimeout(12_000);
            conn.setRequestProperty("User-Agent", MOBILE_UA);
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            conn.connect();

            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isEmpty()) break;

                String next = location.startsWith("http")
                        ? location
                        : new URL(new URL(current), location).toString();

                if (isHttpUrl(next)) {
                    lastHttp = next;
                    current = next;
                } else {
                    // App deep link (tiktok://, snssdk:// …) — stop, use last http URL
                    Log.d(TAG, "Redirect to non-http scheme ignored: " + next);
                    break;
                }
            } else {
                conn.disconnect();
                break;
            }
        }

        if (lastHttp != null) return lastHttp;
        return isHttpUrl(current) ? current : inputUrl;
    }

    /** Safe for WebView — only http and https. */
    public static boolean isWebViewLoadable(String url) {
        if (url == null) return false;
        return isHttpUrl(url);
    }

    /** CDN / direct video file — must not open in WebView or expand via redirect. */
    public static boolean isDirectMediaUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.US);
        return lower.contains("mime_type=video")
                || lower.contains(".mp4")
                || lower.contains(".m3u8")
                || lower.contains(".webm")
                || lower.contains("tiktok.com/video/tos/")
                || lower.contains("-webapp-prime.tiktok.com/video/");
    }

    /** TikTok watch page (not CDN). */
    public static boolean isTikTokPageUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.US);
        return lower.contains("tiktok.com")
                && (lower.contains("/video/") || lower.contains("/@"))
                && !isDirectMediaUrl(url);
    }

    public static boolean isWebViewLoadable(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        if (scheme == null) return false;
        String lower = scheme.toLowerCase(Locale.US);
        return "http".equals(lower) || "https".equals(lower);
    }

    private static boolean isHttpUrl(String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }
}
