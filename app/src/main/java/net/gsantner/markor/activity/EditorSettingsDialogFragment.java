package net.gsantner.markor.activity;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.Preference;

import com.rarepebble.colorpicker.ColorPreference;

import net.gsantner.markor.R;
import net.gsantner.markor.frontend.filebrowser.MarkorFileBrowserFactory;
import net.gsantner.markor.model.AppSettings;
import net.gsantner.opoc.frontend.base.GsPreferenceFragmentBase;
import net.gsantner.opoc.frontend.filebrowser.GsFileBrowserOptions;

import java.io.File;

/**
 * Editor-only settings shown as an overlay over the current editor.
 * Keeping this separate from SettingsActivity also means opening the normal Settings screen
 * never has to inflate the editor settings tree or its font chooser.
 */
public class EditorSettingsDialogFragment extends DialogFragment {
    private static final String TAG = "EditorSettingsDialogFragment";

    public static void show(@NonNull FragmentManager manager, @NonNull Fragment target) {
        EditorSettingsDialogFragment dialog = new EditorSettingsDialogFragment();
        dialog.setTargetFragment(target, 0);
        dialog.show(manager, TAG);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.dialog_editor_settings, container, false);
        view.findViewById(R.id.editor_settings_close).setOnClickListener(v -> dismiss());
        if (savedInstanceState == null) {
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.editor_settings_fragment_container, new EditorSettingsFragment())
                    .commit();
        }
        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.90f)
            );
        }
    }

    public static class EditorSettingsFragment extends GsPreferenceFragmentBase<AppSettings> {
        @Override
        public int getPreferenceResourceForInflation() {
            return R.xml.preferences_editor;
        }

        @Override
        public String getFragmentTag() {
            return "EditorSettingsFragment";
        }

        @Override
        protected AppSettings getAppSettings(Context context) {
            return AppSettings.get(context);
        }

        @Override
        protected void afterOnCreate(Bundle savedInstances, Context context) {
            super.afterOnCreate(savedInstances, context);
            updateBackgroundImageSummary();
        }

        @Override
        public void doUpdatePreferences() {
            updateBackgroundImageSummary();
        }

        private void updateBackgroundImageSummary() {
            final String path = _appSettings.getEditorBackgroundImagePath();
            final Preference pref = findPreference(R.string.pref_key__editor_background_image_path);
            if (pref != null) {
                final String remove = "/storage/emulated/0/";
                pref.setSummary(TextUtils.isEmpty(path) ? getString(R.string.none) : path.replace(remove, ""));
            }
            refreshColors();
        }

        private void refreshColors() {
            updateColor(R.string.pref_key__basic_color_scheme__bg_light, _appSettings.getInt(R.string.pref_key__basic_color_scheme__bg_light, _cu.rcolor(getContext(), R.color.background)));
            updateColor(R.string.pref_key__basic_color_scheme__fg_light, _appSettings.getInt(R.string.pref_key__basic_color_scheme__fg_light, _cu.rcolor(getContext(), R.color.primary_text)));
            updateColor(R.string.pref_key__basic_color_scheme__bg_dark, _appSettings.getInt(R.string.pref_key__basic_color_scheme__bg_dark, _cu.rcolor(getContext(), R.color.background)));
            updateColor(R.string.pref_key__basic_color_scheme__fg_dark, _appSettings.getInt(R.string.pref_key__basic_color_scheme__fg_dark, _cu.rcolor(getContext(), R.color.primary_text)));
        }

        private void updateColor(int key, int color) {
            Preference pref = findPreference(key);
            if (pref instanceof ColorPreference) {
                try { ((ColorPreference) pref).setColor(color); } catch (Exception ignored) { }
            }
        }

        @Override
        protected void onPreferenceChanged(SharedPreferences prefs, String key) {
            super.onPreferenceChanged(prefs, key);
            final DocumentEditAndViewFragment editor = getEditor();
            if (editor != null) {
                editor.applyEditorSettingsLive();
            }
            if (key != null && key.equals(getString(R.string.pref_key__editor_background_image_path))) {
                updateBackgroundImageSummary();
            }
        }

        @Override
        public Boolean onPreferenceClicked(Preference preference, String key, int keyResId) {
            if (keyResId == R.string.pref_key__editor_background_setting) {
                final DocumentEditAndViewFragment editor = getEditor();
                if (editor != null) {
                    // Keep the parent dialog alive. Dismissing it and immediately showing a
                    // sibling DialogFragment can race FragmentManager state changes and crash the
                    // editor. The background dialog makes the parent window transparent instead.
                    EditorBackgroundSettingsDialogFragment.show(getParentFragmentManager(), editor);
                }
                return true;
            }

            if (keyResId == R.string.pref_key__editor_background_image_path) {
                final Activity activity = getActivity();
                final DocumentEditAndViewFragment editor = getEditor();
                if (activity == null || editor == null) {
                    return true;
                }
                MarkorFileBrowserFactory.showFileDialog(new GsFileBrowserOptions.SelectionListenerAdapter() {
                    @Override
                    public void onFsViewerSelected(String request, File file, Integer lineNumber) {
                        _appSettings.setEditorBackgroundImagePath(file.getAbsolutePath());
                        _appSettings.setEditorBackgroundEnabled(true);
                        updateBackgroundImageSummary();
                        editor.applyEditorSettingsLive();
                    }

                    @Override
                    public void onFsViewerConfig(GsFileBrowserOptions.Options dopt) {
                        dopt.titleText = R.string.editor_background_image_path;
                        dopt.rootFolder = _appSettings.getNotebookDirectory();
                        dopt.newDirButtonEnable = false;
                    }
                }, getParentFragmentManager(), activity, MarkorFileBrowserFactory.IsMimeImage);
                return true;
            }

            if (key.startsWith("pref_key__editor_basic_color_scheme") && !key.contains("_fg_") && !key.contains("_bg_")) {
                applyColorScheme(key);
                _appSettings.setRecreateMainRequired(true);
                final DocumentEditAndViewFragment editor = getEditor();
                if (editor != null) {
                    editor.applyEditorSettingsLive();
                }
                refreshColors();
                return true;
            }
            return null;
        }

        private void applyColorScheme(final String key) {
            if (key.equals(getString(R.string.pref_key__basic_color_scheme_markor))) {
                _appSettings.setEditorBasicColor(true, R.color.dark__primary_text, R.color.dark__background);
                _appSettings.setEditorBasicColor(false, R.color.light__primary_text, R.color.light__background);
            } else if (key.equals(getString(R.string.pref_key__basic_color_scheme_blackorwhite))) {
                _appSettings.setEditorBasicColor(true, R.color.white, R.color.black);
                _appSettings.setEditorBasicColor(false, R.color.black, R.color.white);
            } else if (key.equals(getString(R.string.pref_key__basic_color_scheme_solarized))) {
                _appSettings.setEditorBasicColor(true, R.color.solarized_fg, R.color.solarized_bg_dark);
                _appSettings.setEditorBasicColor(false, R.color.solarized_fg, R.color.solarized_bg_light);
            } else if (key.equals(getString(R.string.pref_key__basic_color_scheme_gruvbox))) {
                _appSettings.setEditorBasicColor(true, R.color.gruvbox_fg_dark, R.color.gruvbox_bg_dark);
                _appSettings.setEditorBasicColor(false, R.color.gruvbox_fg_light, R.color.gruvbox_bg_light);
            } else if (key.equals(getString(R.string.pref_key__basic_color_scheme_nord))) {
                _appSettings.setEditorBasicColor(true, R.color.nord_fg_dark, R.color.nord_bg_dark);
                _appSettings.setEditorBasicColor(false, R.color.nord_fg_light, R.color.nord_bg_light);
            } else if (key.equals(getString(R.string.pref_key__basic_color_scheme_greenscale))) {
                _appSettings.setEditorBasicColor(true, R.color.green_dark, R.color.black);
                _appSettings.setEditorBasicColor(false, R.color.green_light, R.color.white);
            } else if (key.equals(getString(R.string.pref_key__basic_color_scheme_sepia))) {
                _appSettings.setEditorBasicColor(true, R.color.sepia_bg_light__fg_dark, R.color.sepia_fg_light__bg_dark);
                _appSettings.setEditorBasicColor(false, R.color.sepia_fg_light__bg_dark, R.color.sepia_bg_light__fg_dark);
            }
        }

        @Nullable
        private DocumentEditAndViewFragment getEditor() {
            Fragment target = getParentFragment() != null
                    ? getParentFragment().getTargetFragment()
                    : null;
            return target instanceof DocumentEditAndViewFragment ? (DocumentEditAndViewFragment) target : null;
        }

        @Override
        public boolean isDividerVisible() {
            return false;
        }
    }
}
