package net.gsantner.markor.activity;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.Fragment;

import net.gsantner.markor.R;
import net.gsantner.markor.frontend.filebrowser.MarkorFileBrowserFactory;
import net.gsantner.markor.model.AppSettings;
import net.gsantner.opoc.frontend.filebrowser.GsFileBrowserOptions;

import java.io.File;

/** Transparent live editor-background tuning overlay. */
public class EditorBackgroundSettingsDialogFragment extends DialogFragment {
    private static final String TAG = "EditorBackgroundSettings";

    public static void show(@NonNull androidx.fragment.app.FragmentManager manager,
                            @NonNull DocumentEditAndViewFragment editor) {
        final EditorBackgroundSettingsDialogFragment dialog = new EditorBackgroundSettingsDialogFragment();
        dialog.setTargetFragment(editor, 0);
        dialog.show(manager, TAG);
    }

    private AppSettings settings() {
        return AppSettings.get(requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_editor_background_settings, container, false);
    }

    @Override
    public void onStart() {
        super.onStart();
        final Window window = getDialog() == null ? null : getDialog().getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0f);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }

        // Make the existing Editor Settings window transparent rather than dismissing it.
        // This keeps its FragmentManager state intact while exposing the live editor behind
        // this transparent tuning overlay.
        final FragmentManager fm = getParentFragmentManager();
        final Fragment parent = fm.findFragmentByTag("EditorSettingsDialogFragment");
        if (parent instanceof DialogFragment) {
            final Dialog parentDialog = ((DialogFragment) parent).getDialog();
            if (parentDialog != null && parentDialog.getWindow() != null) {
                parentDialog.getWindow().getDecorView().setAlpha(0f);
            }
        }
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        // Restore the existing settings dialog instead of creating a new one.
        final FragmentManager fm = getParentFragmentManager();
        final Fragment parent = fm.findFragmentByTag("EditorSettingsDialogFragment");
        if (parent instanceof DialogFragment) {
            final Dialog parentDialog = ((DialogFragment) parent).getDialog();
            if (parentDialog != null && parentDialog.getWindow() != null) {
                parentDialog.getWindow().getDecorView().setAlpha(1f);
            }
        }
        super.onDismiss(dialog);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        final AppSettings settings = settings();
        final DocumentEditAndViewFragment editor = getEditor();
        if (editor == null) {
            dismiss();
            return;
        }

        final Switch enabled = view.findViewById(R.id.editor_background_enabled);
        final Button choose = view.findViewById(R.id.editor_background_choose);
        final TextView path = view.findViewById(R.id.editor_background_path);
        final SeekBar x = view.findViewById(R.id.editor_background_x);
        final SeekBar y = view.findViewById(R.id.editor_background_y);
        final SeekBar blur = view.findViewById(R.id.editor_background_blur);
        final SeekBar darkness = view.findViewById(R.id.editor_background_darkness);
        final TextView xLabel = view.findViewById(R.id.editor_background_x_label);
        final TextView yLabel = view.findViewById(R.id.editor_background_y_label);
        final TextView blurLabel = view.findViewById(R.id.editor_background_blur_label);
        final TextView darknessLabel = view.findViewById(R.id.editor_background_darkness_label);

        enabled.setChecked(settings.isEditorBackgroundEnabled());
        x.setProgress(settings.getEditorBackgroundX());
        y.setProgress(settings.getEditorBackgroundY());
        blur.setProgress(settings.getEditorBackgroundBlur());
        darkness.setProgress(settings.getEditorBackgroundDarkness());
        updatePath(path, settings.getEditorBackgroundImagePath());
        updateLabels(xLabel, yLabel, blurLabel, darknessLabel, x.getProgress(), y.getProgress(), blur.getProgress(), darkness.getProgress());

        enabled.setOnCheckedChangeListener((button, checked) -> {
            settings.setEditorBackgroundEnabled(checked);
            editor.applyEditorSettingsLive();
        });

        choose.setOnClickListener(v -> {
            final Activity activity = getActivity();
            if (activity == null) return;
            MarkorFileBrowserFactory.showFileDialog(new GsFileBrowserOptions.SelectionListenerAdapter() {
                @Override
                public void onFsViewerSelected(String request, File file, Integer lineNumber) {
                    settings.setEditorBackgroundImagePath(file.getAbsolutePath());
                    settings.setEditorBackgroundEnabled(true);
                    enabled.setChecked(true);
                    updatePath(path, file.getAbsolutePath());
                    editor.applyEditorSettingsLive();
                }

                @Override
                public void onFsViewerConfig(GsFileBrowserOptions.Options dopt) {
                    dopt.titleText = R.string.editor_background_image_path;
                    dopt.rootFolder = settings.getNotebookDirectory();
                    dopt.newDirButtonEnable = false;
                }
            }, getParentFragmentManager(), activity, MarkorFileBrowserFactory.IsMimeImage);
        });

        x.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                settings.setEditorBackgroundX(progress);
                xLabel.setText(getString(R.string.editor_background_x_value, progress));
                editor.applyEditorSettingsLive();
            }
        });
        y.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                settings.setEditorBackgroundY(progress);
                yLabel.setText(getString(R.string.editor_background_y_value, progress));
                editor.applyEditorSettingsLive();
            }
        });
        blur.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                settings.setEditorBackgroundBlur(progress);
                blurLabel.setText("Blur: " + progress);
                editor.applyEditorSettingsLive();
            }
        });
        darkness.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                settings.setEditorBackgroundDarkness(progress);
                darknessLabel.setText("Darkness: " + progress);
                editor.applyEditorSettingsLive();
            }
        });

        ((Button) view.findViewById(R.id.editor_background_close)).setOnClickListener(v -> dismiss());
    }

    private void updatePath(TextView view, String value) {
        view.setText(TextUtils.isEmpty(value) ? getString(R.string.none) : value.replace("/storage/emulated/0/", ""));
    }

    private void updateLabels(TextView x, TextView y, TextView blur, TextView darkness,
                              int xp, int yp, int bp, int dp) {
        x.setText(getString(R.string.editor_background_x_value, xp));
        y.setText(getString(R.string.editor_background_y_value, yp));
        blur.setText("Blur: " + bp);
        darkness.setText("Darkness: " + dp);
    }

    private DocumentEditAndViewFragment getEditor() {
        final Fragment target = getTargetFragment();
        return target instanceof DocumentEditAndViewFragment ? (DocumentEditAndViewFragment) target : null;
    }

    private abstract static class SimpleSeekBarListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) { }
        @Override public void onStopTrackingTouch(SeekBar seekBar) { }
    }
}
