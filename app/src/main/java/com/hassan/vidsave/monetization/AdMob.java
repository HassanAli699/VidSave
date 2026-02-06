package com.hassan.vidsave.monetization;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;

public class AdMob {

    private static RewardedAd rewardedAd;
    private static boolean isLoadingRewarded = false;


    public  static  void adMobSDKInit(Context context){
            if(!Constants.IS_ADS_ENABLED) return;
        new Thread(
                () -> {
                    // Initialize the Google Mobile Ads SDK on a background thread.
                    MobileAds.initialize(context, initializationStatus -> {});
                }).start();
    }


    public  static  void setBannerAD(LinearLayout adContainerView, Context context){
        if(!Constants.IS_ADS_ENABLED) return;

        AdView adView = new AdView(context);
        adView.setVisibility(View.GONE);

        adView.setAdUnitId(Constants.getBannerAdId(context));
        Log.d("ADMOB", "Banner ad unit id:" + Constants.getBannerAdId(context));
        adView.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, AdSize.FULL_WIDTH));
        adContainerView.removeAllViews();
        adContainerView.addView(adView);

        AdRequest adRequest = new AdRequest.Builder().build();
        adView.loadAd(adRequest);
        adView.setAdListener(new AdListener() {
            @Override
            public void onAdClicked() {
                super.onAdClicked();
                Toast.makeText(context, "AD CLICKED", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onAdLoaded() {
                super.onAdLoaded();
                adView.setVisibility(View.VISIBLE);
            }

            @Override
            public void onAdOpened() {
                super.onAdOpened();
                Log.d("ADMOB", "AD OPENED");
            }
        });

    }

    public static void showRewardedAd(Activity activity, Runnable onRewardEarned) {
        if (!Constants.IS_ADS_ENABLED) {
            onRewardEarned.run();
            return;
        }

        if (rewardedAd == null) {
            // Ad not ready → do NOT block download
            onRewardEarned.run();
            return;
        }

        rewardedAd.show(activity, rewardItem -> {
            // Reward earned
            onRewardEarned.run();
        });
    }

    public static void loadRewardedAd(Context context) {
        if (!Constants.IS_ADS_ENABLED) return;
        if (rewardedAd != null || isLoadingRewarded) return;

        isLoadingRewarded = true;

        Context appContext = context.getApplicationContext();
        AdRequest adRequest = new AdRequest.Builder().build();
        Log.d("ADMOB", "Rewarded ad unit id:" + Constants.getRewardedAdId(appContext));
        RewardedAd.load(
                appContext,
                Constants.getRewardedAdId(appContext),
                adRequest,
                new RewardedAdLoadCallback() {

                    @Override
                    public void onAdLoaded(@NonNull RewardedAd ad) {
                        rewardedAd = ad;
                        isLoadingRewarded = false;

                        Log.d("ADMOB", "Rewarded ad loaded");

                        rewardedAd.setFullScreenContentCallback(
                                new FullScreenContentCallback() {

                                    @Override
                                    public void onAdShowedFullScreenContent() {
                                        Log.d("ADMOB", "Rewarded ad shown");
                                    }

                                    @Override
                                    public void onAdDismissedFullScreenContent() {
                                        Log.d("ADMOB", "Rewarded ad dismissed");
                                        rewardedAd = null;
                                        loadRewardedAd(appContext); // preload next
                                    }

                                    @Override
                                    public void onAdFailedToShowFullScreenContent(
                                            @NonNull AdError adError) {
                                        Log.e("ADMOB",
                                                "Failed to show rewarded ad: " + adError.getMessage());
                                        rewardedAd = null;
                                        loadRewardedAd(appContext);
                                    }
                                });
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                        isLoadingRewarded = false;
                        rewardedAd = null;

                        Log.e("ADMOB",
                                "Rewarded ad failed to load: " + loadAdError.getMessage());
                    }
                });
    }




}
