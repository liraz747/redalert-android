package com.red.alert.ui.elements;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceViewHolder;

import com.google.android.material.slider.LabelFormatter;
import com.google.android.material.slider.Slider;

import com.red.alert.R;

public class SliderPreference extends Preference {
    private int mMin, mMax, mSliderDefaultValue;
    private int mValue;
    private String mUnits;

    public SliderPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_slider_material3);
        setSelectable(false);

        int defaultMin = 0;
        int defaultMax = 100;

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
        mValue = getPersistedValue(resolvedDefault);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        TextView valueText = (TextView) holder.findViewById(R.id.slider_preference_value);
        Slider slider = (Slider) holder.findViewById(R.id.slider_preference_seekbar);
        if (slider == null) {
            return;
        }

        slider.clearOnChangeListeners();
        slider.setValueFrom(mMin);
        slider.setValueTo(mMax);
        slider.setStepSize(1f);
        slider.setEnabled(isEnabled());
        slider.setLabelFormatter(new LabelFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return formatValueText(Math.round(value));
            }
        });

        slider.setValue(mValue);
        if (valueText != null) {
            valueText.setText(formatValueText(mValue));
            valueText.setEnabled(isEnabled());
        }

        slider.addOnChangeListener((changedSlider, value, fromUser) -> {
            int newValue = clamp(Math.round(value));
            if (valueText != null) {
                valueText.setText(formatValueText(newValue));
            }

            if (!fromUser || newValue == mValue) {
                return;
            }

            if (!callChangeListener(newValue)) {
                changedSlider.setValue(mValue);
                if (valueText != null) {
                    valueText.setText(formatValueText(mValue));
                }
                return;
            }

            setPersistedValue(newValue);
        });
    }

    public int getSliderDefaultValue() {
        return mSliderDefaultValue;
    }

    public String getUnits() {
        return mUnits;
    }

    public int getMin() {
        return mMin;
    }

    public int getMax() {
        return mMax;
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
        mValue = clamped;
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

    private String formatValueText(int value) {
        int percentage;
        if (mMax > mMin) {
            percentage = Math.round(((float) (value - mMin) / (float) (mMax - mMin)) * 100f);
        } else {
            percentage = value;
        }

        if (mUnits == null) {
            return percentage + "%";
        }
        return percentage + "%" + mUnits;
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
