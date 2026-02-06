package com.hassan.vidsave.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.hassan.vidsave.R;
import com.hassan.vidsave.helpers.YtDlpHelper;

public class DownloadService extends Service {

    private static final String TAG = "DownloadService";

    // Actions
    public static final String ACTION_START = "com.hassan.vidsave.DOWNLOAD_START";
    public static final String ACTION_PROGRESS = "com.hassan.vidsave.DOWNLOAD_PROGRESS";
    public static final String ACTION_COMPLETE = "com.hassan.vidsave.DOWNLOAD_COMPLETE";
    public static final String ACTION_ERROR = "com.hassan.vidsave.DOWNLOAD_ERROR";
    public static final String ACTION_CANCEL = "com.hassan.vidsave.DOWNLOAD_CANCEL";

    // Notification channels
    public static final String CHANNEL_ID_PROGRESS = "download_progress_channel";
    public static final String CHANNEL_ID_COMPLETE = "download_complete_channel";

    // Notification IDs
    private static final int NOTIFICATION_ID_PROGRESS = 1001;
    private static final int NOTIFICATION_ID_COMPLETE = 1002;

    // Intent extras
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_FOLDER_PATH = "folderPath";
    public static final String EXTRA_PERCENT = "percent";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_ERROR = "error";

    private final IBinder binder = new LocalBinder();
    private int lastProgress = -1;
    private String lastStatus = "";
    private boolean isDownloading = false;
    private YtDlpHelper ytDlpHelper;

    public class LocalBinder extends Binder {
        public DownloadService getService() {
            return DownloadService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");
        createNotificationChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            Log.e(TAG, "Received null intent");
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();

        if (ACTION_CANCEL.equals(action)) {
            handleCancelDownload();
            return START_NOT_STICKY;
        }

        // Validate required extras
        String url = intent.getStringExtra(EXTRA_URL);
        String folderPath = intent.getStringExtra(EXTRA_FOLDER_PATH);

        if (url == null || url.trim().isEmpty()) {
            Log.e(TAG, "Invalid URL provided");
            sendErrorBroadcast("Invalid download URL");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (folderPath == null || folderPath.trim().isEmpty()) {
            Log.e(TAG, "Invalid folder path provided");
            sendErrorBroadcast("Invalid storage location");
            stopSelf();
            return START_NOT_STICKY;
        }

        Log.d(TAG, "Starting download - URL: " + url + ", Folder: " + folderPath);

        // Start foreground service
        startForegroundService();

        // Begin download
        startDownload(url, folderPath);

        return START_NOT_STICKY;
    }

    private void startForegroundService() {
        Notification notification = buildProgressNotification("Preparing download...", 0, true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID_PROGRESS,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            );
        } else {
            startForeground(NOTIFICATION_ID_PROGRESS, notification);
        }
    }

    private void startDownload(String url, String folderPath) {
        if (isDownloading) {
            Log.w(TAG, "Download already in progress");
            return;
        }

        isDownloading = true;
        updateState(0, "Initializing...");

        try {
            ytDlpHelper = new YtDlpHelper(this);
            ytDlpHelper.downloadVideoWithFolder(url, folderPath, new YtDlpHelper.DownloadProgressCallback() {
                @Override
                public void onProgress(int percent, String statusText) {
                    Log.d(TAG, "DownloadService.onProgress called: " + percent + "% - " + statusText);

                    if (!isDownloading) {
                        Log.d(TAG, "Download was cancelled, ignoring progress");
                        return;
                    }

                    // Check if download is technically complete but still processing
                    boolean isProcessing = percent == 100 && statusText != null &&
                            statusText.toLowerCase().contains("making video");

                    // Update UI state
                    if (isProcessing) {
                        // Indeterminate progress for UI
                        updateState(-1, "Processing video… Please wait"); // -1 means indeterminate
                    } else {
                        updateState(percent, statusText);
                    }

                    // Update notification
                    if (isProcessing) {
                        updateProgressNotification(-1, "Processing video… Please wait"); // indeterminate
                    } else {
                        updateProgressNotification(percent, statusText);
                    }

                    // Broadcast to UI
                    Intent progressIntent = new Intent(ACTION_PROGRESS);
                    if (isProcessing) {
                        progressIntent.putExtra(EXTRA_PERCENT, -1); // indeterminate
                        progressIntent.putExtra(EXTRA_STATUS, "Processing video… Please wait");
                    } else {
                        progressIntent.putExtra(EXTRA_PERCENT, percent);
                        progressIntent.putExtra(EXTRA_STATUS, statusText);
                    }
                    sendBroadcast(progressIntent);

                    Log.d(TAG, "Progress broadcast sent: " + percent + "%");
                }


                @Override
                public void onComplete(String filePath) {
                    Log.d(TAG, "DownloadService.onComplete called: " + filePath);

                    if (!isDownloading) return;

                    Log.d(TAG, "Download completed successfully");
                    updateState(100, "Download complete");
                    isDownloading = false;

                    showCompletionNotification(filePath);

                    Intent completeIntent = new Intent(ACTION_COMPLETE);
                    completeIntent.putExtra(EXTRA_FOLDER_PATH, filePath);
                    sendBroadcast(completeIntent);

                    stopForeground(false);
                    stopSelf();
                }

                @Override
                public void onError(String error) {
                    Log.e(TAG, "DownloadService.onError called: " + error);

                    updateState(-1, "Error");
                    isDownloading = false;

                    showErrorNotification(error);
                    sendErrorBroadcast(error);

                    stopForeground(true);
                    stopSelf();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to start download", e);
            isDownloading = false;
            sendErrorBroadcast("Failed to initialize download: " + e.getMessage());
            stopForeground(true);
            stopSelf();
        }
    }

    private void handleCancelDownload() {
        Log.d(TAG, "Cancelling download");
        isDownloading = false;
        updateState(-1, "Cancelled");
        stopForeground(true);
        stopSelf();
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            Log.e(TAG, "NotificationManager is null");
            return;
        }

        NotificationChannel progressChannel = new NotificationChannel(
                CHANNEL_ID_PROGRESS,
                "Download Progress",
                NotificationManager.IMPORTANCE_LOW
        );
        progressChannel.setDescription("Shows ongoing download progress");
        progressChannel.enableVibration(false);
        progressChannel.setSound(null, null);
        progressChannel.setShowBadge(false);

        NotificationChannel completeChannel = new NotificationChannel(
                CHANNEL_ID_COMPLETE,
                "Download Complete",
                NotificationManager.IMPORTANCE_HIGH
        );
        completeChannel.setDescription("Notifies when downloads complete or fail");
        completeChannel.enableVibration(true);
        completeChannel.setShowBadge(true);

        manager.createNotificationChannel(progressChannel);
        manager.createNotificationChannel(completeChannel);
    }

    private Notification buildProgressNotification(String content, int percent, boolean indeterminate) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID_PROGRESS)
                .setSmallIcon(R.drawable.evd_logo)
                .setContentTitle("Downloading Video")
                .setContentText(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        PendingIntent cancelIntent = createCancelPendingIntent();
        if (cancelIntent != null) {
            builder.addAction(R.drawable.evd_logo, "Cancel", cancelIntent);
        }

        if (indeterminate) {
            builder.setProgress(0, 0, true);
        } else {
            builder.setProgress(100, percent, false);
        }

        return builder.build();
    }

    private void updateProgressNotification(int percent, String status) {
        boolean isIndeterminate = isIndeterminateStatus(status);
        String displayText = isIndeterminate ? status : percent + "% Downloaded";

        Notification notification = buildProgressNotification(displayText, percent, isIndeterminate);

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            try {
                manager.notify(NOTIFICATION_ID_PROGRESS, notification);
            } catch (Exception e) {
                Log.e(TAG, "Failed to update notification", e);
            }
        }
    }

    private boolean isIndeterminateStatus(String status) {
        if (status == null) return true;

        String lowerStatus = status.toLowerCase();
        return lowerStatus.contains("waiting") ||
                lowerStatus.contains("processing") ||
                lowerStatus.contains("preparing") ||
                lowerStatus.contains("initializing");
    }

    private void showCompletionNotification(String filePath) {
        stopForeground(false);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID_COMPLETE)
                .setSmallIcon(R.drawable.evd_logo)
                .setContentTitle("Download Complete")
                .setContentText("Video saved successfully")
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText("Video saved to: " + filePath))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVibrate(new long[]{0, 200, 100, 200});

        PendingIntent contentIntent = createAppPendingIntent();
        if (contentIntent != null) {
            builder.setContentIntent(contentIntent);
        }

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID_COMPLETE, builder.build());
        }
    }

    private void showErrorNotification(String error) {
        stopForeground(false);

        String errorMessage = error != null ? error : "Unknown error occurred";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID_COMPLETE)
                .setSmallIcon(R.drawable.evd_logo)
                .setContentTitle("Download Failed")
                .setContentText(errorMessage)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(errorMessage))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH);

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID_COMPLETE, builder.build());
        }
    }

    private PendingIntent createCancelPendingIntent() {
        Intent intent = new Intent(this, DownloadService.class);
        intent.setAction(ACTION_CANCEL);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        return PendingIntent.getService(this, 0, intent, flags);
    }

    private PendingIntent createAppPendingIntent() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (intent == null) return null;

        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        return PendingIntent.getActivity(this, 0, intent, flags);
    }

    private void sendErrorBroadcast(String message) {
        Intent intent = new Intent(ACTION_ERROR);
        intent.putExtra(EXTRA_ERROR, message != null ? message : "Unknown error");
        sendBroadcast(intent);
    }

    private void updateState(int progress, String status) {
        lastProgress = progress;
        lastStatus = status != null ? status : "";
    }

    public int getLastProgress() {
        return lastProgress;
    }

    public String getLastStatus() {
        return lastStatus;
    }

    public boolean isDownloading() {
        return isDownloading;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isDownloading = false;
        Log.d(TAG, "Service destroyed");
    }
}