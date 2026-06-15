package com.hassan.vidsavedownloader.adapters;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.hassan.vidsavedownloader.R;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

public class VideoListAdapter extends RecyclerView.Adapter<VideoListAdapter.ViewHolder> {

    private final Context context;
    private final List<File> videos;

    public VideoListAdapter(Context context, List<File> videos) {
        this.context = context;
        this.videos = videos;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_video, parent, false);
        return new ViewHolder(view);
    }

    @SuppressLint("DefaultLocale")
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        File videoFile = videos.get(position);

        // ------------------ THUMBNAIL ------------------
        Glide.with(context)
                .load(videoFile)
                .centerCrop()
                .into(holder.thumbnail);


        // ------------------ TITLE & SIZE ------------------
        String fileName = videoFile.getName();
        int dot = fileName.lastIndexOf('.');
        String title = dot > 0 ? fileName.substring(0, dot) : fileName;
        title = title.replace('_', ' ').replace('-', ' ');
        holder.videoTitle.setText(title);

        long bytes = videoFile.length();
        holder.videoFileSize.setText(String.format(Locale.getDefault(),
                "%.1f MB", bytes / (1024.0 * 1024.0)));

        // ------------------ DURATION ------------------
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(videoFile.getAbsolutePath());
            String durationStr = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
            );
            if (durationStr != null) {
                long ms = Long.parseLong(durationStr);
                holder.videoDuration.setText(
                        String.format(Locale.getDefault(),
                                "%02d:%02d",
                                (ms / 1000) / 60,
                                (ms / 1000) % 60)
                );
            } else {
                holder.videoDuration.setText("--:--");
            }
        } catch (Exception e) {
            holder.videoDuration.setText("--:--");
        } finally {
            try {
                retriever.release();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }



        // ------------------ PLAY VIDEO ------------------
        holder.itemView.setOnClickListener(v -> {
            Uri contentUri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    videoFile
            );

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(contentUri, "video/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(intent);
        });

        // ------------------ LONG PRESS OPTIONS ------------------
        holder.itemView.setOnLongClickListener(v -> {
            String[] options = {"Show details", "Delete video", "Share"};

            new android.app.AlertDialog.Builder(context)
                    .setTitle("Choose an action")
                    .setItems(options, (dialog, which) -> {
                        if (which == 0) {
                            showVideoDetails(videoFile);
                        } else if (which == 1) {
                            deleteVideo(videoFile, position);
                        } else {
                            shareVideo(videoFile);
                        }
                    })
                    .show();
            return true;
        });
    }

    // ------------------ VIDEO DETAILS ------------------
    private void showVideoDetails(File videoFile) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(videoFile.getAbsolutePath());

        String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
        String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
        String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
        String mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
        String bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);

        try {
            retriever.release();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        long fileSize = videoFile.length();
        long lastModified = videoFile.lastModified();

        String details =
                "Resolution: " + width + " x " + height +
                        "\nDuration: " + (duration != null ? Integer.parseInt(duration) / 1000 : 0) + " sec" +
                        "\nMIME Type: " + mimeType +
                        "\nFile Size: " + String.format(Locale.getDefault(),
                        "%.2f MB", fileSize / (1024.0 * 1024.0)) +
                        "\nDate Modified: " + android.text.format.DateFormat.format(
                        "dd MMM yyyy, hh:mm a", lastModified) +
                        "\nBitrate: " + (bitrate != null ? Integer.parseInt(bitrate) / 1000 + " kbps" : "Unknown");

        new android.app.AlertDialog.Builder(context)
                .setTitle("Video Details")
                .setMessage(details)
                .setPositiveButton("OK", null)
                .show();
    }

    // ------------------ DELETE ------------------
    private void deleteVideo(File videoFile, int position) {
        if (videoFile.delete()) {
            videos.remove(position);
            notifyItemRemoved(position);
        }
    }

    // ------------------ SHARE ------------------
    private void shareVideo(File videoFile) {
        Uri contentUri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".fileprovider",
                videoFile
        );

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("video/*");
        shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        context.startActivity(
                Intent.createChooser(shareIntent, "Share video via")
        );
    }

    @Override
    public int getItemCount() {
        return videos.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView thumbnail;
        TextView videoDuration;
        TextView videoTitle;
        TextView videoFileSize;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            thumbnail = itemView.findViewById(R.id.videoThumbnail);
            videoDuration = itemView.findViewById(R.id.videoDuration);
            videoTitle = itemView.findViewById(R.id.videoTitle);
            videoFileSize = itemView.findViewById(R.id.videoFileSize);
        }
    }
}
