package com.red.alert.activities.settings;

import android.app.NotificationManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;
import com.red.alert.R;
import com.red.alert.logic.settings.AppPreferences;
import com.red.alert.ui.dialogs.AlertDialogBuilder;

import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import me.pushy.sdk.Pushy;
import me.pushy.sdk.config.PushyForegroundService;
import me.pushy.sdk.util.PushyServiceManager;

public class AdvancedPreferenceFragment extends BasePreferenceFragment {
    private TwoStatePreference mAlertPopup;
    private TwoStatePreference mForegroundService;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.settings_advanced, rootKey);
        mAlertPopup = (TwoStatePreference) findPreference(getString(R.string.alertPopupPref));
        mForegroundService = (TwoStatePreference) findPreference(getString(R.string.foregroundServicePref));

        // Secondary Alerts
        findPreference(getString(R.string.secondaryPref))
                .setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference preference) {
                        if (getActivity() instanceof com.red.alert.activities.Main) {
                            ((com.red.alert.activities.Main) getActivity()).navigateToFragment(
                                    new SecondaryAlertsFragment(), preference.getTitle().toString());
                        }
                        return true;
                    }
                });

        // Early Warnings
        findPreference(getString(R.string.earlyWarningsPref))
                .setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference preference) {
                        if (getActivity() instanceof com.red.alert.activities.Main) {
                            ((com.red.alert.activities.Main) getActivity())
                                    .navigateToFragment(new EarlyWarningsFragment(), preference.getTitle().toString());
                        }
                        return true;
                    }
                });

        // Leave Shelter Alerts
        findPreference(getString(R.string.leaveShelterAlertsPref))
                .setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference preference) {
                        if (getActivity() instanceof com.red.alert.activities.Main) {
                            ((com.red.alert.activities.Main) getActivity()).navigateToFragment(
                                    new LeaveShelterAlertsFragment(), preference.getTitle().toString());
                        }
                        return true;
                    }
                });

        // Location Alerts
        findPreference(getString(R.string.locationPref))
                .setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference preference) {
                        if (getActivity() instanceof com.red.alert.activities.Main) {
                            ((com.red.alert.activities.Main) getActivity())
                                    .navigateToFragment(new LocationAlertsFragment(), preference.getTitle().toString());
                        }
                        return true;
                    }
                });

        // Alert Popup
        findPreference(getString(R.string.alertPopupPref))
                .setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                    @Override
                    public boolean onPreferenceChange(Preference preference, Object newValue) {
                        // Enabled?
                        if ((boolean) newValue) {
                            // Check for overlay permission
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                                    && !Settings.canDrawOverlays(getActivity())) {
                                // Show permission dialog
                                AlertDialogBuilder.showGenericDialog(getString(R.string.grantOverlayPermission),
                                        getString(R.string.grantOverlayPermissionInstructions),
                                        getString(R.string.okay),
                                        getString(R.string.notNow), true, getActivity(), (dialogInterface, which) -> {
                                            if (which == DialogInterface.BUTTON_POSITIVE)
                                                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                        Uri.parse("package:" + getActivity().getPackageName())));
                                        });

                                // Don't enable yet
                                return false;
                            }
                        }
                        return true;
                    }
                });

        // Improve Reliability (Pushy foreground service)
        if (mForegroundService != null) {
            mForegroundService.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    boolean enabled = (boolean) newValue;

                    // Cached Apps Freezer compatibility:
                    // force foreground service for Pixel devices on Android 15+
                    if (Build.MANUFACTURER.toLowerCase().contains("google")
                            && Build.MODEL.toLowerCase().contains("pixel")
                            && Build.VERSION.SDK_INT >= 35) {
                        showForegroundServiceDialog();
                        return false;
                    }

                    final Context appContext = requireContext().getApplicationContext();
                    PushyServiceManager.stop(appContext);
                    Pushy.toggleForegroundService(enabled, appContext);

                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            PushyServiceManager.start(appContext);
                        }
                    }, 2000);

                    if (enabled) {
                        showForegroundServiceDialog();
                    }

                    return true;
                }
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mAlertPopup != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mAlertPopup.setChecked(Settings.canDrawOverlays(requireContext()));
        }
        if (mForegroundService != null) {
            mForegroundService.setChecked(AppPreferences.getForegroundServiceEnabled(requireContext()));
        }
    }

    private void showForegroundServiceDialog() {
        if (!isAdded() || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        AlertDialogBuilder.showGenericDialog(
                getString(R.string.hidePushyForegroundNotification),
                getString(R.string.hidePushyForegroundNotificationInstructions),
                getString(R.string.okay),
                getString(R.string.notNow),
                true,
                requireContext(),
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int which) {
                        if (which != DialogInterface.BUTTON_POSITIVE || !isAdded()) {
                            return;
                        }

                        final Context context = requireContext().getApplicationContext();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                NotificationManager notificationManager =
                                        context.getSystemService(NotificationManager.class);
                                while (notificationManager != null
                                        && notificationManager.getNotificationChannel(
                                        PushyForegroundService.FOREGROUND_NOTIFICATION_CHANNEL) == null) {
                                    try {
                                        Thread.sleep(200);
                                    } catch (Exception ignored) {
                                    }
                                }

                                if (!isAdded()) {
                                    return;
                                }

                                new Handler(Looper.getMainLooper()).post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (!isAdded()) {
                                            return;
                                        }
                                        Intent intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
                                        intent.putExtra(
                                                Settings.EXTRA_CHANNEL_ID,
                                                PushyForegroundService.FOREGROUND_NOTIFICATION_CHANNEL);
                                        intent.putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().getPackageName());
                                        startActivity(intent);
                                    }
                                });
                            }
                        }).start();
                    }
                });
    }
}
