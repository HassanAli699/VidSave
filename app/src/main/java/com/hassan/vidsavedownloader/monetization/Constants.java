package com.hassan.vidsavedownloader.monetization;

import android.content.Context;
import android.content.pm.ApplicationInfo;

public class Constants {

    public static boolean IS_ADS_ENABLED = true;

    // Test IDs
    private static final String BANNER_AD_ID_TEST = "ca-app-pub-3940256099942544/9214589741";
    private static final String REWARDED_AD_ID_TEST = "ca-app-pub-3940256099942544/5224354917";

    // Live IDs
    private static final String BANNER_AD_ID_LIVE = "ca-app-pub-7555789740461293/8437596788";
    private static final String REWARDED_AD_ID_LIVE = "ca-app-pub-7555789740461293/1100287966";

    public static boolean isDebugApp(Context context) {
        return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    public static String getBannerAdId(Context context) {
        return isDebugApp(context)
                ? BANNER_AD_ID_TEST
                : BANNER_AD_ID_LIVE;
    }

    public static String getRewardedAdId(Context context) {
        return isDebugApp(context)
                ? REWARDED_AD_ID_TEST
                : REWARDED_AD_ID_LIVE;
    }
}
