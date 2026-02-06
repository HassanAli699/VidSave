package com.hassan.vidsave.helpers;

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

    public void downloadVideoWithFolder(String url, String folderPath, DownloadProgressCallback callback) {
        new Thread(() -> {
            try {
                File tempFolder = new File(context.getCacheDir(), "download_tmp");
                if (tempFolder.exists()) {
                    deleteRecursive(tempFolder);
                }
                tempFolder.mkdirs();

                Python py = Python.getInstance();
                PyObject downloader = py.getModule("downloader");

                // Create bridge that forwards to the outer callback
                ProgressBridge bridge = new ProgressBridge(new DownloadProgressCallback() {
                    @Override
                    public void onProgress(int percent, String statusText) {
                        android.util.Log.d("YtDlpHelper", "Inner callback received: " + percent + "% - " + statusText);
                        callback.onProgress(percent, statusText);
                    }

                    @Override
                    public void onComplete(String mergedFilePath) {
                        try {
                            File downloadedFile = new File(mergedFilePath);
                            saveDownloadedFile(downloadedFile, folderPath, callback);
                        } catch (Exception e) {
                            callback.onError("❌ Error saving video: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onError(String error) {
                        android.util.Log.d("YtDlpHelper", "Inner callback error: " + error);
                        callback.onError(error);
                    }
                });

                downloader.callAttr("download_video_with_progress",
                        url,
                        tempFolder.getAbsolutePath(),
                        bridge
                );

            } catch (Exception e) {
                android.util.Log.e("YtDlpHelper", "Exception in downloadVideoWithFolder", e);
                callback.onError("❌ Error: " + e.getMessage());
            }
        }).start();
    }


    private void saveDownloadedFile(
            File inputFile,
            String folderPath,
            DownloadProgressCallback callback
    ) {
        try {
            File outputFolder = new File(folderPath);
            if (!outputFolder.exists()) outputFolder.mkdirs();

            String fileName = inputFile.getName();

            // Ensure .mp4 extension (rename only, no processing)
            if (!fileName.toLowerCase().endsWith(".mp4")) {
                int dotIndex = fileName.lastIndexOf('.');
                fileName = dotIndex > 0
                        ? fileName.substring(0, dotIndex) + ".mp4"
                        : fileName + ".mp4";
            }

            File outFile = new File(outputFolder, fileName);

            // 🔹 MOVE file (fast, no re-encode, no remux)
            if (!inputFile.renameTo(outFile)) {
                // Fallback: copy + delete (for cross-filesystem cases)
                copyFile(inputFile, outFile);
                inputFile.delete();
            }

            // Cleanup temp directory
            File parentDir = inputFile.getParentFile();
            if (parentDir != null && parentDir.exists()) {
                deleteRecursive(parentDir);
            }

            // Notify media scanner (Gallery, WhatsApp, etc.)
            Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            mediaScanIntent.setData(Uri.fromFile(outFile));
            context.sendBroadcast(mediaScanIntent);

            callback.onComplete(outFile.getAbsolutePath());

        } catch (Exception e) {
            callback.onError("❌ Error saving video: " + e.getMessage());
        }
    }

    private void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
        }
    }

    private void deleteRecursive(File fileOrDir) {
        if (fileOrDir.isDirectory()) {
            for (File child : fileOrDir.listFiles()) {
                deleteRecursive(child);
            }
        }
        fileOrDir.delete();
    }

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
            android.util.Log.d("ProgressBridge", "update_progress called: " + percent + "% - " + status);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                android.util.Log.d("ProgressBridge", "Calling callback.onProgress: " + percent);
                callback.onProgress(percent, status);
            });
        }

        public void completed(String folderPath) {
            android.util.Log.d("ProgressBridge", "completed called: " + folderPath);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                callback.onComplete(folderPath);
            });
        }

        public void error(String errorMsg) {
            android.util.Log.d("ProgressBridge", "error called: " + errorMsg);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                callback.onError(errorMsg);
            });
        }
    }
}