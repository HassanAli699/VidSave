package com.hassan.vidsavedownloader.fragments;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.hassan.vidsavedownloader.R;
import com.hassan.vidsavedownloader.helpers.VideoExtractorHelper.VideoEntry;
import com.hassan.vidsavedownloader.monetization.AdMob;
import com.hassan.vidsavedownloader.services.DownloadService;
import com.hassan.vidsavedownloader.utils.PremiumUtils;
import com.hassan.vidsavedownloader.utils.UrlResolver;
import com.hassan.vidsavedownloader.utils.Utils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VideoPickerBottomSheet extends BottomSheetDialogFragment {

    private List<VideoEntry> videos    = new ArrayList<>();
    private String           cookies   = "";
    private String           userAgent = "";
    private String           pageReferer = "";

    // ── Factory ────────────────────────────────────────────────────────────

    /** Primary factory: takes a list of scraped VideoEntry objects */
    public static VideoPickerBottomSheet newInstance(List<VideoEntry> videos,
                                                     String cookies,
                                                     String userAgent,
                                                     String pageReferer) {
        VideoPickerBottomSheet sheet = new VideoPickerBottomSheet();
        sheet.videos       = videos       != null ? videos       : new ArrayList<>();
        sheet.cookies      = cookies      != null ? cookies      : "";
        sheet.userAgent    = userAgent    != null ? userAgent    : "";
        sheet.pageReferer  = pageReferer  != null ? pageReferer  : "";
        return sheet;
    }

    /** Convenience overload without pageReferer */
    public static VideoPickerBottomSheet newInstance(List<VideoEntry> videos,
                                                     String cookies,
                                                     String userAgent) {
        return newInstance(videos, cookies, userAgent, "");
    }

    /** Convenience overload without userAgent */
    public static VideoPickerBottomSheet newInstance(List<VideoEntry> videos,
                                                     String cookies) {
        return newInstance(videos, cookies, "");
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View view = inflater.inflate(
                R.layout.fragment_video_picker_bottom_sheet, container, false);

        RecyclerView rvVideos      = view.findViewById(R.id.rvVideos);
        Button       btnDownloadAll = view.findViewById(R.id.btnDownloadAll);
        TextView     tvVideoCount  = view.findViewById(R.id.tvVideoCount);

        int count = videos.size();
        tvVideoCount.setText(count + (count == 1 ? " video found" : " videos found"));

        rvVideos.setLayoutManager(new LinearLayoutManager(getContext()));
        rvVideos.setAdapter(new VideoAdapter());
        rvVideos.setNestedScrollingEnabled(true);

        btnDownloadAll.setOnClickListener(v -> {
            if (PremiumUtils.isPremium(requireContext())) {
                downloadAll();
            } else {
                Utils.showAnimatedToast(getActivity(),
                        "⭐ Upgrade to Premium to download all videos at once",
                        R.drawable.warning, Utils.ToastDuration.LONG);
            }
        });

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() instanceof BottomSheetDialog) {
            BottomSheetBehavior<?> b = ((BottomSheetDialog) getDialog()).getBehavior();
            b.setState(BottomSheetBehavior.STATE_EXPANDED);
            b.setSkipCollapsed(true);
            b.setFitToContents(true);
        }
    }

    // ── Download helpers ───────────────────────────────────────────────────

    private void downloadAll() {
        if (videos.isEmpty()) return;
        Utils.showAnimatedToast(getActivity(),
                "Starting " + videos.size() + " downloads…",
                R.drawable.check_mark, Utils.ToastDuration.SHORT);
        for (VideoEntry v : videos) startDownloadService(v.url);
        dismiss();
    }

    private void startDownloadService(String url) {
        File folder = getVidSaveFolder();
        if (!folder.exists()) folder.mkdirs();

        Intent intent = new Intent(requireContext(), DownloadService.class);
        intent.putExtra(DownloadService.EXTRA_URL,         url);
        intent.putExtra(DownloadService.EXTRA_FOLDER_PATH, folder.getAbsolutePath());
        intent.putExtra(DownloadService.EXTRA_COOKIES,     cookies);
        intent.putExtra(DownloadService.EXTRA_USER_AGENT,  userAgent);
        intent.putExtra(DownloadService.EXTRA_REFERER,     pageReferer);
        ContextCompat.startForegroundService(requireContext(), intent);
    }

    private File getVidSaveFolder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new File(requireContext()
                    .getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "VidSave");
        }
        return new File(android.os.Environment
                .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "VidSave");
    }

    private String resolveYoutubeThumbnail(String url) {
        if (url == null) return null;
        Matcher m = Pattern.compile(
                "(?:v=|youtu\\.be/|shorts/)([A-Za-z0-9_-]{11})").matcher(url);
        return m.find()
                ? "https://img.youtube.com/vi/" + m.group(1) + "/hqdefault.jpg"
                : null;
    }

    private String extractDomain(String url) {
        try {
            String s = url.replaceFirst("https?://", "");
            int slash = s.indexOf('/');
            return slash != -1 ? s.substring(0, slash) : s;
        } catch (Exception e) {
            return url;
        }
    }

    private boolean isDirectVideoFile(String url) {
        return UrlResolver.isDirectMediaUrl(url);
    }

    private String entryTypeHint(VideoEntry entry) {
        if (pageReferer != null && !pageReferer.isEmpty()
                && pageReferer.equals(entry.url)) {
            return "This page";
        }
        if (UrlResolver.isTikTokPageUrl(entry.url)) {
            return "TikTok page";
        }
        if (UrlResolver.isDirectMediaUrl(entry.url)) {
            return "Direct video file";
        }
        return extractDomain(entry.url);
    }

    // ── Adapter ────────────────────────────────────────────────────────────

    private class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            final ImageView ivThumbnail;
            final TextView  tvTitle, tvMeta, tvVideoUrl;
            final Button    btnDownload;

            VH(@NonNull View v) {
                super(v);
                ivThumbnail = v.findViewById(R.id.ivThumbnail);
                tvTitle     = v.findViewById(R.id.tvVideoTitle);
                tvMeta      = v.findViewById(R.id.tvDomain);
                tvVideoUrl  = v.findViewById(R.id.tvVideoUrl);
                btnDownload = v.findViewById(R.id.btnDownload);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_video_picker, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            VideoEntry entry = videos.get(position);

            // ── Thumbnail ─────────────────────────────────────────────────
            String thumb = (entry.thumb != null && !entry.thumb.isEmpty())
                    ? entry.thumb
                    : resolveYoutubeThumbnail(entry.url);

            if (thumb != null && !thumb.isEmpty()) {
                Glide.with(requireContext())
                        .load(thumb)
                        .placeholder(R.drawable.evd_logo)
                        .error(R.drawable.evd_logo)
                        .centerCrop()
                        .into(holder.ivThumbnail);
            } else {
                holder.ivThumbnail.setImageResource(R.drawable.evd_logo);
            }

            // ── Title ─────────────────────────────────────────────────────
            String title = (entry.title != null && !entry.title.isEmpty())
                    ? entry.title
                    : "Video " + (position + 1);
            holder.tvTitle.setText(title);

            // ── Meta: type hint + duration ────────────────────────────────
            String domain   = extractDomain(entry.url);
            String duration = entry.formattedDuration();
            String typeHint = entryTypeHint(entry);
            holder.tvMeta.setText(duration.isEmpty() ? typeHint : typeHint + "  ·  " + duration);

            holder.tvVideoUrl.setText(Utils.truncateUrl(entry.url, 120));

            // ── Download button ───────────────────────────────────────────
            holder.btnDownload.setOnClickListener(v -> {
                Utils.showAnimatedToast(getActivity(),
                        "Watch a short ad to start download",
                        R.drawable.warning, Utils.ToastDuration.SHORT);

                AdMob.showRewardedAd(requireActivity(), () -> {
                    startDownloadService(entry.url);
                    Utils.showAnimatedToast(getActivity(),
                            "Download started!",
                            R.drawable.check_mark, Utils.ToastDuration.SHORT);
                    dismiss();
                });
            });
        }

        @Override
        public int getItemCount() { return videos.size(); }
    }
}