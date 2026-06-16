package com.hassan.vidsavedownloader.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;

import androidx.core.content.FileProvider;

import com.hassan.vidsavedownloader.MainActivity;
import com.hassan.vidsavedownloader.R;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

public final class VidSaveFolderHelper {

    private VidSaveFolderHelper() {}

    public static File getPrimaryFolder(Context context) {
        File movies = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (movies != null) {
            return new File(movies, "VidSave");
        }
        return new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "VidSave");
    }

    /** All folders where downloads may be stored (new + legacy). */
    public static File[] getAllFolders(Context context) {
        return new File[] {
                getPrimaryFolder(context),
                new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "VidSave")
        };
    }

    /**
     * Opens the VidSave folder in a gallery / file app, or falls back to the in-app Library tab.
     */
    public static void openFolderInGallery(Context context) {
        ensureFoldersExist(context);

        if (tryOpenFolderExternally(context)) {
            return;
        }

        if (context instanceof MainActivity) {
            ((MainActivity) context).openLibraryTab();
        }
    }

    private static void ensureFoldersExist(Context context) {
        for (File folder : getAllFolders(context)) {
            if (!folder.exists()) {
                //noinspection ResultOfMethodCallIgnored
                folder.mkdirs();
            }
        }
    }

    private static boolean tryOpenFolderExternally(Context context) {
        File folder = getPrimaryFolder(context);
        String authority = context.getPackageName() + ".fileprovider";

        try {
            Uri uri = FileProvider.getUriForFile(context, authority, folder);
            String[] mimeTypes = {
                    "vnd.android.document/directory",
                    "resource/folder",
                    "inode/directory",
                    "*/*"
            };

            for (String mime : mimeTypes) {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, mime);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_ACTIVITY_NEW_TASK);

                if (intent.resolveActivity(context.getPackageManager()) != null) {
                    Intent chooser = Intent.createChooser(
                            intent,
                            context.getString(R.string.settings_open_folder_chooser));
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(chooser);
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public static File findLatestVideo(Context context) {
        File newest = null;
        long newestTime = 0L;

        for (File folder : getAllFolders(context)) {
            if (!folder.isDirectory()) continue;
            File[] files = folder.listFiles((dir, name) -> isVideoFile(name));
            if (files == null) continue;

            for (File file : files) {
                if (file.lastModified() > newestTime) {
                    newestTime = file.lastModified();
                    newest = file;
                }
            }
        }
        return newest;
    }

    public static int countVideos(Context context) {
        int count = 0;
        for (File folder : getAllFolders(context)) {
            if (!folder.isDirectory()) continue;
            File[] files = folder.listFiles((dir, name) -> isVideoFile(name));
            if (files != null) count += files.length;
        }
        return count;
    }

    private static boolean isVideoFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm");
    }
}
