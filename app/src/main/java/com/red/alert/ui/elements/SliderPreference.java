package com.red.alert.ui.elements;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.util.AttributeSet;

import androidx.preference.DialogPreference;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceViewHolder;

import com.red.alert.R;

public class SliderPreference extends DialogPreference {
    private int mMin, mMax, mSliderDefaultValue;
    private String mUnits;

    public SliderPreference(Context context, AttributeSet attrs) {
        super(context, attrs);

        // Get custom attributes
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.SliderPreference);
        mMin = a.getInt(R.styleable.SliderPreference_min, 0);
        mMax = a.getInt(R.styleable.SliderPreference_max, 100);
        mSliderDefaultValue = a.getInt(R.styleable.SliderPreference_sliderDefaultValue, 50);
        mUnits = a.getString(R.styleable.SliderPreference_units);
        a.recycle();

        // Set layout
        setDialogLayoutResource(R.layout.slider_preference_dialog);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.itemView.setClickable(true);
    }

    public int getMin() {
        return mMin;
    }

    public int getMax() {
        return mMax;
    }

    public int getSliderDefaultValue() {
        return mSliderDefaultValue;
    }

    public String getUnits() {
        return mUnits;
    }

    // Public wrapper for protected getPersistedInt
    public int getPersistedValue(int defaultValue) {
        try {
            int persisted = getPersistedInt(defaultValue);
            return clamp(persisted);
        } catch (ClassCastException ignored) {
            // Backward compatibility: previous versions stored slider values as float [0..1].
            float legacyValue = getPersistedFloat(defaultValue / 100.0f);
            int migrated;
            if (legacyValue >= 0f && legacyValue <= 1f) {
                migrated = Math.round(legacyValue * 100f);
            } else {
                migrated = Math.round(legacyValue);
            }

            migrated = clamp(migrated);
            persistIntCompat(migrated);

            return migrated;
        }
    }

    // Public wrapper for protected persistInt
    public void setPersistedValue(int value) {
        int clamped = clamp(value);
        persistIntCompat(clamped);
    }

    private int clamp(int value) {
        return Math.max(mMin, Math.min(mMax, value));
    }

    private void persistIntCompat(int value) {
        if (!shouldPersist()) {
            return;
        }

        PreferenceDataStore dataStore = getPreferenceDataStore();
        if (dataStore != null) {
            dataStore.putInt(getKey(), value);
            return;
        }

        SharedPreferences preferences = getSharedPreferences();
        if (preferences == null) {
            return;
        }

        // Remove legacy float value before writing int to prevent ClassCastException
        // from Preference.persistInt() internals during type migration.
        preferences.edit().remove(getKey()).putInt(getKey(), value).apply();
    }
}
