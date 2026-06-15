package com.hassan.vidsavedownloader.helpers;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class YtDlpHelper {

    private static final String TAG = "YtDlpHelper";
    private final Context context;

    public YtDlpHelper(Context context) {
        this.context = context;
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(context));
        }
    }

    /**
     * @param url        Video page URL
     * @param folderPath Destination folder on device
     * @param cookies    WebView cookie string for the domain (may be null)
     * @param callback   Progress / completion callbacks
     */
    public void downloadVideoWithFolder(String url,
                                        String folderPath,
                                        String cookies,
                                        String userAgent,
                                        String referer,
                                        DownloadProgressCallback callback) {
        new Thread(() -> {
            try {
                File tempFolder = new File(context.getCacheDir(), "download_tmp");
                if (tempFolder.exists()) {
                    deleteRecursive(tempFolder);
                }
                tempFolder.mkdirs();

                Python py = Python.getInstance();
                PyObject downloader = py.getModule("downloader");

                ProgressBridge bridge = new ProgressBridge(new DownloadProgressCallback() {
                    @Override
                    public void onProgress(int percent, String statusText) {
                        android.util.Log.d(TAG, "Progress: " + percent + "% — " + statusText);
                        callback.onProgress(percent, statusText);
                    }

                    @Override
                    public void onComplete(String mergedFilePath) {
                        try {
                            saveDownloadedFile(
                                    new File(mergedFilePath),
                                    folderPath,
                                    callback
                            );
                        } catch (Exception e) {
                            callback.onError("❌ Error saving video: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onError(String error) {
                        android.util.Log.e(TAG, "Download error: " + error);
                        callback.onError(error);
                    }
                });

                downloader.callAttr(
                        "download_video_with_progress",
                        url,
                        tempFolder.getAbsolutePath(),
                        bridge,
                        cookies != null ? cookies : "",
                        userAgent != null ? userAgent : "",
                        referer != null ? referer : ""
                );

            } catch (Exception e) {
                android.util.Log.e(TAG, "Exception in downloadVideoWithFolder", e);
                callback.onError("❌ Error: " + e.getMessage());
            }
        }).start();
    }

    // ── File handling ──────────────────────────────────────────────────────

    private void saveDownloadedFile(File inputFile,
                                    String folderPath,
                                    DownloadProgressCallback callback) {
        try {
            File outputFolder = new File(folderPath);
            if (!outputFolder.exists()) outputFolder.mkdirs();

            String fileName = inputFile.getName();

            // Normalise to .mp4 extension (no re-encode)
            if (!fileName.toLowerCase().endsWith(".mp4")) {
                int dot = fileName.lastIndexOf('.');
                fileName = (dot > 0)
                        ? fileName.substring(0, dot) + ".mp4"
                        : fileName + ".mp4";
            }

            File outFile = new File(outputFolder, fileName);

            // Prefer rename (instant); fall back to copy+delete across filesystems
            if (!inputFile.renameTo(outFile)) {
                copyFile(inputFile, outFile);
                inputFile.delete();
            }

            // Clean up temp folder
            File parent = inputFile.getParentFile();
            if (parent != null && parent.exists()) {
                deleteRecursive(parent);
            }

            // Tell the media scanner so the file appears in Gallery / Files
            Intent scan = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            scan.setData(Uri.fromFile(outFile));
            context.sendBroadcast(scan);

            callback.onComplete(outFile.getAbsolutePath());

        } catch (Exception e) {
            callback.onError("❌ Error saving video: " + e.getMessage());
        }
    }

    private void copyFile(File src, File dst) throws IOException {
        try (InputStream in  = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    private void deleteRecursive(File fileOrDir) {
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        fileOrDir.delete();
    }

    // ── Public interfaces ──────────────────────────────────────────────────

    public interface DownloadProgressCallback {
        void onProgress(int percent, String statusText);
        void onComplete(String folderPath);
        void onError(String error);
    }

    public static class ProgressBridge {

        private final DownloadProgressCallback callback;

        public ProgressBridge(DownloadProgressCallback callback) {
            this.callback = callback;
            android.util.Log.d("ProgressBridge", "Bridge created");
        }

        public void update_progress(int percent, String status) {
            android.util.Log.d("ProgressBridge", "update_progress: " + percent + "% — " + status);
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> callback.onProgress(percent, status));
        }

        public void completed(String filePath) {
            android.util.Log.d("ProgressBridge", "completed: " + filePath);
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> callback.onComplete(filePath));
        }

        public void error(String errorMsg) {
            android.util.Log.d("ProgressBridge", "error: " + errorMsg);
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> callback.onError(errorMsg));
        }
    }
}