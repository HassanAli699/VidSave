package com.hassan.vidsave.fragments;

import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;

import static com.hassan.vidsave.utils.Utils.isValidUrl;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.hassan.vidsave.R;
import com.hassan.vidsave.monetization.AdMob;
import com.hassan.vidsave.services.DownloadService;
import com.hassan.vidsave.utils.Utils;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;

public class HomeFragment extends Fragment {
    private static final String TAG = "HomeFragment";
    private static final int REQUEST_PERMISSION = 100;
    private static final int REQUEST_NOTIFICATIONS = 3001;
    private boolean permissionRequestInProgress = false;

    private static final int REQUEST_STORAGE = 2001;
    private boolean storagePermissionRequestInProgress = false;

    private EditText urlInput;
    private Button downloadBtn;
    private ProgressBar downloadProgress;
    private TextView progressText;
    private ImageView pasteLinkBtn;
    private String downloadUrl;
    private boolean isReceiverRegistered = false;
    private Handler mainHandler;
    private boolean isDownloading = false;


    public HomeFragment() {
        // Required empty constructor
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);
        AdMob.loadRewardedAd(requireContext());
        mainHandler = new Handler(Looper.getMainLooper());

        urlInput = view.findViewById(R.id.urlInput);
        downloadBtn = view.findViewById(R.id.downloadBtn);
        downloadProgress = view.findViewById(R.id.downloadProgress);
        progressText = view.findViewById(R.id.progressText);
        pasteLinkBtn = view.findViewById(R.id.pasteLink);


        urlInput.setOnClickListener(v -> pasteLinkBtn.performClick());


        urlInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // no-op
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateUrlHighlight(s != null ? s.toString() : "");
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                // no-op
            }
        });


        setupPasteButton();
        setupDownloadButton();

        // Create VidSave folder on startup
        createVidSaveFolder();

        return view;
    }


    private void updateUrlHighlight(String text) {
        if (text == null || text.trim().isEmpty()) {
            urlInput.setBackgroundResource(R.drawable.edittext_bg);
        } else if (isValidUrl(text)) {
            urlInput.setBackgroundResource(R.drawable.bg_url_valid);
        } else {
            urlInput.setBackgroundResource(R.drawable.bg_url_invalid);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        registerDownloadReceiver();
    }

    @Override
    public void onPause() {
        super.onPause();
        // Don't unregister here - let it persist
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        unregisterDownloadReceiver();
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
    }

    private void createVidSaveFolder() {
        File vidSaveFolder = getVidSaveFolder();
        if (!vidSaveFolder.exists()) {
            vidSaveFolder.mkdirs();
            Log.d(TAG, "VidSave folder created: " + vidSaveFolder.getAbsolutePath());
        }
    }

    private File getVidSaveFolder() {
        // Use app-specific directory (no permissions needed for Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new File(requireContext().getExternalFilesDir(Environment.DIRECTORY_MOVIES), "VidSave");
        } else {
            // For older versions, use public Downloads/VidSave
            return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VidSave");
        }
    }

    private void registerDownloadReceiver() {
        if (isReceiverRegistered) {
            Log.d(TAG, "Receiver already registered, skipping");
            return;
        }

        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(DownloadService.ACTION_PROGRESS);
            filter.addAction(DownloadService.ACTION_COMPLETE);
            filter.addAction(DownloadService.ACTION_ERROR);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireContext().registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                ContextCompat.registerReceiver(requireContext(), downloadReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            }

            isReceiverRegistered = true;
            Log.d(TAG, "BroadcastReceiver registered successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error registering receiver", e);
        }
    }

    private void unregisterDownloadReceiver() {
        if (!isReceiverRegistered) {
            return;
        }

        try {
            if (getContext() != null) {
                getContext().unregisterReceiver(downloadReceiver);
                isReceiverRegistered = false;
                Log.d(TAG, "BroadcastReceiver unregistered successfully");
            }
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Receiver not registered or already unregistered");
            isReceiverRegistered = false;
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering receiver", e);
        }
    }

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @SuppressLint("SetTextI18n")
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                Log.w(TAG, "Received null intent");
                return;
            }

            String action = intent.getAction();
            Log.d(TAG, "★★★ BROADCAST RECEIVED: " + action + " ★★★");

            if (mainHandler != null) {
                mainHandler.post(() -> handleBroadcast(intent, action));
            } else {
                handleBroadcast(intent, action);
            }
        }
    };


    private void handleBroadcast(Intent intent, String action) {
        if (getView() == null || getActivity() == null) return;

        if (DownloadService.ACTION_PROGRESS.equals(action)) {
            isDownloading = true; // download started / ongoing

            int percent = intent.getIntExtra("percent", -1);
            String status = intent.getStringExtra("status");

            downloadProgress.setVisibility(VISIBLE);
            progressText.setVisibility(VISIBLE);

            // Check if percent == 100 but still processing
            boolean isProcessing = status != null && status.equals("Making Video Upload Ready Wait...");

            if (isProcessing) {
                downloadProgress.setIndeterminate(true);
                progressText.setText("Processing video… Please wait");
            } else if (percent >= 0) {
                downloadProgress.setIndeterminate(false);
                downloadProgress.setProgress(percent);
                progressText.setText(percent + "% Downloaded");
            } else {
                downloadProgress.setIndeterminate(true);
                progressText.setText(status != null ? status : "Working...");
            }

        } else if (DownloadService.ACTION_COMPLETE.equals(action)) {
            isDownloading = false; // finished
            progressText.setText("Download Complete ✅\nYour video is now ready to be shared!");
            downloadProgress.setVisibility(GONE);

        } else if (DownloadService.ACTION_ERROR.equals(action)) {
            isDownloading = false; // failed
            String error = intent.getStringExtra("error");

            downloadProgress.setVisibility(GONE);
            progressText.setVisibility(VISIBLE);
            progressText.setText(error != null ? error : "Unknown error ❌");

            Utils.showAnimatedToast(getActivity(), error != null ? error : "Something went wrong", R.drawable.alert_error, Utils.ToastDuration.SHORT);
        }
    }


    private String resolveFinalUrl(String shortUrl) {
        try {
            String currentUrl = shortUrl;
            int maxRedirects = 5;

            for (int i = 0; i < maxRedirects; i++) {
                HttpURLConnection connection = (HttpURLConnection) new URL(currentUrl).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.connect();

                int code = connection.getResponseCode();
                if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location != null && !location.isEmpty()) {
                        if (!location.startsWith("http")) {
                            URL base = new URL(currentUrl);
                            location = new URL(base, location).toString();
                        }
                        currentUrl = location;
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }
            return currentUrl;
        } catch (Exception e) {
            Log.e(TAG, "Error resolving URL", e);
            return shortUrl;
        }
    }

    private void setupPasteButton() {
        pasteLinkBtn.setOnClickListener(v -> {
            if ("clear".equals(pasteLinkBtn.getTag())) {
                urlInput.setText("");
                pasteLinkBtn.setImageResource(R.drawable.copy);
                pasteLinkBtn.setTag("paste");
            } else {
                ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null && clipboard.hasPrimaryClip()) {
                    ClipData clipData = clipboard.getPrimaryClip();
                    if (clipData != null && clipData.getItemCount() > 0) {
                        CharSequence pastedText = clipData.getItemAt(0).coerceToText(getContext());
                        if (pastedText != null) {
                            urlInput.setText(pastedText.toString());
                            pasteLinkBtn.setImageResource(R.drawable.clear);
                            pasteLinkBtn.setTag("clear");
                            Utils.showAnimatedToast(getActivity(), "Link pasted", R.drawable.check_mark, Utils.ToastDuration.SHORT);
                        }
                    }
                } else {
                    Utils.showAnimatedToast(getActivity(), "Clipboard is empty", R.drawable.alert_error, Utils.ToastDuration.SHORT);
                }
            }
        });
    }

    private void setupDownloadButton() {
        downloadBtn.setOnClickListener(v -> {

            if (isDownloading) {
                Utils.showAnimatedToast(getActivity(), "A download is already in progress", R.drawable.warning, Utils.ToastDuration.SHORT);
                return;
            }

            if (checkPermissions()) {
                registerDownloadReceiver();
                downloadProgress.setVisibility(VISIBLE);
                downloadProgress.setIndeterminate(true);
                progressText.setVisibility(VISIBLE);
                progressText.setText("Starting Download...");
                startDownload();
            } else {
                requestPermissions();
            }
        });
    }

    private void checkStoragePermissionAndContinue(Runnable onGranted) {

        // Android 10+ → app-specific storage → no permission needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onGranted.run();
            return;
        }

        // Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED) {
                onGranted.run();
                return;
            }

            if (shouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_VIDEO)) {
                Utils.showAnimatedToast(getActivity(), "We need video access to save downloaded videos to your device", R.drawable.warning, Utils.ToastDuration.LONG);
            }

            storagePermissionRequestInProgress = true;
            requestPermissions(new String[]{Manifest.permission.READ_MEDIA_VIDEO}, REQUEST_STORAGE);
            return;
        }

        // Android 9 and below
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            onGranted.run();
            return;
        }

        if (shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            Utils.showAnimatedToast(getActivity(), "Storage permission is required to save videos", R.drawable.warning, Utils.ToastDuration.LONG);
        }

        storagePermissionRequestInProgress = true;
        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
    }


    private void startDownload() {

        if (permissionRequestInProgress || storagePermissionRequestInProgress) return;

        checkNotificationPermissionAndContinue(() -> {
            checkStoragePermissionAndContinue(() -> {

                String inputUrl = urlInput.getText().toString().trim();

                if (inputUrl.isEmpty() || !isValidUrl(inputUrl)) {
                    updateUrlHighlight(inputUrl);
                    downloadProgress.setVisibility(INVISIBLE);
                    progressText.setVisibility(INVISIBLE);

                    Utils.showAnimatedToast(getActivity(), "Please enter a valid URL", R.drawable.warning, Utils.ToastDuration.SHORT);
                    return;
                }

                if (inputUrl.contains("pin.it/")) {
                    new Thread(() -> {
                        String expandedUrl = resolveFinalUrl(inputUrl);
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                downloadUrl = expandedUrl;
                                proceedWithDownload();
                            });
                        }
                    }).start();
                } else {
                    downloadUrl = inputUrl;
                    proceedWithDownload();
                }
            });
        });
    }


    private void proceedWithDownload() {
        if (downloadUrl == null) return;

        Utils.showAnimatedToast(getActivity(), "Watch a short ad to start download", R.drawable.warning, Utils.ToastDuration.SHORT);


        // Show rewarded ad BEFORE download
        AdMob.showRewardedAd(requireActivity(), () -> {
            // User watched ad successfully (reward earned)
            File vidSaveFolder = getVidSaveFolder();
            startDownloadService(vidSaveFolder.getAbsolutePath());
        });
    }

    private void startDownloadService(String folderPath) {
        Log.d(TAG, "Starting DownloadService for: " + downloadUrl + " to folder: " + folderPath);

        Intent serviceIntent = new Intent(getContext(), DownloadService.class);
        serviceIntent.putExtra("url", downloadUrl);
        serviceIntent.putExtra("folderPath", folderPath);

        ContextCompat.startForegroundService(requireContext(), serviceIntent);
    }

    private boolean checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return true; // No permission needed for app-specific directory
        } else {
            return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{Manifest.permission.READ_MEDIA_VIDEO}, REQUEST_PERMISSION);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // No permission needed
            startDownload();
        } else {
            ActivityCompat.requestPermissions(requireActivity(), new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_PERMISSION);
        }
    }

    private void handlePermanentDenial(String permission, String message) {
        if (!shouldShowRequestPermissionRationale(permission)) {
            Utils.showAnimatedToast(getActivity(), message + ". Enable it from Settings.", R.drawable.alert_error, Utils.ToastDuration.LONG);
            openAppSettings();
        } else {
            Utils.showAnimatedToast(getActivity(), message, R.drawable.alert_error, Utils.ToastDuration.SHORT);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_NOTIFICATIONS) {
            permissionRequestInProgress = false;

            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startDownload();
            } else {
                handlePermanentDenial(Manifest.permission.POST_NOTIFICATIONS, "Enable notifications from Settings to continue downloads");
            }
        }

        if (requestCode == REQUEST_STORAGE) {
            storagePermissionRequestInProgress = false;

            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startDownload();
            } else {
                handlePermanentDenial(permissions[0], "Storage permission is required to save videos");
            }
        }
    }

    private void openAppSettings() {
        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(android.net.Uri.fromParts("package", requireContext().getPackageName(), null));
        startActivity(intent);
    }

    private void checkNotificationPermissionAndContinue(Runnable onGranted) {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onGranted.run();
            return;
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            onGranted.run();
            return;
        }

        // Show rationale if needed
        if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            Utils.showAnimatedToast(getActivity(), "We use notifications to show download progress and completion", R.drawable.warning, Utils.ToastDuration.LONG);
        }

        permissionRequestInProgress = true;

        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
    }


}