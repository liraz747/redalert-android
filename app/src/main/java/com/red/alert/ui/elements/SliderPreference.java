package com.red.alert.ui.elements;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.util.AttributeSet;

import androidx.preference.PreferenceDataStore;
import androidx.preference.SeekBarPreference;

import com.red.alert.R;

public class SliderPreference extends SeekBarPreference {
    private int mMin, mMax, mSliderDefaultValue;
    private String mUnits;

    public SliderPreference(Context context, AttributeSet attrs) {
        super(context, attrs);

        // Defaults from SeekBarPreference attributes.
        int defaultMin = getMin();
        int defaultMax = getMax();

        // Get custom attributes
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.SliderPreference);
        mMin = a.hasValue(R.styleable.SliderPreference_min)
                ? a.getInt(R.styleable.SliderPreference_min, defaultMin)
                : defaultMin;
        mMax = a.hasValue(R.styleable.SliderPreference_max)
                ? a.getInt(R.styleable.SliderPreference_max, defaultMax)
                : defaultMax;
        mSliderDefaultValue = a.hasValue(R.styleable.SliderPreference_sliderDefaultValue)
                ? a.getInt(R.styleable.SliderPreference_sliderDefaultValue, 50)
                : 50;
        mUnits = a.getString(R.styleable.SliderPreference_units);
        a.recycle();

        if (mMax < mMin) {
            mMax = mMin;
        }

        setMin(mMin);
        setMax(mMax);
        setAdjustable(true);
        setUpdatesContinuously(false);
        setShowSeekBarValue(false);
    }

    @Override
    protected Object onGetDefaultValue(TypedArray a, int index) {
        try {
            return clamp(a.getInt(index, mSliderDefaultValue));
        } catch (RuntimeException ignored) {
        }

        try {
            return clamp(normalizeSliderValue(a.getFloat(index, mSliderDefaultValue / 100.0f)));
        } catch (RuntimeException ignored) {
        }

        String raw = a.getString(index);
        if (raw != null) {
            try {
                if (raw.contains(".")) {
                    return clamp(normalizeSliderValue(Float.parseFloat(raw)));
                }
                return clamp(Integer.parseInt(raw));
            } catch (NumberFormatException ignored) {
            }
        }

        return clamp(mSliderDefaultValue);
    }

    @Override
    protected void onSetInitialValue(Object defaultValue) {
        int resolvedDefault = resolveDefault(defaultValue);
        setValue(getPersistedValue(resolvedDefault));
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
            int migrated = clamp(normalizeSliderValue(legacyValue));
            persistIntCompat(migrated);

            return migrated;
        }
    }

    // Public wrapper for protected persistInt
    public void setPersistedValue(int value) {
        int clamped = clamp(value);
        persistIntCompat(clamped);
        notifyChanged();
    }

    private int clamp(int value) {
        return Math.max(mMin, Math.min(mMax, value));
    }

    private int normalizeSliderValue(float rawValue) {
        if (rawValue >= 0f && rawValue <= 1f) {
            return Math.round(rawValue * 100f);
        }
        return Math.round(rawValue);
    }

    private int resolveDefault(Object defaultValue) {
        if (defaultValue instanceof Number) {
            return clamp(((Number) defaultValue).intValue());
        }
        if (defaultValue instanceof String) {
            try {
                return clamp(Integer.parseInt((String) defaultValue));
            } catch (NumberFormatException ignored) {
            }
        }
        return clamp(mSliderDefaultValue);
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
