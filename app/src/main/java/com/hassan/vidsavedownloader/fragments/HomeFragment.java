package com.hassan.vidsavedownloader.fragments;

import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static com.hassan.vidsavedownloader.utils.Utils.isValidUrl;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
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
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.bumptech.glide.Glide;
import com.hassan.vidsavedownloader.MainActivity;
import com.hassan.vidsavedownloader.R;
import com.hassan.vidsavedownloader.models.LinkPreview;
import com.hassan.vidsavedownloader.monetization.AdMob;
import com.hassan.vidsavedownloader.services.DownloadService;
import com.hassan.vidsavedownloader.utils.LinkPreviewFetcher;
import com.hassan.vidsavedownloader.utils.UrlResolver;
import com.hassan.vidsavedownloader.utils.Utils;

import java.io.File;

public class HomeFragment extends Fragment implements MainActivity.DownloadUiListener {

    private static final String TAG                = "HomeFragment";
    private static final int    REQUEST_PERMISSION = 100;
    private static final int    REQUEST_NOTIFICATIONS = 3001;
    private static final int    REQUEST_STORAGE    = 2001;

    private boolean permissionRequestInProgress       = false;
    private boolean storagePermissionRequestInProgress = false;

    private EditText    urlInput;
    private Button      downloadBtn;
    private ProgressBar downloadProgress;
    private TextView    progressText;
    private ImageView   pasteLinkBtn;
    private String      downloadUrl;
    private Handler     mainHandler;
    private boolean     isDownloading        = false;

    private CardView  linkPreviewCard;
    private View      previewLoadingContainer;
    private View      previewContentContainer;
    private ImageView previewThumbnail;
    private TextView  previewDomain, previewTitle, previewDescription;
    private String    lastPreviewedUrl = "";

    public HomeFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.fragment_home, container, false);
        AdMob.loadRewardedAd(requireContext());
        mainHandler = new Handler(Looper.getMainLooper());

        urlInput         = view.findViewById(R.id.urlInput);
        downloadBtn      = view.findViewById(R.id.downloadBtn);
        downloadProgress = view.findViewById(R.id.downloadProgress);
        progressText     = view.findViewById(R.id.progressText);
        pasteLinkBtn     = view.findViewById(R.id.pasteLink);

        linkPreviewCard         = view.findViewById(R.id.linkPreviewCard);
        previewLoadingContainer = view.findViewById(R.id.previewLoadingContainer);
        previewContentContainer = view.findViewById(R.id.previewContentContainer);
        previewThumbnail        = view.findViewById(R.id.previewThumbnail);
        previewDomain           = view.findViewById(R.id.previewDomain);
        previewTitle            = view.findViewById(R.id.previewTitle);
        previewDescription      = view.findViewById(R.id.previewDescription);

        urlInput.setOnClickListener(v -> pasteLinkBtn.performClick());

        urlInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(android.text.Editable s) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String text = s != null ? s.toString() : "";
                updateUrlHighlight(text);

                if (isValidUrl(text) && !text.equals(lastPreviewedUrl)) {
                    showPreviewLoading();
                    if (UrlResolver.needsExpansion(text)) {
                        new Thread(() -> {
                            String expanded = UrlResolver.resolve(text);
                            if (getActivity() == null) return;
                            getActivity().runOnUiThread(() -> {
                                lastPreviewedUrl = expanded;
                                fetchLinkPreview(expanded);
                            });
                        }).start();
                    } else {
                        lastPreviewedUrl = text;
                        fetchLinkPreview(text);
                    }
                } else if (!isValidUrl(text)) {
                    lastPreviewedUrl = "";
                    hidePreview();
                }
            }
        });

        setupPasteButton();
        setupDownloadButton();
        setupBrowserButton(view);
        createVidSaveFolder();

        if (getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            if (activity.isDownloadInProgress()) {
                isDownloading = true;
                onDownloadProgress(activity.getLastDownloadProgress(), activity.getLastDownloadStatus());
            }
        }

        return view;
    }

    // ── Browser button ────────────────────────────────────────────────────

    private void setupBrowserButton(View view) {
        Button browserBtn = view.findViewById(R.id.browserBtn);
        browserBtn.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            String startUrl = isValidUrl(url) ? url : "https://www.google.com";
            openBrowserFragment(startUrl);
        });
    }

    /**
     * Navigates to BrowserFragment with the given URL and shows a
     * guiding toast so the user knows what to do.
     */
    private void openBrowserFragment(String url) {
        if (!isAdded() || getActivity() == null) return;

        String loadUrl = UrlResolver.resolve(url);
        BrowserFragment browserFragment = BrowserFragment.newInstance(loadUrl);
        requireActivity().getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragmentContainer, browserFragment)
                .addToBackStack("browser")
                .commit();
    }

    // ── Download UI callbacks (via MainActivity) ─────────────────────────

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setDownloadUiListener(this);
        }
    }

    @Override
    public void onPause() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).clearDownloadUiListener(this);
        }
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).clearDownloadUiListener(this);
        }
        if (mainHandler != null) mainHandler.removeCallbacksAndMessages(null);
        super.onDestroyView();
    }

    @Override
    public void onDownloadStart(String url) {
        if (getView() == null) return;
        isDownloading = true;
        downloadProgress.setVisibility(VISIBLE);
        downloadProgress.setIndeterminate(true);
        progressText.setVisibility(VISIBLE);
        progressText.setText("Starting download…");
    }

    @Override
    @SuppressLint("SetTextI18n")
    public void onDownloadProgress(int percent, String status) {
        if (getView() == null) return;

        isDownloading = true;
        downloadProgress.setVisibility(VISIBLE);
        progressText.setVisibility(VISIBLE);

        boolean isProcessing = status != null && (
                status.toLowerCase().contains("processing")
                        || status.toLowerCase().contains("merging")
                        || status.toLowerCase().contains("making video"));

        if (isProcessing || percent < 0) {
            downloadProgress.setIndeterminate(true);
            progressText.setText(status != null ? status : "Working…");
        } else {
            downloadProgress.setIndeterminate(false);
            downloadProgress.setProgress(percent);
            progressText.setText(percent + "% downloaded");
        }
    }

    @Override
    public void onDownloadComplete(String filePath) {
        if (getView() == null) return;

        isDownloading = false;
        downloadProgress.setVisibility(GONE);
        progressText.setVisibility(VISIBLE);
        progressText.setText("Download complete ✅\nYour video is ready to share!");
    }

    @Override
    public void onDownloadError(String error, String sourceUrl) {
        if (getView() == null || getActivity() == null) return;

        isDownloading = false;
        downloadProgress.setVisibility(GONE);
        progressText.setVisibility(VISIBLE);

        String urlToOpen = (sourceUrl != null && !sourceUrl.isEmpty())
                ? sourceUrl
                : downloadUrl;

        if (Utils.isBrowserRequiredError(error) && urlToOpen != null && !urlToOpen.isEmpty()) {
            onOpenBrowser(urlToOpen, error);
            return;
        }

        progressText.setText(error != null ? error : "Unknown error ❌");
        Utils.showAnimatedToast(getActivity(),
                error != null ? error : "Something went wrong",
                R.drawable.alert_error,
                Utils.ToastDuration.SHORT);
    }

    @Override
    public void onDownloadCancel() {
        if (getView() == null) return;

        isDownloading = false;
        downloadProgress.setVisibility(GONE);
        progressText.setVisibility(VISIBLE);
        progressText.setText("Download cancelled");
    }

    @Override
    public void onOpenBrowser(String blockedUrl, String message) {
        if (getView() == null || getActivity() == null) return;

        isDownloading = false;
        downloadProgress.setVisibility(GONE);
        progressText.setVisibility(VISIBLE);
        progressText.setText("Access blocked — opening browser…");

        Utils.showAnimatedToast(getActivity(),
                message != null ? message
                        : "Opening in-app browser. Wait for the video to load, then tap Download.",
                R.drawable.warning, Utils.ToastDuration.LONG);

        if (blockedUrl != null && !blockedUrl.isEmpty()) {
            urlInput.setText(blockedUrl);
            lastPreviewedUrl = "";
            new Thread(() -> {
                String resolved = UrlResolver.resolve(blockedUrl);
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() ->
                        LinkPreviewFetcher.fetch(requireContext(), resolved,
                                new LinkPreviewFetcher.PreviewCallback() {
                                    @Override public void onSuccess(LinkPreview preview) {
                                        if (getActivity() != null) showPreview(preview);
                                    }
                                    @Override public void onError() { /* ignore */ }
                                }));
            }).start();

            if (mainHandler != null) {
                mainHandler.postDelayed(() -> {
                    if (getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).openBrowserForUrl(blockedUrl);
                    }
                }, 800);
            }
        }
    }

    @Override
    public void onDownloadIdle() {
        resetDownloadUi();
    }

    private void resetDownloadUi() {
        if (getView() == null) return;
        isDownloading = false;
        downloadProgress.setVisibility(GONE);
        progressText.setVisibility(GONE);
    }

    private void fetchLinkPreview(String url) {
        LinkPreviewFetcher.fetch(requireContext(), url, new LinkPreviewFetcher.PreviewCallback() {
            @Override public void onSuccess(LinkPreview preview) {
                if (getActivity() == null) return;
                showPreview(preview);
            }
            @Override public void onError() { hidePreview(); }
        });
    }

    // ── Preview helpers ───────────────────────────────────────────────────

    private void updateUrlHighlight(String text) {
        if (text == null || text.trim().isEmpty()) {
            urlInput.setBackgroundResource(R.drawable.edittext_bg);
        } else if (isValidUrl(text)) {
            urlInput.setBackgroundResource(R.drawable.bg_url_valid);
        } else {
            urlInput.setBackgroundResource(R.drawable.bg_url_invalid);
        }
    }

    private void showPreviewLoading() {
        if (linkPreviewCard == null) return;
        linkPreviewCard.setVisibility(VISIBLE);
        previewLoadingContainer.setVisibility(VISIBLE);
        previewContentContainer.setVisibility(GONE);
    }

    private void showPreview(LinkPreview preview) {
        if (linkPreviewCard == null || getContext() == null) return;
        linkPreviewCard.setVisibility(VISIBLE);
        previewLoadingContainer.setVisibility(GONE);
        previewContentContainer.setVisibility(VISIBLE);
        previewDomain.setText(preview.domain);
        previewTitle.setText(preview.title);

        if (preview.description != null && !preview.description.isEmpty()) {
            previewDescription.setText(preview.description);
            previewDescription.setVisibility(VISIBLE);
        } else {
            previewDescription.setVisibility(GONE);
        }

        if (preview.imageUrl != null && !preview.imageUrl.isEmpty()) {
            previewThumbnail.setVisibility(VISIBLE);
            Glide.with(requireContext())
                    .load(preview.imageUrl)
                    .centerCrop()
                    .into(previewThumbnail);
        } else {
            previewThumbnail.setVisibility(GONE);
        }
    }

    private void hidePreview() {
        if (linkPreviewCard == null) return;
        linkPreviewCard.setVisibility(GONE);
    }

    // ── Folder ────────────────────────────────────────────────────────────

    private void createVidSaveFolder() {
        File f = getVidSaveFolder();
        if (!f.exists()) f.mkdirs();
    }

    private File getVidSaveFolder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new File(requireContext()
                    .getExternalFilesDir(Environment.DIRECTORY_MOVIES), "VidSave");
        }
        return new File(Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "VidSave");
    }

    // ── Paste button ──────────────────────────────────────────────────────

    private void setupPasteButton() {
        pasteLinkBtn.setOnClickListener(v -> {
            if ("clear".equals(pasteLinkBtn.getTag())) {
                urlInput.setText("");
                pasteLinkBtn.setImageResource(R.drawable.copy);
                pasteLinkBtn.setTag("paste");
            } else {
                ClipboardManager cb = (ClipboardManager)
                        requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if (cb != null && cb.hasPrimaryClip()) {
                    ClipData cd = cb.getPrimaryClip();
                    if (cd != null && cd.getItemCount() > 0) {
                        CharSequence txt = cd.getItemAt(0).coerceToText(getContext());
                        if (txt != null) {
                            urlInput.setText(txt.toString());
                            pasteLinkBtn.setImageResource(R.drawable.clear);
                            pasteLinkBtn.setTag("clear");
                            Utils.showAnimatedToast(getActivity(), "Link pasted",
                                    R.drawable.check_mark, Utils.ToastDuration.SHORT);
                        }
                    }
                } else {
                    Utils.showAnimatedToast(getActivity(), "Clipboard is empty",
                            R.drawable.alert_error, Utils.ToastDuration.SHORT);
                }
            }
        });
    }

    // ── Download button ───────────────────────────────────────────────────

    private void setupDownloadButton() {
        downloadBtn.setOnClickListener(v -> {
            if (isDownloading) {
                Utils.showAnimatedToast(getActivity(),
                        "A download is already in progress",
                        R.drawable.warning, Utils.ToastDuration.SHORT);
                return;
            }
            if (checkPermissions()) {
                downloadProgress.setVisibility(VISIBLE);
                downloadProgress.setIndeterminate(true);
                progressText.setVisibility(VISIBLE);
                progressText.setText("Starting download…");
                startDownload();
            } else {
                requestPermissions();
            }
        });
    }

    private void startDownload() {
        if (permissionRequestInProgress || storagePermissionRequestInProgress) return;

        checkNotificationPermissionAndContinue(() ->
                checkStoragePermissionAndContinue(() -> {
                    String inputUrl = urlInput.getText().toString().trim();

                    if (inputUrl.isEmpty() || !isValidUrl(inputUrl)) {
                        updateUrlHighlight(inputUrl);
                        downloadProgress.setVisibility(INVISIBLE);
                        progressText.setVisibility(INVISIBLE);
                        Utils.showAnimatedToast(getActivity(),
                                "Please enter a valid URL",
                                R.drawable.warning, Utils.ToastDuration.SHORT);
                        return;
                    }

                    if (UrlResolver.needsExpansion(inputUrl)) {
                        new Thread(() -> {
                            String expanded = UrlResolver.resolve(inputUrl);
                            if (getActivity() != null) {
                                getActivity().runOnUiThread(() -> {
                                    downloadUrl = expanded;
                                    urlInput.setText(expanded);
                                    proceedWithDownload();
                                });
                            }
                        }).start();
                    } else {
                        downloadUrl = inputUrl;
                        proceedWithDownload();
                    }
                }));
    }

    private void proceedWithDownload() {
        if (downloadUrl == null) return;
        Utils.showAnimatedToast(getActivity(),
                "Watch a short ad to start download",
                R.drawable.warning, Utils.ToastDuration.SHORT);

        AdMob.showRewardedAd(requireActivity(), () -> {
            File folder = getVidSaveFolder();
            startDownloadService(folder.getAbsolutePath());
        });
    }

    private void startDownloadService(String folderPath) {
        Log.d(TAG, "Starting DownloadService for: " + downloadUrl);
        Intent i = new Intent(getContext(), DownloadService.class);
        i.putExtra(DownloadService.EXTRA_URL, downloadUrl);
        i.putExtra(DownloadService.EXTRA_FOLDER_PATH, folderPath);
        ContextCompat.startForegroundService(requireContext(), i);
    }

    // ── Permissions ───────────────────────────────────────────────────────

    private boolean checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return true;
        } else {
            return ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{Manifest.permission.READ_MEDIA_VIDEO},
                    REQUEST_PERMISSION);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startDownload();
        } else {
            ActivityCompat.requestPermissions(requireActivity(),
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_PERMISSION);
        }
    }

    private void checkStoragePermissionAndContinue(Runnable onGranted) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onGranted.run();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED) {
                onGranted.run();
                return;
            }
            storagePermissionRequestInProgress = true;
            requestPermissions(new String[]{Manifest.permission.READ_MEDIA_VIDEO},
                    REQUEST_STORAGE);
            return;
        }
        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            onGranted.run();
            return;
        }
        storagePermissionRequestInProgress = true;
        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                REQUEST_STORAGE);
    }

    private void checkNotificationPermissionAndContinue(Runnable onGranted) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) { onGranted.run(); return; }
        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            onGranted.run();
            return;
        }
        permissionRequestInProgress = true;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_NOTIFICATIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_NOTIFICATIONS) {
            permissionRequestInProgress = false;
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startDownload();
            } else {
                Utils.showAnimatedToast(getActivity(),
                        "Enable notifications from Settings to track downloads",
                        R.drawable.alert_error, Utils.ToastDuration.LONG);
            }
        }

        if (requestCode == REQUEST_STORAGE) {
            storagePermissionRequestInProgress = false;
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startDownload();
            } else {
                Utils.showAnimatedToast(getActivity(),
                        "Storage permission is required to save videos",
                        R.drawable.alert_error, Utils.ToastDuration.LONG);
            }
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────

    private void handlePermanentDenial(String permission, String message) {
        if (!shouldShowRequestPermissionRationale(permission)) {
            Utils.showAnimatedToast(getActivity(),
                    message + ". Enable it from Settings.",
                    R.drawable.alert_error, Utils.ToastDuration.LONG);
            Intent intent = new Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(android.net.Uri.fromParts(
                    "package", requireContext().getPackageName(), null));
            startActivity(intent);
        } else {
            Utils.showAnimatedToast(getActivity(), message,
                    R.drawable.alert_error, Utils.ToastDuration.SHORT);
        }
    }
}