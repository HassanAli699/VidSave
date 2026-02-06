package com.hassan.vidsave.utils;

import android.app.Activity;
import android.os.Handler;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.TranslateAnimation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.hassan.vidsave.R;

public class Utils {

    // Enum for toast duration
    public enum ToastDuration {
        SHORT(2000),  // 2 seconds
        MEDIUM(3500), // 3.5 seconds
        LONG(5000);   // 5 seconds

        private final int millis;
        ToastDuration(int millis) { this.millis = millis; }
        public int getMillis() { return millis; }
    }

    public static void showAnimatedToast(Activity activity, String message, int iconResId, ToastDuration duration) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return; // prevent crash
        }

        LayoutInflater inflater = LayoutInflater.from(activity);
        View toastView = inflater.inflate(R.layout.custom_toast, null);

        ImageView icon = toastView.findViewById(R.id.toast_icon);
        TextView text = toastView.findViewById(R.id.toast_text);

        icon.setImageResource(iconResId);
        text.setText(message);

        FrameLayout decorView = (FrameLayout) activity.getWindow().getDecorView();
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.topMargin = 100;
        toastView.setLayoutParams(params);

        decorView.addView(toastView);

        // Slide-in animation
        TranslateAnimation slideIn = new TranslateAnimation(0, 0, -200, 0);
        slideIn.setDuration(300);
        toastView.startAnimation(slideIn);

        // Tap to dismiss
        toastView.setOnClickListener(v -> fadeOutAndRemove(toastView, decorView));

        // Auto dismiss
        new Handler().postDelayed(() -> fadeOutAndRemove(toastView, decorView), duration.getMillis());
    }

    private static void fadeOutAndRemove(View view, FrameLayout parent) {
        AlphaAnimation fadeOut = new AlphaAnimation(1, 0);
        fadeOut.setDuration(300);
        fadeOut.setFillAfter(true);
        view.startAnimation(fadeOut);

        fadeOut.setAnimationListener(new android.view.animation.Animation.AnimationListener() {
            @Override public void onAnimationStart(android.view.animation.Animation animation) {}
            @Override public void onAnimationRepeat(android.view.animation.Animation animation) {}
            @Override
            public void onAnimationEnd(android.view.animation.Animation animation) {
                parent.removeView(view);
            }
        });
    }

    public static boolean isValidUrl(String url) {
        if (url == null || url.trim().isEmpty()) return false;

        return android.util.Patterns.WEB_URL.matcher(url).matches()
                && (url.startsWith("http://") || url.startsWith("https://"));
    }
}
