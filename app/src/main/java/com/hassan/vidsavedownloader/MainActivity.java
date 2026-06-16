package com.hassan.vidsavedownloader;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.hassan.vidsavedownloader.fragments.BrowserFragment;
import com.hassan.vidsavedownloader.fragments.DownloadedFragment;
import com.hassan.vidsavedownloader.fragments.HomeFragment;
import com.hassan.vidsavedownloader.fragments.SettingsFragment;
import com.hassan.vidsavedownloader.monetization.AdMob;
import com.hassan.vidsavedownloader.services.DownloadService;
import com.hassan.vidsavedownloader.utils.AppUpdateHelper;
import com.hassan.vidsavedownloader.utils.UrlResolver;
import com.hassan.vidsavedownloader.utils.Utils;

public class MainActivity extends AppCompatActivity {

    public interface DownloadUiListener {
        void onDownloadStart(String url);
        void onDownloadProgress(int percent, String status);
        void onDownloadComplete(String filePath);
        void onDownloadError(String error, String sourceUrl);
        void onDownloadCancel();
        void onOpenBrowser(String url, String message);
        default void onDownloadIdle() {}
    }

    private static final int STATE_IDLE         = 0;
    private static final int STATE_PROGRESS     = 1;
    private static final int STATE_COMPLETE     = 2;
    private static final int STATE_ERROR        = 3;
    private static final int STATE_CANCEL       = 4;
    private static final int STATE_OPEN_BROWSER = 5;

    private AppUpdateHelper appUpdateHelper;
    private DownloadUiListener downloadUiListener;
    private boolean downloadReceiverRegistered = false;

    private int downloadUiState = STATE_IDLE;
    private boolean downloadInProgress = false;
    private int lastDownloadProgress = -1;
    private String lastDownloadStatus = "";
    private String lastCompletePath = "";
    private String lastErrorSourceUrl = "";

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            handleDownloadBroadcast(intent.getAction(), intent);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        appUpdateHelper = new AppUpdateHelper(this);

        BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
        LinearLayout adViewContainer = findViewById(R.id.ad_view_container);

        AdMob.adMobSDKInit(this);
        AdMob.setBannerAD(adViewContainer, this);

        bottomNav.setOnItemSelectedListener(item -> {
            Fragment selectedFragment;
            int id = item.getItemId();
            if (id == R.id.nav_home) {
                selectedFragment = new HomeFragment();
            } else if (id == R.id.nav_downloaded) {
                selectedFragment = new DownloadedFragment();
            } else if (id == R.id.nav_settings) {
                selectedFragment = new SettingsFragment();
            } else {
                return false;
            }
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragmentContainer, selectedFragment)
                    .commit();
            return true;
        });

        if (savedInstanceState == null) {
            bottomNav.setSelectedItemId(R.id.nav_home);
        }

        handleNotificationIntent(getIntent());
        registerDownloadReceiver();
    }

    @Override
    protected void onDestroy() {
        unregisterDownloadReceiver();
        if (appUpdateHelper != null) {
            appUpdateHelper.destroy();
        }
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNotificationIntent(intent);
    }

    public void setDownloadUiListener(DownloadUiListener listener) {
        downloadUiListener = listener;
        if (listener != null) {
            syncDownloadUi(listener);
        }
    }

    private void syncDownloadUi(DownloadUiListener listener) {
        switch (downloadUiState) {
            case STATE_PROGRESS:
                listener.onDownloadStart(null);
                listener.onDownloadProgress(lastDownloadProgress, lastDownloadStatus);
                break;
            case STATE_COMPLETE:
                listener.onDownloadComplete(lastCompletePath);
                downloadUiState = STATE_IDLE;
                break;
            case STATE_ERROR:
                listener.onDownloadError(lastDownloadStatus, lastErrorSourceUrl);
                downloadUiState = STATE_IDLE;
                break;
            case STATE_CANCEL:
                listener.onDownloadCancel();
                downloadUiState = STATE_IDLE;
                break;
            case STATE_OPEN_BROWSER:
                listener.onOpenBrowser(lastErrorSourceUrl, lastDownloadStatus);
                downloadUiState = STATE_IDLE;
                break;
            default:
                listener.onDownloadIdle();
                break;
        }
    }

    public void clearDownloadUiListener(DownloadUiListener listener) {
        if (downloadUiListener == listener) {
            downloadUiListener = null;
        }
    }

    public boolean isDownloadInProgress() {
        return downloadInProgress;
    }

    public int getLastDownloadProgress() {
        return lastDownloadProgress;
    }

    public String getLastDownloadStatus() {
        return lastDownloadStatus;
    }

    public void openBrowserForUrl(String url) {
        if (url == null || url.trim().isEmpty()) return;

        if (UrlResolver.isDirectMediaUrl(url)) {
            Utils.showAnimatedToast(this,
                    "Video file links can't open in the browser. Go back and tap Download on the page.",
                    R.drawable.alert_error, Utils.ToastDuration.LONG);
            return;
        }

        new Thread(() -> {
            String resolved = url;
            try {
                if (UrlResolver.needsExpansion(url)) {
                    resolved = UrlResolver.resolve(url);
                }
            } catch (Exception e) {
                android.util.Log.w("MainActivity", "URL resolve failed, using original", e);
            }

            final String loadUrl = resolved;
            if (!UrlResolver.isWebViewLoadable(loadUrl) || UrlResolver.isDirectMediaUrl(loadUrl)) {
                runOnUiThread(() -> Utils.showAnimatedToast(this,
                        "Could not open this link in the browser",
                        R.drawable.alert_error, Utils.ToastDuration.LONG));
                return;
            }

            runOnUiThread(() -> {
                BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
                bottomNav.setSelectedItemId(R.id.nav_home);

                getSupportFragmentManager().beginTransaction()
                        .replace(R.id.fragmentContainer, BrowserFragment.newInstance(loadUrl))
                        .addToBackStack("browser")
                        .commit();
            });
        }).start();
    }

    private void handleNotificationIntent(Intent intent) {
        if (intent == null) return;

        if (intent.getBooleanExtra(DownloadService.EXTRA_OPEN_BROWSER, false)) {
            String url = intent.getStringExtra(DownloadService.EXTRA_BROWSER_URL);
            openBrowserForUrl(url);
            intent.removeExtra(DownloadService.EXTRA_OPEN_BROWSER);
            intent.removeExtra(DownloadService.EXTRA_BROWSER_URL);
        }
    }

    private void registerDownloadReceiver() {
        if (downloadReceiverRegistered) return;

        IntentFilter filter = new IntentFilter();
        filter.addAction(DownloadService.ACTION_START);
        filter.addAction(DownloadService.ACTION_PROGRESS);
        filter.addAction(DownloadService.ACTION_COMPLETE);
        filter.addAction(DownloadService.ACTION_ERROR);
        filter.addAction(DownloadService.ACTION_CANCEL);
        filter.addAction(DownloadService.ACTION_OPEN_BROWSER);

        LocalBroadcastManager.getInstance(this).registerReceiver(downloadReceiver, filter);
        downloadReceiverRegistered = true;
    }

    private void unregisterDownloadReceiver() {
        if (!downloadReceiverRegistered) return;
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(downloadReceiver);
        } catch (Exception ignored) {
            // Receiver may already be unregistered.
        }
        downloadReceiverRegistered = false;
    }

    private void handleDownloadBroadcast(String action, Intent intent) {
        if (DownloadService.ACTION_START.equals(action)) {
            downloadInProgress = true;
            downloadUiState = STATE_PROGRESS;
            lastDownloadProgress = 0;
            lastDownloadStatus = "Starting download…";
            String url = intent.getStringExtra(DownloadService.EXTRA_URL);
            if (downloadUiListener != null) {
                downloadUiListener.onDownloadStart(url);
            }
            return;
        }

        if (DownloadService.ACTION_PROGRESS.equals(action)) {
            downloadInProgress = true;
            downloadUiState = STATE_PROGRESS;
            lastDownloadProgress = intent.getIntExtra(DownloadService.EXTRA_PERCENT, -1);
            lastDownloadStatus = intent.getStringExtra(DownloadService.EXTRA_STATUS);
            if (downloadUiListener != null) {
                downloadUiListener.onDownloadProgress(lastDownloadProgress, lastDownloadStatus);
            }
            return;
        }

        if (DownloadService.ACTION_COMPLETE.equals(action)) {
            downloadInProgress = false;
            downloadUiState = STATE_COMPLETE;
            lastDownloadProgress = 100;
            lastDownloadStatus = "Download complete";
            lastCompletePath = intent.getStringExtra(DownloadService.EXTRA_FOLDER_PATH);
            if (downloadUiListener != null) {
                downloadUiListener.onDownloadComplete(lastCompletePath);
                downloadUiState = STATE_IDLE;
            }
            return;
        }

        if (DownloadService.ACTION_ERROR.equals(action)) {
            downloadInProgress = false;
            downloadUiState = STATE_ERROR;
            lastDownloadProgress = -1;
            lastDownloadStatus = intent.getStringExtra(DownloadService.EXTRA_ERROR);
            lastErrorSourceUrl = intent.getStringExtra(DownloadService.EXTRA_BROWSER_URL);
            if (downloadUiListener != null) {
                downloadUiListener.onDownloadError(lastDownloadStatus, lastErrorSourceUrl);
                downloadUiState = STATE_IDLE;
            } else if (Utils.isBrowserRequiredError(lastDownloadStatus) && lastErrorSourceUrl != null) {
                openBrowserForUrl(lastErrorSourceUrl);
            }
            return;
        }

        if (DownloadService.ACTION_CANCEL.equals(action)) {
            downloadInProgress = false;
            downloadUiState = STATE_CANCEL;
            lastDownloadProgress = -1;
            lastDownloadStatus = "Cancelled";
            if (downloadUiListener != null) {
                downloadUiListener.onDownloadCancel();
                downloadUiState = STATE_IDLE;
            }
            return;
        }

        if (DownloadService.ACTION_OPEN_BROWSER.equals(action)) {
            downloadInProgress = false;
            downloadUiState = STATE_OPEN_BROWSER;
            lastDownloadProgress = -1;
            lastErrorSourceUrl = intent.getStringExtra(DownloadService.EXTRA_BROWSER_URL);
            lastDownloadStatus = intent.getStringExtra(DownloadService.EXTRA_ERROR);
            if (downloadUiListener != null) {
                downloadUiListener.onOpenBrowser(lastErrorSourceUrl, lastDownloadStatus);
                downloadUiState = STATE_IDLE;
            } else {
                openBrowserForUrl(lastErrorSourceUrl);
            }
        }
    }

    public void checkForUpdatesManually() {
        if (appUpdateHelper != null) {
            appUpdateHelper.checkForUpdateManually();
        }
    }

    /** Opens the in-app Library tab (video gallery). */
    public void openLibraryTab() {
        BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
        if (bottomNav != null) {
            bottomNav.setSelectedItemId(R.id.nav_downloaded);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (appUpdateHelper != null) {
            appUpdateHelper.checkOnResume();
        }
    }
}
