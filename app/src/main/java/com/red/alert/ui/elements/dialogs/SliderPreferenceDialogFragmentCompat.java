package com.red.alert.ui.elements.dialogs;

import android.os.Bundle;

import androidx.preference.PreferenceDialogFragmentCompat;

/**
 * Legacy compatibility stub.
 * Sliders are now inline via SliderPreference (SeekBarPreference),
 * so this dialog should not be shown anymore.
 */
public class SliderPreferenceDialogFragmentCompat extends PreferenceDialogFragmentCompat {

    public static SliderPreferenceDialogFragmentCompat newInstance(String key) {
        SliderPreferenceDialogFragmentCompat fragment = new SliderPreferenceDialogFragmentCompat();
        Bundle args = new Bundle(1);
        args.putString(ARG_KEY, key);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onDialogClosed(boolean positiveResult) {
        // No-op by design.
    }
}
