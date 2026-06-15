package com.hassan.vidsavedownloader.utils;

import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.appupdate.AppUpdateOptions;
import com.google.android.play.core.install.InstallStateUpdatedListener;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.InstallStatus;
import com.google.android.play.core.install.model.UpdateAvailability;
import com.hassan.vidsavedownloader.R;

/**
 * Checks Google Play for updates and prompts the user in-app (flexible or immediate flow).
 */
public final class AppUpdateHelper {

    private static final String TAG = "AppUpdateHelper";

    private final AppCompatActivity activity;
    private final AppUpdateManager appUpdateManager;
    private final ActivityResultLauncher<IntentSenderRequest> updateLauncher;

    private InstallStateUpdatedListener installListener;
    private boolean updateDialogShownThisSession;
    private Snackbar updateReadySnackbar;

    public AppUpdateHelper(AppCompatActivity activity) {
        this.activity = activity;
        this.appUpdateManager = AppUpdateManagerFactory.create(activity);
        this.updateLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(),
                result -> Log.d(TAG, "Update flow result: " + result.getResultCode()));
    }

    /** Call from {@link AppCompatActivity#onResume}. */
    public void checkOnResume() {
        appUpdateManager.getAppUpdateInfo()
                .addOnSuccessListener(info -> {
                    if (info.installStatus() == InstallStatus.DOWNLOADED) {
                        showUpdateReadySnackbar();
                        return;
                    }
                    if (info.updateAvailability()
                            == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                        startUpdateFlow(info, AppUpdateType.IMMEDIATE);
                        return;
                    }
                    checkForUpdate(false);
                })
                .addOnFailureListener(e -> Log.w(TAG, "getAppUpdateInfo failed", e));
    }

    public void destroy() {
        dismissUpdateReadySnackbar();
        unregisterInstallListener();
    }

    private void checkForUpdate(boolean forceDialog) {
        appUpdateManager.getAppUpdateInfo()
                .addOnSuccessListener(info -> {
                    if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) {
                        return;
                    }
                    if (!forceDialog && updateDialogShownThisSession) {
                        return;
                    }
                    showUpdateAvailableDialog(info);
                })
                .addOnFailureListener(e -> Log.w(TAG, "Update check failed", e));
    }

    private void showUpdateAvailableDialog(AppUpdateInfo info) {
        int updateType = resolveUpdateType(info);
        if (updateType == -1) {
            Log.d(TAG, "Update available but no supported in-app update type");
            return;
        }

        updateDialogShownThisSession = true;

        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_available_title)
                .setMessage(R.string.update_available_message)
                .setPositiveButton(R.string.update_now, (dialog, which) ->
                        startUpdateFlow(info, updateType))
                .setNegativeButton(R.string.update_later, null)
                .show();
    }

    private int resolveUpdateType(AppUpdateInfo info) {
        if (info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
            return AppUpdateType.FLEXIBLE;
        }
        if (info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
            return AppUpdateType.IMMEDIATE;
        }
        return -1;
    }

    private void startUpdateFlow(AppUpdateInfo info, int updateType) {
        if (updateType == AppUpdateType.FLEXIBLE) {
            registerInstallListener();
        }

        try {
            AppUpdateOptions options = AppUpdateOptions.newBuilder(updateType).build();
            appUpdateManager.startUpdateFlowForResult(info, updateLauncher, options);
        } catch (Exception e) {
            Log.w(TAG, "startUpdateFlowForResult failed", e);
        }
    }

    private void registerInstallListener() {
        if (installListener != null) return;

        installListener = state -> {
            if (state.installStatus() == InstallStatus.DOWNLOADED) {
                showUpdateReadySnackbar();
            }
        };
        appUpdateManager.registerListener(installListener);
    }

    private void unregisterInstallListener() {
        if (installListener != null) {
            appUpdateManager.unregisterListener(installListener);
            installListener = null;
        }
    }

    private void showUpdateReadySnackbar() {
        if (activity.isFinishing()) return;

        dismissUpdateReadySnackbar();

        android.view.View anchor = activity.findViewById(R.id.ad_view_container);
        if (anchor == null) {
            anchor = activity.findViewById(android.R.id.content);
        }

        updateReadySnackbar = Snackbar.make(anchor, R.string.update_ready_message, Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.update_restart, v -> {
                    dismissUpdateReadySnackbar();
                    appUpdateManager.completeUpdate();
                });

        updateReadySnackbar.show();
    }

    private void dismissUpdateReadySnackbar() {
        if (updateReadySnackbar != null) {
            updateReadySnackbar.dismiss();
            updateReadySnackbar = null;
        }
    }
}
