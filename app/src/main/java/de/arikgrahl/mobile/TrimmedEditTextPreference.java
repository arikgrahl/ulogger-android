/*
 * Copyright (c) 2018 Bartek Fabiszewski
 * http://www.fabiszewski.net
 *
 * This file is part of mobile-android.
 * Licensed under GPL, either version 3, or any later.
 * See <http://www.gnu.org/licenses/>
 */

package de.arikgrahl.mobile;

import android.content.Context;
import android.util.AttributeSet;

import androidx.preference.EditTextPreference;

/**
 * Trimmed edit text preference
 * Trims input string
 */
@SuppressWarnings("WeakerAccess")
class TrimmedEditTextPreference extends EditTextPreference {

    public TrimmedEditTextPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public TrimmedEditTextPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public TrimmedEditTextPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public TrimmedEditTextPreference(Context context) {
        super(context);
    }

    @Override
    public void setText(String text) {
        super.setText(text.trim());
    }

}
