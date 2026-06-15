package com.hassan.vidsavedownloader.utils;

import android.content.Context;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.hassan.vidsavedownloader.models.LinkPreview;

import org.json.JSONObject;

import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LinkPreviewFetcher {

    public interface PreviewCallback {
        void onSuccess(LinkPreview preview);
        void onError();
    }

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static void fetch(Context context, String urlString, PreviewCallback callback) {
        executor.execute(() -> {
            try {
                ensurePython(context);
                Python py = Python.getInstance();
                PyObject module = py.getModule("link_preview");
                PyObject result = module.callAttr("get_link_preview", urlString, "");
                String json = result != null ? result.toString() : "";

                JSONObject obj = new JSONObject(json);
                if (obj.has("error")) {
                    postFallback(context, urlString, callback);
                    return;
                }

                String title = obj.optString("title", "");
                if (title.isEmpty()) {
                    postFallback(context, urlString, callback);
                    return;
                }

                LinkPreview preview = new LinkPreview(
                        title,
                        obj.optString("description", ""),
                        obj.optString("thumbnail", ""),
                        obj.optString("domain", extractDomain(urlString))
                );
                postSuccess(callback, preview);
            } catch (Exception e) {
                postFallback(context, urlString, callback);
            }
        });
    }

    private static void ensurePython(Context context) {
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(context.getApplicationContext()));
        }
    }

    private static void postFallback(Context context, String urlString, PreviewCallback callback) {
        try {
            String domain = extractDomain(urlString);
            String platform = domain.split("\\.")[0];
            if (!platform.isEmpty()) {
                platform = Character.toUpperCase(platform.charAt(0)) + platform.substring(1);
            }
            LinkPreview preview = new LinkPreview(
                    platform + " video",
                    urlString,
                    "https://www.google.com/s2/favicons?sz=128&domain=" + domain,
                    domain
            );
            postSuccess(callback, preview);
        } catch (Exception e) {
            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            handler.post(callback::onError);
        }
    }

    private static String extractDomain(String urlString) throws Exception {
        return new URL(urlString).getHost().replaceFirst("^www\\.", "");
    }

    private static void postSuccess(PreviewCallback callback, LinkPreview preview) {
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.post(() -> callback.onSuccess(preview));
    }
}
