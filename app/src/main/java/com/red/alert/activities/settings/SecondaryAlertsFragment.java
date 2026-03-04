package com.red.alert.activities.settings;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;

import com.red.alert.R;
import com.red.alert.config.Logging;
import com.red.alert.logic.communication.broadcasts.LocationSelectionEvents;
import com.red.alert.logic.push.PushManager;
import com.red.alert.logic.settings.AppPreferences;
import com.red.alert.ui.dialogs.AlertDialogBuilder;
import com.red.alert.ui.elements.MaterialProgressDialog;
import com.red.alert.ui.elements.SearchableMultiSelectPreference;
import com.red.alert.ui.elements.SliderPreference;
import com.red.alert.utils.backend.RedAlertAPI;
import com.red.alert.utils.caching.Singleton;
import com.red.alert.utils.localization.Localization;
import com.red.alert.utils.metadata.LocationData;
import com.red.alert.utils.threading.AsyncTaskAdapter;

public class SecondaryAlertsFragment extends BasePreferenceFragment {
    private String mPreviousSecondaryCities;
    private SliderPreference mSecondaryVolume;
    private TwoStatePreference mSecondaryAlertPopup;
    private TwoStatePreference mSecondaryNotificationsEnabled;
    private SearchableMultiSelectPreference mSecondaryCitySelection;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.settings_secondary_alerts, rootKey);

        mSecondaryVolume = (SliderPreference) findPreference(getString(R.string.secondaryVolumePref));
        mSecondaryAlertPopup = (TwoStatePreference) findPreference(getString(R.string.secondaryAlertPopupPref));
        mSecondaryCitySelection = (SearchableMultiSelectPreference) findPreference(
                getString(R.string.selectedSecondaryCitiesPref));
        mSecondaryNotificationsEnabled = (TwoStatePreference) findPreference(getString(R.string.secondaryEnabledPref));

        initializeSettings();
        initializeListeners();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshAreaValues();
        refreshSecondaryVolumeSummary();
        syncOverlayPermissionState();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key.equalsIgnoreCase(LocationSelectionEvents.LOCATIONS_UPDATED)) {
            refreshAreaValues();
            new UpdateSubscriptionsAsync().execute();
        }

        if (key.equals(getString(R.string.secondaryVolumePref))) {
            refreshSecondaryVolumeSummary();
        }
    }

    private void initializeSettings() {
        if (mSecondaryCitySelection != null) {
            mSecondaryCitySelection.setEntries(LocationData.getAllCityNames(getContext()));
            mSecondaryCitySelection.setEntryValues(LocationData.getAllCityValues(getContext()));
        }

        if (mSecondaryNotificationsEnabled != null && !RedAlertAPI.isRegistered(getContext())) {
            mSecondaryNotificationsEnabled.setEnabled(false);
        }

        refreshAreaValues();
        refreshSecondaryVolumeSummary();
        syncOverlayPermissionState();
    }

    private void refreshAreaValues() {
        if (mSecondaryCitySelection == null || getContext() == null) {
            return;
        }

        String secondaryCities = Singleton.getSharedPreferences(getContext())
                .getString(getString(R.string.selectedSecondaryCitiesPref), getString(R.string.none));

        mSecondaryCitySelection.setSummary(
                getString(R.string.selectedSecondaryCitiesDesc) + "\r\n("
                        + LocationData.getSelectedCityNamesByValues(
                        getContext(),
                        secondaryCities,
                        mSecondaryCitySelection.getEntries(),
                        mSecondaryCitySelection.getEntryValues())
                        + ")");

        if (mPreviousSecondaryCities == null) {
            mPreviousSecondaryCities = secondaryCities;
        }
    }

    private void refreshSecondaryVolumeSummary() {
        if (mSecondaryVolume == null || getContext() == null) {
            return;
        }

        int percent = Math.round(AppPreferences.getSecondaryAlertVolume(getContext(), -1) * 100f);
        String summary = getString(R.string.secondaryVolumeDesc) + "\r\n(" + percent + "%)";
        mSecondaryVolume.setSummary(Localization.localizeDigits(summary, getContext()));
    }

    private void syncOverlayPermissionState() {
        if (mSecondaryAlertPopup == null || getContext() == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        mSecondaryAlertPopup.setChecked(Settings.canDrawOverlays(getContext()));
    }

    private void initializeListeners() {
        if (mSecondaryNotificationsEnabled != null) {
            mSecondaryNotificationsEnabled.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    new UpdateNotificationsAsync().execute();
                    return true;
                }
            });
        }

        if (mSecondaryAlertPopup != null) {
            mSecondaryAlertPopup.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    if ((boolean) newValue && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                            && !Settings.canDrawOverlays(requireContext())) {
                        AlertDialogBuilder.showGenericDialog(
                                getString(R.string.grantOverlayPermission),
                                getString(R.string.grantOverlayPermissionInstructions),
                                getString(R.string.okay),
                                getString(R.string.notNow),
                                true,
                                requireContext(),
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialogInterface, int which) {
                                        if (which == DialogInterface.BUTTON_POSITIVE) {
                                            startActivity(new Intent(
                                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                    Uri.parse("package:" + requireContext().getPackageName())));
                                        }
                                    }
                                });
                        return false;
                    }

                    return true;
                }
            });
        }
    }

    private class UpdateSubscriptionsAsync extends AsyncTaskAdapter<Integer, String, Exception> {
        private final android.content.Context mContext;
        private final MaterialProgressDialog mLoading;

        UpdateSubscriptionsAsync() {
            mContext = requireContext();
            mLoading = new MaterialProgressDialog(mContext);
            mLoading.setCancelable(false);
            mLoading.setMessage(getString(R.string.loading));
            mLoading.show();
        }

        @Override
        protected Exception doInBackground(Integer... parameter) {
            try {
                PushManager.updateSubscriptions(mContext);
                RedAlertAPI.subscribe(mContext);
                return null;
            } catch (Exception exc) {
                return exc;
            }
        }

        @Override
        protected void onPostExecute(Exception exc) {
            if (exc != null && getContext() != null) {
                Log.e(Logging.TAG, "Updating subscriptions failed", exc);
                Singleton.getSharedPreferences(getContext()).edit()
                        .putString(getString(R.string.selectedSecondaryCitiesPref), mPreviousSecondaryCities)
                        .commit();
            }

            if (!isAdded()) {
                return;
            }

            if (mLoading.isShowing()) {
                mLoading.dismiss();
            }

            if (exc != null) {
                String errorMessage = getString(R.string.apiRequestFailed) + "\n\n" + exc.getMessage()
                        + (exc.getCause() != null ? "\n\n" + exc.getCause() : "");
                AlertDialogBuilder.showGenericDialog(getString(R.string.error), errorMessage, getString(R.string.okay),
                        null, false, requireContext(), null);
            } else {
                mPreviousSecondaryCities = null;
            }

            refreshAreaValues();
        }
    }

    private class UpdateNotificationsAsync extends AsyncTaskAdapter<Integer, String, Exception> {
        private final android.content.Context mContext;
        private final MaterialProgressDialog mLoading;

        UpdateNotificationsAsync() {
            mContext = requireContext();
            mLoading = new MaterialProgressDialog(mContext);
            mLoading.setCancelable(false);
            mLoading.setMessage(getString(R.string.loading));
            mLoading.show();
        }

        @Override
        protected Exception doInBackground(Integer... parameter) {
            try {
                PushManager.updateSubscriptions(mContext);
                RedAlertAPI.updateNotificationPreferences(mContext);
                return null;
            } catch (Exception exc) {
                return exc;
            }
        }

        @Override
        protected void onPostExecute(Exception exc) {
            if (exc != null && getContext() != null) {
                Log.e(Logging.TAG, "Updating notification preferences failed", exc);
                Singleton.getSharedPreferences(getContext()).edit()
                        .putBoolean(
                                getString(R.string.secondaryEnabledPref),
                                !AppPreferences.getSecondaryNotificationsEnabled(getContext()))
                        .commit();
            }

            if (!isAdded()) {
                return;
            }

            if (mLoading.isShowing()) {
                mLoading.dismiss();
            }

            if (exc != null) {
                String errorMessage = getString(R.string.apiRequestFailed) + "\n\n" + exc.getMessage()
                        + (exc.getCause() != null ? "\n\n" + exc.getCause() : "");
                AlertDialogBuilder.showGenericDialog(getString(R.string.error), errorMessage, getString(R.string.okay),
                        null, false, requireContext(), null);
            }

            if (mSecondaryNotificationsEnabled != null && getContext() != null) {
                mSecondaryNotificationsEnabled.setChecked(
                        AppPreferences.getSecondaryNotificationsEnabled(requireContext()));
            }
        }
    }
}
