/*
 * Copyright (c) 2018 Bartek Fabiszewski
 * http://www.fabiszewski.net
 *
 * This file is part of mobile-android.
 * Licensed under GPL, either version 3, or any later.
 * See <http://www.gnu.org/licenses/>
 */

package de.arikgrahl.mobile;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.text.BidiFormatter;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;

import static de.arikgrahl.mobile.Alert.showAlert;

class ListWithEditTextPreference extends ListPreference implements Preference.OnPreferenceChangeListener {

    private static final String TAG = ListWithEditTextPreference.class.getSimpleName();

    private CharSequence otherSummary;
    private static final String OTHER = "other";


    public ListWithEditTextPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context, attrs, defStyleAttr, defStyleRes);
    }

    public ListWithEditTextPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs, defStyleAttr, 0);

    }

    public ListWithEditTextPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs, 0, 0);

    }

    public ListWithEditTextPreference(Context context) {
        super(context);
        init(context, null, 0, 0);

    }

    private void init(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        setOnPreferenceChangeListener(this);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.ListWithEditTextPreference, defStyleAttr, defStyleRes);
        otherSummary = a.getText(R.styleable.ListWithEditTextPreference_otherSummary);
        a.recycle();
    }


    /**
     * Called when a preference has been changed by the user.
     * This will show EditText dialog for "other" value
     *
     * @param preference The changed preference
     * @param newValue   The new value of the preference
     * @return {@code true} to update the state of the preference with the new value
     */
    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (newValue.toString().equals(OTHER)) {
            showOtherDialog(preference);
            return false;
        }
        return true;
    }

    @Override
    public CharSequence getSummary() {
        if ((getSummaryProvider() != null) && (findIndexOfValue(getValue()) == findIndexOfValue(OTHER))) {
            final String summary = super.getSummary() + " (%s)";
            final String value = getValue();
            final boolean isRtl = BidiFormatter.getInstance().isRtl(summary);
            String wrapped = BidiFormatter.getInstance(isRtl).unicodeWrap(value);
            return String.format(summary, wrapped);
        }
        return super.getSummary();
    }

    /**
     * Returns the index of the given value (in the entry values array).
     *
     * @param value The value whose index should be returned
     * @return The index of the value, or -1 if not found
     */
    @Override
    public int findIndexOfValue(String value) {
        int index = super.findIndexOfValue(value);
        if (index == -1) {
            return super.findIndexOfValue(OTHER);
        } else {
            return index;
        }
    }

    /**
     * Show dialog with EditText
     * @param preference Preference
     */
    private void showOtherDialog(Preference preference) {
        Activity context = getActivity();
        if (context == null) {
            return;
        }
        final String key = preference.getKey();
        final AlertDialog dialog = showAlert(context,
                preference.getTitle(),
                R.layout.other_dialog);
        final TextView textView = dialog.findViewById(R.id.other_textview);
        final EditText editText = dialog.findViewById(R.id.other_edittext);
        final Button submit = dialog.findViewById(R.id.other_button_submit);
        final Button cancel = dialog.findViewById(R.id.other_button_cancel);
        if (textView == null || editText == null || submit == null || cancel == null) {
            return;
        }
        textView.setText(otherSummary);
        textView.setContentDescription(otherSummary);
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        editText.setText(prefs.getString(key, ""));
        editText.setHint(prefs.getString(key, ""));
        submit.setOnClickListener(v -> {
            String newval = editText.getText().toString();
            if (newval.length() > 0 && Integer.valueOf(newval) >= 0) {
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString(key, newval);
                editor.apply();
                setValue(newval);
                if (Logger.DEBUG) { Log.d(TAG, "[" + key + " set to " + newval + "]"); }
            }
            dialog.cancel();
        });

        cancel.setOnClickListener(v -> dialog.cancel());
    }

    /**
     * Get Activity from context
     * @return Activity
     */
    private Activity getActivity() {
        Context context = getContext();
        if (context instanceof Activity) {
            return (Activity) context;
        } else if (context instanceof ContextWrapper
                && ((ContextWrapper) context).getBaseContext() instanceof Activity) {
            return (Activity) ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
