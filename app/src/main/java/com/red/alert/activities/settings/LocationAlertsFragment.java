package com.red.alert.activities.settings;

import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;

import com.red.alert.R;
import com.red.alert.activities.Map;
import com.red.alert.config.Logging;
import com.red.alert.config.NotificationChannels;
import com.red.alert.config.ThreatTypes;
import com.red.alert.logic.communication.broadcasts.LocationAlertsEvents;
import com.red.alert.logic.location.LocationLogic;
import com.red.alert.logic.push.PushManager;
import com.red.alert.logic.services.ServiceManager;
import com.red.alert.logic.settings.AppPreferences;
import com.red.alert.model.Alert;
import com.red.alert.services.location.LocationService;
import com.red.alert.ui.dialogs.AlertDialogBuilder;
import com.red.alert.ui.elements.MaterialProgressDialog;
import com.red.alert.ui.elements.SliderPreference;
import com.red.alert.utils.backend.RedAlertAPI;
import com.red.alert.utils.caching.Singleton;
import com.red.alert.utils.formatting.StringUtils;
import com.red.alert.utils.integration.GooglePlayServices;
import com.red.alert.utils.localization.DateTime;
import com.red.alert.utils.localization.Localization;
import com.red.alert.utils.metadata.LocationData;
import com.red.alert.utils.threading.AsyncTaskAdapter;

import java.util.ArrayList;
import java.util.List;

public class LocationAlertsFragment extends BasePreferenceFragment {
    private Preference mNearbyCities;
    private SliderPreference mFrequency;
    private SliderPreference mMaxDistance;
    private TwoStatePreference mLocationAlerts;
    private boolean mPendingEnableAfterPermissionGrant;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.settings_location_alerts, rootKey);

        mLocationAlerts = (TwoStatePreference) findPreference(getString(R.string.locationAlertsPref));
        mFrequency = (SliderPreference) findPreference(getString(R.string.gpsFrequencyPref));
        mMaxDistance = (SliderPreference) findPreference(getString(R.string.maxDistancePref));
        mNearbyCities = findPreference(getString(R.string.nearbyCitiesPref));

        verifyGooglePlayServicesAvailable();
        initializeListeners();
        refreshSummaries();
    }

    @Override
    public void onResume() {
        super.onResume();

        if (mPendingEnableAfterPermissionGrant && LocationLogic.isLocationAccessGranted(requireContext())) {
            mPendingEnableAfterPermissionGrant = false;
            if (mLocationAlerts != null && !mLocationAlerts.isChecked()) {
                mLocationAlerts.setChecked(true);
            }
            ServiceManager.startLocationService(requireContext());
            new UpdateSubscriptionsAsync().execute();
        }

        if (mLocationAlerts != null && mLocationAlerts.isChecked()
                && !LocationLogic.isLocationAccessGranted(requireContext())) {
            mPendingEnableAfterPermissionGrant = false;
            mLocationAlerts.setChecked(false);
            ServiceManager.stopLocationService(requireContext());
            if (getActivity() != null) {
                LocationLogic.showLocationAccessRequestDialog(getActivity());
            }
        }

        refreshSummaries();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key.equalsIgnoreCase(LocationAlertsEvents.LOCATION_RECEIVED)
                || key.equals(getString(R.string.gpsFrequencyPref))
                || key.equals(getString(R.string.maxDistancePref))) {
            refreshSummaries();
        }
    }

    private void verifyGooglePlayServicesAvailable() {
        if (getContext() == null || GooglePlayServices.isAvailable(getContext())) {
            return;
        }

        AlertDialogBuilder.showGenericDialog(
                getString(R.string.error),
                getString(R.string.noGooglePlayServices),
                getString(R.string.okay),
                null,
                false,
                requireContext(),
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (getActivity() != null) {
                            getActivity().getOnBackPressedDispatcher().onBackPressed();
                        }
                    }
                });
    }

    private void initializeListeners() {
        if (mMaxDistance != null) {
            mMaxDistance.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object value) {
                    new Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            refreshSummaries();
                            updateLocationService();
                        }
                    }, 200);
                    return true;
                }
            });
        }

        if (mLocationAlerts != null) {
            mLocationAlerts.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    if ((boolean) newValue) {
                        if (!LocationLogic.isLocationAccessGranted(requireContext())) {
                            mPendingEnableAfterPermissionGrant = true;
                            if (getActivity() != null) {
                                LocationLogic.showLocationAccessRequestDialog(getActivity());
                            }
                            return false;
                        }
                        mPendingEnableAfterPermissionGrant = false;
                        ServiceManager.startLocationService(requireContext());
                    } else {
                        mPendingEnableAfterPermissionGrant = false;
                        ServiceManager.stopLocationService(requireContext());
                    }

                    new UpdateSubscriptionsAsync().execute();
                    return true;
                }
            });
        }

        if (mFrequency != null) {
            mFrequency.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object value) {
                    new Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            refreshSummaries();
                            updateLocationService();
                        }
                    }, 200);
                    return true;
                }
            });
        }

        if (mNearbyCities != null) {
            mNearbyCities.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    Location location = LocationLogic.getCurrentLocation(requireContext());
                    if (location == null) {
                        return false;
                    }

                    List<String> nearbyCities = LocationData.getNearbyCities(location, requireContext());
                    List<Alert> mockAlerts = new ArrayList<>();

                    for (String city : nearbyCities) {
                        Alert alert = new Alert();
                        alert.city = city;
                        alert.date = DateTime.getUnixTimestamp();
                        alert.threat = ThreatTypes.NEARBY_CITIES_DISPLAY;
                        mockAlerts.add(alert);
                    }

                    Intent map = new Intent(requireContext(), Map.class);
                    Map.mAlerts = mockAlerts;
                    startActivity(map);
                    return true;
                }
            });
        }
    }

    private void updateLocationService() {
        if (getContext() == null) {
            return;
        }

        requireContext().bindService(new Intent(requireContext(), LocationService.class), new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName componentName, IBinder binder) {
                LocationService.LocalBinder localBinder = (LocationService.LocalBinder) binder;
                LocationService service = localBinder.getService();
                service.updateLocationServiceParams();
                requireContext().unbindService(this);
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
            }
        }, Context.BIND_AUTO_CREATE);
    }

    private String getFrequencySummary(float overrideValue) {
        return getString(R.string.gpsFrequencyDesc) + "\r\n(" + getString(R.string.every) + " "
                + LocationLogic.getUpdateIntervalMinutes(requireContext(), overrideValue) + " "
                + getString(R.string.minutes) + ")";
    }

    private String getMaxDistanceSummary(float overrideValue) {
        return getString(R.string.maxDistanceDesc) + "\r\n("
                + LocationLogic.getMaxDistanceKilometers(requireContext(), overrideValue) + " "
                + getString(R.string.kilometer) + ")";
    }

    private void refreshSummaries() {
        if (!isAdded() || mLocationAlerts == null || mFrequency == null || mMaxDistance == null || mNearbyCities == null) {
            return;
        }

        mMaxDistance.setSummary(Localization.localizeDigits(getMaxDistanceSummary(-1), requireContext()));
        mFrequency.setSummary(Localization.localizeDigits(getFrequencySummary(-1), requireContext()));

        Location location = LocationLogic.getCurrentLocation(requireContext());
        String nearby;

        if (!mLocationAlerts.isChecked()) {
            nearby = "";
        } else if (location == null) {
            nearby = getString(R.string.noLocation);
        } else {
            nearby = LocationData.getNearbyCityNames(location, requireContext());
            if (StringUtils.stringIsNullOrEmpty(nearby)) {
                nearby = getString(R.string.noNearbyCities);
            }
        }

        mNearbyCities.setSummary(nearby);
        mNearbyCities.setEnabled(mLocationAlerts.isChecked());
    }

    private class UpdateSubscriptionsAsync extends AsyncTaskAdapter<Integer, String, Exception> {
        private final Context mContext;
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
                RedAlertAPI.updateNotificationPreferences(mContext);
                RedAlertAPI.subscribe(mContext);
                return null;
            } catch (Exception exc) {
                return exc;
            }
        }

        @Override
        protected void onPostExecute(Exception exc) {
            if (exc != null) {
                Log.e(Logging.TAG, "Updating location subscriptions failed", exc);

                if (getContext() != null) {
                    Singleton.getSharedPreferences(requireContext()).edit()
                            .putBoolean(
                                    getString(R.string.locationAlertsPref),
                                    !AppPreferences.getLocationAlertsEnabled(requireContext()))
                            .commit();
                }
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
            } else if (mLocationAlerts != null
                    && mLocationAlerts.isChecked()
                    && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                AlertDialogBuilder.showGenericDialog(
                        getString(R.string.hideGPSForegroundNotification),
                        getString(R.string.hideGPSForegroundNotificationInstructions),
                        getString(R.string.okay),
                        getString(R.string.notNow),
                        true,
                        requireContext(),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int which) {
                                if (which == DialogInterface.BUTTON_POSITIVE) {
                                    Intent intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
                                    intent.putExtra(
                                            Settings.EXTRA_CHANNEL_ID,
                                            NotificationChannels.LOCATION_SERVICE_FOREGROUND_NOTIFICATION_CHANNEL_ID);
                                    intent.putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().getPackageName());
                                    startActivity(intent);
                                }
                            }
                        });
            }

            refreshSummaries();
        }
    }
}
