package com.hassan.vidsavedownloader.fragments;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.hassan.vidsavedownloader.HowToGuideActivity;
import com.hassan.vidsavedownloader.MainActivity;
import com.hassan.vidsavedownloader.R;
import com.hassan.vidsavedownloader.utils.VidSaveFolderHelper;

public class SettingsFragment extends Fragment {

    public SettingsFragment() {}

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        TextView tvAppVersion = view.findViewById(R.id.tvAppVersion);
        TextView tvBuildInfo = view.findViewById(R.id.tvBuildInfo);
        TextView tvDownloadFolderPath = view.findViewById(R.id.tvDownloadFolderPath);

        bindVersionInfo(tvAppVersion, tvBuildInfo);
        tvDownloadFolderPath.setText(getString(
                R.string.settings_download_folder_desc,
                VidSaveFolderHelper.countVideos(requireContext())));

        view.findViewById(R.id.rowHowTo).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), HowToGuideActivity.class)));

        view.findViewById(R.id.rowCheckUpdate).setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).checkForUpdatesManually();
            }
        });

        view.findViewById(R.id.rowDownloadFolder).setOnClickListener(v ->
                VidSaveFolderHelper.openFolderInGallery(requireContext()));

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        View view = getView();
        if (view == null) return;
        TextView tvDownloadFolderPath = view.findViewById(R.id.tvDownloadFolderPath);
        if (tvDownloadFolderPath != null) {
            tvDownloadFolderPath.setText(getString(
                    R.string.settings_download_folder_desc,
                    VidSaveFolderHelper.countVideos(requireContext())));
        }
    }

    private void bindVersionInfo(TextView tvAppVersion, TextView tvBuildInfo) {
        try {
            PackageManager pm = requireContext().getPackageManager();
            PackageInfo info = pm.getPackageInfo(requireContext().getPackageName(), 0);
            String versionName = info.versionName != null ? info.versionName : "?";
            tvAppVersion.setText(getString(R.string.settings_version_format, versionName));
            tvBuildInfo.setText(getString(R.string.settings_build_format, info.versionCode));
        } catch (PackageManager.NameNotFoundException e) {
            tvAppVersion.setText(R.string.settings_version_unknown);
            tvBuildInfo.setText("");
        }
    }
}
