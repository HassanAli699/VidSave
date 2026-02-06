package com.hassan.vidsave.fragments;

import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.hassan.vidsave.R;
import com.hassan.vidsave.adapters.VideoListAdapter;
import com.hassan.vidsave.monetization.AdMob;
import com.hassan.vidsave.utils.Utils;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DownloadedFragment extends Fragment {

    private RecyclerView videosRecycler;
    private FrameLayout loadingOverlay;
    private LinearLayout emptyViewContainer;
    private VideoListAdapter adapter;

    public DownloadedFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_downloaded, container, false);

        videosRecycler = view.findViewById(R.id.videosRecycler);
        ImageView refreshBtn = view.findViewById(R.id.refreshBtn);
        loadingOverlay = view.findViewById(R.id.loadingOverlay);
        SwipeRefreshLayout swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout);
        emptyViewContainer = view.findViewById(R.id.emptyViewContainer);


        swipeRefreshLayout.setOnRefreshListener(() -> {
            loadDownloadedVideos();
            swipeRefreshLayout.setRefreshing(false);
        });

        refreshBtn.setOnClickListener(v -> {
            loadDownloadedVideos();
            Utils.showAnimatedToast(
                    getActivity(),
                    "Successfully Refreshed",
                    R.drawable.check_mark,
                    Utils.ToastDuration.SHORT
            );
        });

        loadDownloadedVideos();
        return view;
    }

    private void showLoader() {
        if (getActivity() != null) {
            loadingOverlay.bringToFront();
            loadingOverlay.setVisibility(View.VISIBLE);
        }
    }

    private void hideLoader() {
        loadingOverlay.setVisibility(View.GONE);
    }

    private void loadDownloadedVideos() {
        showLoader();

        new Thread(() -> {
            List<File> videoFiles = getVideoFilesFromFolder();

            if (getActivity() == null) return;

            getActivity().runOnUiThread(() -> {
                if (videoFiles.isEmpty()) {
                    videosRecycler.setVisibility(View.GONE);
                    emptyViewContainer.setVisibility(View.VISIBLE);
                    hideLoader();
                } else {
                    videosRecycler.setVisibility(View.VISIBLE);
                    emptyViewContainer.setVisibility(View.GONE);

                   // if (adapter != null) adapter.shutdown();

                    adapter = new VideoListAdapter(getContext(), videoFiles);
                    DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
                    float dpWidth = displayMetrics.widthPixels / displayMetrics.density;
                    int numOfColumns = Math.max(1, (int) (dpWidth / 180));

                    videosRecycler.setLayoutManager(new GridLayoutManager(getContext(), numOfColumns));
                    videosRecycler.setAdapter(adapter);
                    hideLoader();
                }
            });
        }).start();
    }


    private List<File> getVideoFilesFromFolder() {
        List<File> videos = new ArrayList<>();

        File[] folders = {
                new File(getContext().getExternalFilesDir(Environment.DIRECTORY_MOVIES), "VidSave"),
                new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VidSave")
        };

        Log.d("FILE FOLDERS:", folders.length + " folders");

        for (File folder : folders) {
            if (folder.exists()) {
                File[] files = folder.listFiles((d, n) -> {
                    String lower = n.toLowerCase();
                    return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm");
                });
                if (files != null) {
                    Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                    videos.addAll(Arrays.asList(files));
                }
            }
        }

        return videos;
    }



    @Override
    public void onResume() {
        super.onResume();
        loadDownloadedVideos();
    }
}
