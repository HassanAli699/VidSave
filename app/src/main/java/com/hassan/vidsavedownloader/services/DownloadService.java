package com.hassan.vidsavedownloader.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Binder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.hassan.vidsavedownloader.MainActivity;
import com.hassan.vidsavedownloader.R;
import com.hassan.vidsavedownloader.helpers.YtDlpHelper;
import com.hassan.vidsavedownloader.utils.UrlResolver;

public class DownloadService extends Service {

    private static final String TAG = "DownloadService";

    public static final String CLOUDFLARE_ERROR_PREFIX = "CLOUDFLARE_BLOCK:";

    public static final String ACTION_START        = "com.hassan.vidsavedownloader.DOWNLOAD_START";
    public static final String ACTION_PROGRESS     = "com.hassan.vidsavedownloader.DOWNLOAD_PROGRESS";
    public static final String ACTION_COMPLETE     = "com.hassan.vidsavedownloader.DOWNLOAD_COMPLETE";
    public static final String ACTION_ERROR        = "com.hassan.vidsavedownloader.DOWNLOAD_ERROR";
    public static final String ACTION_CANCEL       = "com.hassan.vidsavedownloader.DOWNLOAD_CANCEL";
    public static final String ACTION_OPEN_BROWSER = "com.hassan.vidsavedownloader.OPEN_BROWSER";

    public static final String CHANNEL_ID_PROGRESS = "download_progress_channel";
    public static final String CHANNEL_ID_COMPLETE = "download_complete_channel";

    private static final int NOTIFICATION_ID_PROGRESS = 1001;
    private static final int NOTIFICATION_ID_COMPLETE = 1002;

    public static final String EXTRA_URL         = "url";
    public static final String EXTRA_FOLDER_PATH = "folderPath";
    public static final String EXTRA_PERCENT     = "percent";
    public static final String EXTRA_STATUS      = "status";
    public static final String EXTRA_ERROR       = "error";
    public static final String EXTRA_COOKIES     = "cookies";
    public static final String EXTRA_USER_AGENT  = "extra_user_agent";
    public static final String EXTRA_BROWSER_URL = "browser_url";
    public static final String EXTRA_OPEN_BROWSER = "open_browser";

    public static final String EXTRA_REFERER     = "referer";
    public static final String EXTRA_FROM_BROWSER = "from_browser";

    private final IBinder binder = new LocalBinder();
    private int     lastProgress = -1;
    private String  lastStatus   = "";
    private boolean isDownloading = false;
    private YtDlpHelper ytDlpHelper;
    private String currentDownloadUrl = "";
    private String currentRefererUrl  = "";
    private boolean downloadFromBrowser = false;

    public class LocalBinder extends Binder {
        public DownloadService getService() { return DownloadService.this; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_CANCEL.equals(intent.getAction())) {
            handleCancelDownload();
            return START_NOT_STICKY;
        }

        String url        = intent.getStringExtra(EXTRA_URL);
        String folderPath = intent.getStringExtra(EXTRA_FOLDER_PATH);
        String cookies    = intent.getStringExtra(EXTRA_COOKIES);
        String userAgent  = intent.getStringExtra(EXTRA_USER_AGENT);
        String referer    = intent.getStringExtra(EXTRA_REFERER);
        downloadFromBrowser = intent.getBooleanExtra(EXTRA_FROM_BROWSER, false);

        if (url == null || url.trim().isEmpty()) {
            sendErrorBroadcast("Invalid download URL", "");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (folderPath == null || folderPath.trim().isEmpty()) {
            sendErrorBroadcast("Invalid storage location", url);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (isDownloading) {
            Log.w(TAG, "Download already in progress, ignoring new request");
            return START_NOT_STICKY;
        }

        currentDownloadUrl = url;
        currentRefererUrl  = referer != null ? referer : "";
        Log.d(TAG, "Starting download — URL: " + url);
        if (referer != null && !referer.isEmpty()) {
            Log.d(TAG, "Referer: " + referer);
        }

        startForegroundNotification("Preparing download…", 0, true);
        startDownload(url, folderPath, cookies, userAgent, referer);
        return START_NOT_STICKY;
    }

    private void startForegroundNotification(String content, int percent, boolean indeterminate) {
        Notification notification = buildProgressNotification(content, percent, indeterminate);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID_PROGRESS,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID_PROGRESS, notification);
        }
    }

    private void startDownload(String url, String folderPath, String cookies,
                               String userAgent, String referer) {
        isDownloading = true;
        updateState(0, "Initializing…");
        sendStartBroadcast(url);

        try {
            ytDlpHelper = new YtDlpHelper(this);
            ytDlpHelper.downloadVideoWithFolder(
                    url, folderPath, cookies, userAgent, referer,
                    new YtDlpHelper.DownloadProgressCallback() {

                        @Override
                        public void onProgress(int percent, String statusText) {
                            if (!isDownloading) return;

                            boolean isProcessing = percent == 100 && statusText != null
                                    && (statusText.toLowerCase().contains("making video")
                                    || statusText.toLowerCase().contains("merging")
                                    || statusText.toLowerCase().contains("processing"));

                            String displayStatus = isProcessing
                                    ? "Processing video… Please wait" : statusText;
                            int displayPct = isProcessing ? -1 : percent;

                            updateState(displayPct, displayStatus);
                            updateProgressNotification(displayPct, displayStatus);
                            broadcastProgress(displayPct, displayStatus);
                        }

                        @Override
                        public void onComplete(String filePath) {
                            if (!isDownloading) return;
                            finishDownloadSuccess(filePath);
                        }

                        @Override
                        public void onError(String error) {
                            if (!isDownloading) return;
                            Log.e(TAG, "Download error: " + error);
                            isDownloading = false;
                            updateState(-1, "Error");

                            removeForegroundNotification();

                            if (error != null && error.startsWith(CLOUDFLARE_ERROR_PREFIX)) {
                                String friendlyMsg = error.substring(CLOUDFLARE_ERROR_PREFIX.length());
                                if (downloadFromBrowser) {
                                    showErrorNotification(friendlyMsg);
                                    sendErrorBroadcast(friendlyMsg, currentRefererUrl);
                                } else {
                                    handleCaptchaError(getBrowserFallbackUrl(), friendlyMsg);
                                }
                            } else {
                                showErrorNotification(error);
                                sendErrorBroadcast(error, currentDownloadUrl);
                            }

                            stopSelf();
                        }
                    });
        } catch (Exception e) {
            Log.e(TAG, "Failed to start download", e);
            isDownloading = false;
            removeForegroundNotification();
            sendErrorBroadcast("Failed to start: " + e.getMessage(), url);
            stopSelf();
        }
    }

    private void finishDownloadSuccess(String filePath) {
        isDownloading = false;
        updateState(100, "Download complete");
        removeForegroundNotification();
        showCompletionNotification(filePath);

        Intent complete = new Intent(ACTION_COMPLETE);
        complete.putExtra(EXTRA_FOLDER_PATH, filePath);
        sendAppBroadcast(complete);

        stopSelf();
    }

    private String getBrowserFallbackUrl() {
        if (currentRefererUrl != null && !currentRefererUrl.isEmpty()
                && UrlResolver.isWebViewLoadable(currentRefererUrl)) {
            return currentRefererUrl;
        }
        if (currentDownloadUrl != null && UrlResolver.isTikTokPageUrl(currentDownloadUrl)) {
            return currentDownloadUrl;
        }
        if (currentDownloadUrl != null && UrlResolver.isWebViewLoadable(currentDownloadUrl)
                && !UrlResolver.isDirectMediaUrl(currentDownloadUrl)) {
            return currentDownloadUrl;
        }
        return "https://www.tiktok.com/";
    }

    private void handleCaptchaError(String url, String friendlyMessage) {
        if (UrlResolver.isDirectMediaUrl(url)) {
            showErrorNotification(friendlyMessage);
            sendErrorBroadcast(friendlyMessage, currentRefererUrl);
            return;
        }

        Log.d(TAG, "Site block — redirecting to browser: " + url);

        PendingIntent tapPi = createMainActivityPendingIntent(url, true);

        NotificationCompat.Builder nb =
                new NotificationCompat.Builder(this, CHANNEL_ID_COMPLETE)
                        .setSmallIcon(R.drawable.ic_notification_download)
                        .setContentTitle("Sign in required")
                        .setContentText("Tap to open the browser and try again.")
                        .setStyle(new NotificationCompat.BigTextStyle()
                                .bigText(friendlyMessage
                                        + "\n\nTap this notification to open the in-app browser, "
                                        + "then tap Download and choose \"This page\"."))
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_STATUS)
                        .setVibrate(new long[]{0, 300, 150, 300});

        if (tapPi != null) nb.setContentIntent(tapPi);

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID_COMPLETE, nb.build());

        Intent broadcast = new Intent(ACTION_OPEN_BROWSER);
        broadcast.putExtra(EXTRA_BROWSER_URL, url);
        broadcast.putExtra(EXTRA_ERROR, friendlyMessage);
        sendAppBroadcast(broadcast);
    }

    private void sendStartBroadcast(String url) {
        Intent intent = new Intent(ACTION_START);
        intent.putExtra(EXTRA_URL, url);
        sendAppBroadcast(intent);
    }

    private void broadcastProgress(int percent, String status) {
        Intent intent = new Intent(ACTION_PROGRESS);
        intent.putExtra(EXTRA_PERCENT, percent);
        intent.putExtra(EXTRA_STATUS, status);
        sendAppBroadcast(intent);
    }

    private void handleCancelDownload() {
        isDownloading = false;
        updateState(-1, "Cancelled");
        removeForegroundNotification();

        Intent intent = new Intent(ACTION_CANCEL);
        sendAppBroadcast(intent);

        stopSelf();
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel progress = new NotificationChannel(
                CHANNEL_ID_PROGRESS,
                "Download Progress",
                NotificationManager.IMPORTANCE_LOW);
        progress.setDescription("Shows ongoing download progress");
        progress.enableVibration(false);
        progress.setSound(null, null);
        progress.setShowBadge(false);

        NotificationChannel complete = new NotificationChannel(
                CHANNEL_ID_COMPLETE,
                "Download Complete",
                NotificationManager.IMPORTANCE_HIGH);
        complete.setDescription("Notifies when downloads complete or fail");
        complete.enableVibration(true);
        complete.setShowBadge(true);

        nm.createNotificationChannel(progress);
        nm.createNotificationChannel(complete);
    }

    private Notification buildProgressNotification(String content,
                                                   int percent,
                                                   boolean indeterminate) {
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(this, CHANNEL_ID_PROGRESS)
                        .setSmallIcon(R.drawable.ic_notification_download)
                        .setContentTitle("Downloading video")
                        .setContentText(content)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setCategory(NotificationCompat.CATEGORY_PROGRESS);

        PendingIntent cancelPi = createCancelPendingIntent();
        if (cancelPi != null) {
            builder.addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Cancel",
                    cancelPi);
        }

        if (indeterminate) {
            builder.setProgress(0, 0, true);
        } else {
            builder.setProgress(100, Math.max(0, percent), false);
        }

        return builder.build();
    }

    private void updateProgressNotification(int percent, String status) {
        boolean indeterminate = isIndeterminateStatus(status) || percent < 0;
        String text = indeterminate
                ? (status != null ? status : "Working…")
                : percent + "% downloaded";

        try {
            startForegroundNotification(text, percent, indeterminate);
        } catch (Exception e) {
            Log.e(TAG, "Failed to update foreground notification", e);
        }
    }

    private boolean isIndeterminateStatus(String status) {
        if (status == null) return true;
        String lower = status.toLowerCase();
        return lower.contains("waiting")
                || lower.contains("processing")
                || lower.contains("preparing")
                || lower.contains("initializing")
                || lower.contains("merging");
    }

    private void showCompletionNotification(String filePath) {
        String fileName = filePath != null
                ? filePath.substring(filePath.lastIndexOf('/') + 1)
                : "Video saved successfully";

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(this, CHANNEL_ID_COMPLETE)
                        .setSmallIcon(R.drawable.ic_notification_download)
                        .setContentTitle("Download complete")
                        .setContentText(fileName)
                        .setStyle(new NotificationCompat.BigTextStyle()
                                .bigText("Saved to: " + filePath))
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_STATUS)
                        .setVibrate(new long[]{0, 200, 100, 200});

        PendingIntent pi = createMainActivityPendingIntent(null, false);
        if (pi != null) builder.setContentIntent(pi);

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID_COMPLETE, builder.build());
    }

    private void showErrorNotification(String error) {
        String msg = error != null ? error : "Unknown error occurred";

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(this, CHANNEL_ID_COMPLETE)
                        .setSmallIcon(R.drawable.ic_notification_download)
                        .setContentTitle("Download failed")
                        .setContentText(msg)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(msg))
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_ERROR);

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID_COMPLETE, builder.build());
    }

    private void removeForegroundNotification() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
    }

    private PendingIntent createCancelPendingIntent() {
        Intent intent = new Intent(this, DownloadService.class);
        intent.setAction(ACTION_CANCEL);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getService(this, 1, intent, flags);
    }

    @Nullable
    private PendingIntent createMainActivityPendingIntent(@Nullable String browserUrl,
                                                          boolean openBrowser) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (openBrowser && browserUrl != null && !browserUrl.isEmpty()) {
            intent.putExtra(EXTRA_OPEN_BROWSER, true);
            intent.putExtra(EXTRA_BROWSER_URL, browserUrl);
        }

        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getActivity(this, 2, intent, flags);
    }

    private void sendErrorBroadcast(String message, String url) {
        Intent intent = new Intent(ACTION_ERROR);
        intent.putExtra(EXTRA_ERROR, message != null ? message : "Unknown error");
        if (url != null && !url.isEmpty()) {
            intent.putExtra(EXTRA_BROWSER_URL, url);
        }
        sendAppBroadcast(intent);
    }

    private void sendAppBroadcast(Intent intent) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void updateState(int progress, String status) {
        lastProgress = progress;
        lastStatus = status != null ? status : "";
    }

    public int getLastProgress() { return lastProgress; }
    public String getLastStatus() { return lastStatus; }
    public boolean isDownloading() { return isDownloading; }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onDestroy() {
        isDownloading = false;
        super.onDestroy();
        Log.d(TAG, "Service destroyed");
    }
}
