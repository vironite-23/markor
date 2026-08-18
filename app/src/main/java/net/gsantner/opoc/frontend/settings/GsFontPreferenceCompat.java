/*#######################################################
 *
 * SPDX-FileCopyrightText: 2018-2025 Gregor Santner <gsantner AT mailbox DOT org>
 * SPDX-License-Identifier: Unlicense OR CC0-1.0
 *
 * Written 2018-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 * To the extent possible under law, the author(s) have dedicated all copyright and related and neighboring rights to this software to the public domain worldwide. This software is distributed without any warranty.
 * You should have received a copy of the CC0 Public Domain Dedication along with this software. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
#########################################################*/

/*
 * Define element in Preferences-XML:
    <net.gsantner.opoc.preference.FontPreferenceCompat
        android:icon="@drawable/ic_title_black_24dp"
        android:defaultValue="@string/default_font_family"
        android:key="@string/pref_key__font_family"
        android:title="@string/pref_title__font_choice" />
 */
package net.gsantner.opoc.frontend.settings;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Environment;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.MetricAffectingSpan;
import android.text.style.RelativeSizeSpan;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.preference.ListPreference;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A {@link ListPreference} that displays a list of fonts to select from
 * This list contains fonts that are bundled with android
 * <p>
 * Apply to TextView:
 * setTypeface(Typeface.create(settings.getFontFamilyAsString(), Typeface.NORMAL));
 */
@SuppressWarnings({"unused", "SpellCheckingInspection", "WeakerAccess"})
public class GsFontPreferenceCompat extends ListPreference {
    public static File additionalyCheckedFolder = null;
    public static final FilenameFilter FONT_FILENAME_FILTER = (file, s) -> s.toLowerCase().endsWith(".ttf") || s.toLowerCase().endsWith(".otf");
    private final static String ANDROID_ASSET_DIR = "/android_asset/";
    private String _defaultValue;
    private String[] _fontNames = {
            "Roboto Regular", "Roboto Light", "Roboto Bold", "Roboto Medium",
            "Monospace", "Noto Serif", "Cutive Mono", "Roboto Condensed", "Roboto Thin",
            "Roboto Black", "Coming Soon", "Carrois Gothic", "Dancing Script"
    };
    private String[] _fontValues = {
            "sans-serif-regular", "sans-serif-light", "sans-serif-bold", "sans-serif-medium",
            "monospace", "serif", "serif-monospace", "sans-serif-condensed", "sans-serif-thin",
            "sans-serif-black", "casual", "sans-serif-smallcaps", "cursive"
    };

    public GsFontPreferenceCompat(Context context) {
        super(context);
        initializeWithoutScanning(context, null);
    }

    public GsFontPreferenceCompat(Context context, AttributeSet attrs) {
        super(context, attrs);
        initializeWithoutScanning(context, attrs);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public GsFontPreferenceCompat(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initializeWithoutScanning(context, attrs);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public GsFontPreferenceCompat(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        initializeWithoutScanning(context, attrs);
    }

    private void initializeWithoutScanning(Context context, @Nullable AttributeSet attrs) {
        _defaultValue = _fontValues[0];
        if (attrs != null) {
            for (int i = 0; i < attrs.getAttributeCount(); i++) {
                String attrName = attrs.getAttributeName(i);
                String attrValue = attrs.getAttributeValue(i);
                if (attrName.equalsIgnoreCase("defaultValue")) {
                    if (attrValue.startsWith("@")) {
                        int resId = Integer.valueOf(attrValue.substring(1));
                        attrValue = getContext().getString(resId);
                    }
                    _defaultValue = attrValue;
                    break;
                }
            }
        }

        // Do not scan storage or create Typefaces here. AndroidX Preference inflates nested
        // PreferenceScreens eagerly, so doing filesystem/native font work in this constructor
        // makes opening the top-level Settings screen unexpectedly expensive.
        setDefaultValue(_defaultValue);
    }

    private void loadFonts(Context context) {
        loadFonts(context, null);
    }

    // Bugfix (Settings taking a very long time / sometimes ANR-crashing to open):
    // Every time this preference is inflated (i.e. every single time the Settings screen is
    // opened - androidx.preference eagerly instantiates *all* preferences declared in the XML,
    // including ones inside nested <PreferenceScreen> blocks that aren't even visible yet) this
    // used to synchronously: (1) scan several filesystem directories - including external
    // storage - for font files, and (2) call Typeface.createFromFile()/createFromAsset() (disk
    // I/O + native font parsing) for every single font found, all on the UI thread. With more
    // than a few custom fonts installed this routinely took long enough to trigger an ANR.
    // The scan result never changes during a single app run (fonts on disk don't change while
    // Settings is open), so we now compute it once per process and reuse it on every subsequent
    // open. Call resetFontCache() if the font directories change during the app's lifetime
    // (e.g. after a "change notebook directory" action) so the list is picked up again.
    private static String[] _cachedFontNames = null;
    private static String[] _cachedFontValues = null;
    private static Spannable[] _cachedFontText = null;

    public static synchronized void resetFontCache() {
        _cachedFontNames = null;
        _cachedFontValues = null;
        _cachedFontText = null;
    }

    private void loadFonts(Context context, @Nullable AttributeSet attrs) {
        synchronized (GsFontPreferenceCompat.class) {
            if (_cachedFontNames == null || _cachedFontValues == null || _cachedFontText == null) {
                String[] names = _fontNames;
                String[] values = _fontValues;
                for (File file : getAdditionalFonts()) {
                    names = appendToArray(names, file.getName().replace(".ttf", "").replace(".TTF", ""));
                    values = appendToArray(values, file.getAbsolutePath());
                }

                Spannable[] fontText = new Spannable[names.length];
                for (int i = 0; i < names.length; i++) {
                    fontText[i] = new SpannableString(names[i] + "\n" + values[i]);
                    fontText[i].setSpan(new TypefaceObjectSpan(typeface(getContext(), values[i], null)), 0, names[i].length(), 0);
                    fontText[i].setSpan(new RelativeSizeSpan(0.7f), names[i].length() + 1, fontText[i].length(), 0);
                }

                _cachedFontNames = names;
                _cachedFontValues = values;
                _cachedFontText = fontText;
            }

            _fontNames = _cachedFontNames;
            _fontValues = _cachedFontValues;
        }

        setDefaultValue(_defaultValue);
        setEntries(_cachedFontText);
        setEntryValues(_fontValues);
    }

    public static Typeface typeface(Context context, String familyOrFilepath, Integer typefaceStyle) {
        if (typefaceStyle == null) {
            typefaceStyle = Typeface.NORMAL;
        }
        if (!familyOrFilepath.startsWith("/")) {
            return Typeface.create(familyOrFilepath, typefaceStyle);
        } else {
            try {
                if (familyOrFilepath.startsWith(ANDROID_ASSET_DIR)) {
                    return Typeface.createFromAsset(context.getAssets(), familyOrFilepath.substring(ANDROID_ASSET_DIR.length()));

                } else {
                    return Typeface.createFromFile(familyOrFilepath);
                }
            } catch (RuntimeException exception) {
                return typeface(context, "sans-serif-regular", typefaceStyle);
            }
        }
    }

    @Override
    protected void onClick() {
        // The expensive directory scan and Typeface creation happen only when the user
        // actually opens the font chooser.
        if (_cachedFontNames == null || _cachedFontValues == null || _cachedFontText == null) {
            loadFonts(getContext());
        } else {
            _fontNames = _cachedFontNames;
            _fontValues = _cachedFontValues;
            setEntries(_cachedFontText);
            setEntryValues(_fontValues);
        }
        super.onClick();
    }

    @Override
    public CharSequence getSummary() {
        String prefix = TextUtils.isEmpty(super.getSummary())
                ? "" : super.getSummary() + "\n\n";
        String fontText = TextUtils.isEmpty(getValue()) ? _defaultValue : getValue();
        for (int i = 0; i < _fontValues.length; i++) {
            if (_fontValues[i].equals(fontText)) {
                fontText = _fontNames[i] + " (" + fontText + ")";
                break;
            }
        }
        fontText = fontText.replace("★", "");
        return prefix + fontText;
    }

    public String[] getFontNames() {
        return _fontNames;
    }

    public void setFontNames(String[] fontNames) {
        _fontNames = fontNames;
    }

    public String[] getFontValues() {
        return _fontValues;
    }

    public void setFontValues(String[] fontValues) {
        _fontValues = fontValues;
    }


    @SuppressWarnings("ResultOfMethodCallIgnored")
    public List<File> getAdditionalFonts() {
        final ArrayList<File> additionalFonts = new ArrayList<>();

        // Bundled fonts
        try {
            //noinspection ConstantConditions
            for (String filename : getContext().getAssets().list("fonts")) {
                additionalFonts.add(new File(ANDROID_ASSET_DIR + "fonts", filename));
            }
        } catch (Exception ignored) {
        }

        // Directories that are additionally checked out for fonts
        final List<File> checkedDirs = new ArrayList<>(Arrays.asList(
                new File(getContext().getFilesDir(), ".app/fonts"),
                new File(getContext().getFilesDir(), ".app/Fonts"),
                additionalyCheckedFolder,
                new File(Environment.getExternalStorageDirectory(), "fonts"),
                new File(Environment.getExternalStorageDirectory(), "Fonts")
        ));

        // Also check external storage directories, at the respective root and data directory
        for (File externalFileDir : ContextCompat.getExternalFilesDirs(getContext(), null)) {
            if (externalFileDir == null || externalFileDir.getAbsolutePath() == null) {
                continue;
            }
            checkedDirs.add(new File(externalFileDir.getAbsolutePath().replaceFirst("/Android/data/.*$", "/fonts")));
            checkedDirs.add(new File(externalFileDir.getAbsolutePath().replaceFirst("/Android/data/.*$", "/Fonts")));
            checkedDirs.add(new File(externalFileDir.getAbsolutePath(), "/fonts"));
            checkedDirs.add(new File(externalFileDir.getAbsolutePath(), "/Fonts"));
        }
        // Check all directories for fonts
        for (File checkedDir : checkedDirs) {
            if (checkedDir != null && checkedDir.exists()) {
                File[] checkedDirFiles = checkedDir.listFiles(FONT_FILENAME_FILTER);
                if (checkedDirFiles != null) {
                    for (File font : checkedDirFiles) {
                        if (!additionalFonts.contains(new File(font.getAbsolutePath().replace("/Fonts/", "/fonts/")))) {
                            additionalFonts.add(font);
                        }
                    }
                }
            }
        }

        return additionalFonts;
    }

    private static String[] appendToArray(String[] arr, String append) {
        List<String> arro = new ArrayList<>(Arrays.asList(arr));
        arro.add(append);
        return arro.toArray(new String[arr.length + 1]);
    }


    public class TypefaceObjectSpan extends MetricAffectingSpan {
        private final Typeface _typeface;

        public TypefaceObjectSpan(final Typeface typeface) {
            _typeface = typeface;
        }

        @Override
        public void updateDrawState(final TextPaint drawState) {
            apply(drawState);
        }

        @Override
        public void updateMeasureState(final TextPaint paint) {
            apply(paint);
        }

        private void apply(final Paint paint) {
            final Typeface oldTypeface = paint.getTypeface();
            final int oldStyle = oldTypeface != null ? oldTypeface.getStyle() : 0;
            final int fakeStyle = oldStyle & ~_typeface.getStyle();

            if ((fakeStyle & Typeface.BOLD) != 0) {
                paint.setFakeBoldText(true);
            }

            if ((fakeStyle & Typeface.ITALIC) != 0) {
                paint.setTextSkewX(-0.25f);
            }

            paint.setTypeface(_typeface);
        }
    }
}
