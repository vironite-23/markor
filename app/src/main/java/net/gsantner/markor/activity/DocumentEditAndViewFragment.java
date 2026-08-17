/*#######################################################
 *
 *   Maintained 2017-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.core.view.GravityCompat;
import androidx.core.widget.NestedScrollView;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;

import net.gsantner.markor.ApplicationObject;
import net.gsantner.markor.BuildConfig;
import net.gsantner.markor.R;
import net.gsantner.markor.format.ActionButtonBase;
import net.gsantner.markor.format.FormatRegistry;
import net.gsantner.markor.format.TextConverterBase;
import net.gsantner.markor.frontend.DraggableScrollbarScrollView;
import net.gsantner.markor.frontend.FileInfoDialog;
import net.gsantner.markor.frontend.MarkorDialogFactory;
import net.gsantner.markor.frontend.filebrowser.MarkorFileBrowserFactory;
import net.gsantner.markor.frontend.textview.HighlightingEditor;
import net.gsantner.markor.frontend.textview.LineNumbersView;
import net.gsantner.markor.frontend.textview.TextViewUtils;
import net.gsantner.markor.model.AppSettings;
import net.gsantner.markor.model.Document;
import net.gsantner.markor.util.MarkorContextUtils;
import net.gsantner.markor.web.DraggableScrollbarWebView;
import net.gsantner.markor.web.MarkorWebViewClient;
import net.gsantner.markor.widget.TodoWidgetProvider;
import net.gsantner.opoc.frontend.filebrowser.GsFileBrowserOptions;
import net.gsantner.opoc.frontend.settings.GsFontPreferenceCompat;
import net.gsantner.opoc.frontend.textview.TextViewUndoRedo;
import net.gsantner.opoc.util.GsContextUtils;
import net.gsantner.opoc.util.GsCoolExperimentalStuff;
import net.gsantner.opoc.util.GsFileUtils;
import net.gsantner.opoc.web.GsWebViewChromeClient;
import net.gsantner.opoc.wrapper.GsCallback;
import net.gsantner.opoc.wrapper.GsTextWatcherAdapter;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressWarnings({"UnusedReturnValue"})
@SuppressLint("NonConstantResourceId")
public class DocumentEditAndViewFragment extends MarkorBaseFragment implements FormatRegistry.TextFormatApplier {
    public static final String FRAGMENT_TAG = "DocumentEditAndViewFragment";
    public static final String SAVESTATE_DOCUMENT = "DOCUMENT";
    public static final String START_PREVIEW = "START_PREVIEW";

    public static float VIEW_FONT_SCALE = 100f / 15.7f;

    public static DocumentEditAndViewFragment newInstance(final @NonNull Document document, final Integer lineNumber, final Boolean preview) {
        DocumentEditAndViewFragment f = new DocumentEditAndViewFragment();
        Bundle args = new Bundle();
        args.putSerializable(Document.EXTRA_DOCUMENT, document);
        if (lineNumber != null) {
            args.putInt(Document.EXTRA_FILE_LINE_NUMBER, lineNumber);
        }
        if (preview != null) {
            args.putBoolean(START_PREVIEW, preview);
        }
        f.setArguments(args);
        return f;
    }

    private HighlightingEditor _hlEditor;
    private WebView _webView;
    private ViewStub _webViewStub;
    private MarkorWebViewClient _webViewClient;
    private ViewGroup _editorHolder;
    private ViewGroup _textActionsBar;

    private DraggableScrollbarScrollView _verticalScrollView;
    private HorizontalScrollView _horizontalScrollView;
    private LineNumbersView _lineNumbersView;
    private TextView _searchResultTextView;
    private Document _document;
    private FormatRegistry _format;
    private MarkorContextUtils _cu;
    private TextViewUndoRedo _editTextUndoRedoHelper;
    private MenuItem _saveMenuItem, _undoMenuItem, _redoMenuItem;
    private boolean _isPreviewVisible;
    private boolean _nextConvertToPrintMode = false;

    // Item 5: swipe-left read-only panel showing another text file from the same folder
    private static final ExecutorService SIDEPANEL_EXECUTOR = Executors.newSingleThreadExecutor();
    private DrawerLayout _sidePanelDrawerLayout;
    private View _sidePanelSearchBar;
    private TextView _sidePanelTitle;
    private TextView _sidePanelText;
    private EditText _sidePanelSearchInput;
    private NestedScrollView _sidePanelScroll;
    private File _sidePanelCurrentFile;
    private int _sidePanelPendingScrollY = -1;

    // Item 6: swipe-right file-list panel (files in the same folder as the current document)
    private RecyclerView _sidePanelFileListRecycler;
    private TextView _sidePanelFileListTitle;
    private View _sidePanelFileListRoot;

    public DocumentEditAndViewFragment() {
        super();
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Bundle args = getArguments();
        if (savedInstanceState != null && savedInstanceState.containsKey(SAVESTATE_DOCUMENT)) {
            _document = (Document) savedInstanceState.getSerializable(SAVESTATE_DOCUMENT);
        } else if (args != null && args.containsKey(Document.EXTRA_DOCUMENT)) {
            _document = (Document) args.get(Document.EXTRA_DOCUMENT);
        }
    }

    @Override
    protected int getLayoutResId() {
        return R.layout.document__fragment__edit;
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        final Activity activity = getActivity();

        _hlEditor = view.findViewById(R.id.document__fragment__edit__highlighting_editor);
        _editorHolder = view.findViewById(R.id.document__fragment__edit__editor_holder);
        _textActionsBar = view.findViewById(R.id.document__fragment__edit__text_actions_bar);
        _webViewStub = view.findViewById(R.id.document__fragment_webview_stub);
        _verticalScrollView = view.findViewById(R.id.document__fragment__edit__content_editor__scrolling_parent);
        _lineNumbersView = view.findViewById(R.id.document__fragment__edit__line_numbers_view);
        _cu = new MarkorContextUtils(activity);
        _editTextUndoRedoHelper = new TextViewUndoRedo();

        if (isStateBad()) {
            Toast.makeText(activity, R.string.error_could_not_open_file + " " + getDocument().file, Toast.LENGTH_LONG).show();
            if (activity != null) {
                activity.finish();
            }
            return;
        }

        _lineNumbersView.setup(_hlEditor);
        _lineNumbersView.setLineNumbersEnabled(_appSettings.getDocumentLineNumbersEnabled(_document.path));

        setupSidePanel(view);

        applyTextFormat(_appSettings.getDocumentFormat(_document.path, _document.getFormat()));

        if (activity instanceof DocumentActivity) {
            ((DocumentActivity) activity).setDocumentTitle(_document.title);
        }

        final Bundle args = getArguments();
        final boolean startInPreview = _appSettings.getDocumentPreviewState(_document.path);
        if (args != null && savedInstanceState == null) {
            setViewModeVisibility(args.getBoolean(START_PREVIEW, startInPreview), false);
        } else {
            setViewModeVisibility(startInPreview, false);
        }

        _hlEditor.setLineSpacing(0, _appSettings.getEditorLineSpacing());
        _hlEditor.setTextSize(TypedValue.COMPLEX_UNIT_SP, _appSettings.getDocumentFontSize(_document.path));
        _hlEditor.setTypeface(GsFontPreferenceCompat.typeface(getContext(), _appSettings.getFontFamily(), Typeface.NORMAL));
        _hlEditor.setBackgroundColor(_appSettings.getEditorBackgroundColor());
        _hlEditor.setTextColor(_appSettings.getEditorForegroundColor());
        _hlEditor.setGravity(_appSettings.isEditorStartEditingInCenter() ? Gravity.CENTER : Gravity.NO_GRAVITY);
        _hlEditor.setHighlightingEnabled(_appSettings.getDocumentHighlightState(_document.path, _hlEditor.getText()));
        _hlEditor.setAutoFormatEnabled(_appSettings.getDocumentAutoFormatEnabled(_document.path));
        _hlEditor.setSaveInstanceState(false);
        _hlEditor.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            _hlEditor.setImportantForAccessibility(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        }

        setWrapState(isDisplayedAtMainActivity() || _appSettings.getDocumentWrapState(_document.path));
        updateMenuToggleStates(0);

        _document.resetChangeTracking();

        final Runnable debounced = TextViewUtils.makeDebounced(500, () -> {
            checkTextChangeState();
            updateUndoRedoIconStates();
        });
        _hlEditor.addTextChangedListener(GsTextWatcherAdapter.after(s -> debounced.run()));

        if (activity != null) {
            final Window window = activity.getWindow();
            final int adjustResize = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
            final int unchanged = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED | adjustResize;
            final int hidden = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN | adjustResize;
            final int shown = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE | adjustResize;

            _hlEditor.getViewTreeObserver().addOnWindowFocusChangeListener(hasFocus -> {
                if (hasFocus) {
                    _hlEditor.postDelayed(() -> window.setSoftInputMode(unchanged), 500);
                } else {
                    final Boolean isOpen = TextViewUtils.isImeOpen(_hlEditor);
                    if (isOpen != null) {
                        window.setSoftInputMode(isOpen ? shown : hidden);
                    }
                }
            });
        }

        syncEditorMinHeightOnce(_verticalScrollView);
    }

    @Override
    protected void onFragmentFirstTimeVisible() {
        final Bundle args = getArguments();
        int startPos = _appSettings.getLastEditPosition(_document.path, _hlEditor.length());
        if (args != null && args.containsKey(Document.EXTRA_FILE_LINE_NUMBER)) {
            final int lno = args.getInt(Document.EXTRA_FILE_LINE_NUMBER);
            if (lno >= 0) {
                startPos = TextViewUtils.getIndexFromLineOffset(_hlEditor.getText(), lno, 0);
            } else {
                startPos = _hlEditor.length();
            }
        }

        _hlEditor.recomputeHighlighting();
        TextViewUtils.setSelectionAndShow(_hlEditor, startPos);

        syncEditorMinHeightOnce(_editorHolder);

        _hlEditor.post(() -> _hlEditor.animate().alpha(1).setDuration(250).start());
    }

    @Override
    public void onResume() {
        if (_webView != null) {
            _webView.onResume();
        }
        loadDocument();
        if (_editTextUndoRedoHelper != null && _editTextUndoRedoHelper.getTextView() != _hlEditor) {
            _editTextUndoRedoHelper.setTextView(_hlEditor);
        }
        super.onResume();
    }

    @Override
    public void onPause() {
        saveDocument(false);
        if (_webView != null) {
            _webView.onPause();
        }
        _appSettings.addRecentFile(_document.file);
        _appSettings.setDocumentPreviewState(_document.path, _isPreviewVisible);
        _appSettings.setLastEditPosition(_document.path, TextViewUtils.getSelection(_hlEditor)[0]);
        _appSettings.setLastEditScrollY(_document.path, _verticalScrollView.getScrollY());
        persistSidePanelScrollY();
        if (_document.path.equals(_appSettings.getTodoFile().getAbsolutePath())) {
            TodoWidgetProvider.updateTodoWidgets();
        }
        super.onPause();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putSerializable(SAVESTATE_DOCUMENT, _document);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.document__edit__menu, menu);
        _cu.tintMenuItems(menu, true, _cu.rcolor(getContext(), R.color.dark__primary_text));
        _cu.setSubMenuIconsVisibility(menu, true);

        final boolean isExperimentalFeaturesEnabled = _appSettings.isExperimentalFeaturesEnabled();
        final boolean isText = !_document.isBinaryFileNoTextLoading();

        menu.findItem(R.id.action_undo).setVisible(isText && _appSettings.isEditorHistoryEnabled());
        menu.findItem(R.id.action_redo).setVisible(isText && _appSettings.isEditorHistoryEnabled());
        menu.findItem(R.id.action_send_debug_log).setVisible(MainActivity.IS_DEBUG_ENABLED && !isDisplayedAtMainActivity() && !_isPreviewVisible);

        _undoMenuItem = menu.findItem(R.id.action_undo).setVisible(isText && !_isPreviewVisible);
        _redoMenuItem = menu.findItem(R.id.action_redo).setVisible(isText && !_isPreviewVisible);
        _saveMenuItem = menu.findItem(R.id.action_save).setVisible(isText && !_isPreviewVisible);

        menu.findItem(R.id.action_edit).setVisible(isText && _isPreviewVisible);
        menu.findItem(R.id.action_preview).setVisible(isText && !_isPreviewVisible);
        menu.findItem(R.id.action_search).setVisible(isText && !_isPreviewVisible);
        menu.findItem(R.id.action_search_view).setVisible(isText && _isPreviewVisible);
        menu.findItem(R.id.submenu_format_selection).setVisible(isText && !_isPreviewVisible);
        menu.findItem(R.id.submenu_share).setVisible(isText);
        menu.findItem(R.id.submenu_tools).setVisible(isText);
        menu.findItem(R.id.submenu_per_file_settings).setVisible(isText);

        menu.findItem(R.id.action_share_pdf).setVisible(true);
        menu.findItem(R.id.action_share_image).setVisible(true);
        menu.findItem(R.id.action_load_epub).setVisible(isExperimentalFeaturesEnabled);

        setupSearchView((SearchView) menu.findItem(R.id.action_search_view).getActionView());

        updateMenuToggleStates(_document.getFormat());
        checkTextChangeState();
        updateUndoRedoIconStates();
    }

    @Override
    public boolean onReceiveKeyPress(int keyCode, KeyEvent event) {
        if (_format != null && _format.getActions().onReceiveKeyPress(keyCode, event)) {
            return true;
        }

        if (event.isCtrlPressed()) {
            if (event.isShiftPressed() && keyCode == KeyEvent.KEYCODE_Z) {
                if (_editTextUndoRedoHelper != null && _editTextUndoRedoHelper.getCanRedo()) {
                    _hlEditor.withAutoFormatDisabled(_editTextUndoRedoHelper::redo);
                    updateUndoRedoIconStates();
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_S) {
                saveDocument(true);
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_Y) {
                if (_editTextUndoRedoHelper != null && _editTextUndoRedoHelper.getCanRedo()) {
                    _hlEditor.withAutoFormatDisabled(_editTextUndoRedoHelper::redo);
                    updateUndoRedoIconStates();
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_Z) {
                if (_editTextUndoRedoHelper != null && _editTextUndoRedoHelper.getCanUndo()) {
                    _hlEditor.withAutoFormatDisabled(_editTextUndoRedoHelper::undo);
                    updateUndoRedoIconStates();
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_SLASH) {
                setViewModeVisibility(!_isPreviewVisible);
                return true;
            }
        }

        return false;
    }

    private void updateUndoRedoIconStates() {
        Drawable d;
        final boolean canUndo = _editTextUndoRedoHelper != null && _editTextUndoRedoHelper.getCanUndo();
        if (_undoMenuItem != null && _undoMenuItem.isEnabled() != canUndo && (d = _undoMenuItem.setEnabled(canUndo).getIcon()) != null) {
            d.mutate().setAlpha(canUndo ? 255 : 40);
        }

        final boolean canRedo = _editTextUndoRedoHelper != null && _editTextUndoRedoHelper.getCanRedo();
        if (_redoMenuItem != null && _redoMenuItem.isEnabled() != canRedo && (d = _redoMenuItem.setEnabled(canRedo).getIcon()) != null) {
            d.mutate().setAlpha(canRedo ? 255 : 40);
        }
    }

    public boolean loadDocument() {
        if (isSdStatusBad() || isStateBad()) {
            errorClipText();
            return false;
        }

        if (_document.hasFileChangedSinceLastLoad()) {

            final String content = _document.loadContent(getContext());
            if (content == null) {
                errorClipText();
                return false;
            }

            if (!_document.isContentSame(_hlEditor.getText())) {
                _hlEditor.withAutoFormatDisabled(() -> _hlEditor.setTextKeepState(content));
            }

            checkTextChangeState();

            if (_isPreviewVisible) {
                updateViewModeText();
            }

            return true;
        }
        return false;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        final Activity activity = getActivity();
        if (activity == null) {
            return true;
        }

        final int itemId = item.getItemId();
        switch (itemId) {
            case R.id.action_undo: {
                if (_editTextUndoRedoHelper != null && _editTextUndoRedoHelper.getCanUndo()) {
                    _hlEditor.withAutoFormatDisabled(_editTextUndoRedoHelper::undo);
                    updateUndoRedoIconStates();
                }
                return true;
            }
            case R.id.action_redo: {
                if (_editTextUndoRedoHelper != null && _editTextUndoRedoHelper.getCanRedo()) {
                    _hlEditor.withAutoFormatDisabled(_editTextUndoRedoHelper::redo);
                    updateUndoRedoIconStates();
                }
                return true;
            }
            case R.id.action_save: {
                saveDocument(true);
                return true;
            }
            case R.id.action_reload: {
                _document.resetChangeTracking();
                if (loadDocument()) {
                    Toast.makeText(activity, "✔", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            case R.id.action_preview: {
                setViewModeVisibility(true);
                return true;
            }
            case R.id.action_edit: {
                setViewModeVisibility(false);
                return true;
            }
            case R.id.action_preview_edit_toggle: {
                setViewModeVisibility(!_isPreviewVisible);
                return true;
            }
            case R.id.action_share_path: {
                _cu.shareText(getActivity(), _document.file.getAbsolutePath(), GsContextUtils.MIME_TEXT_PLAIN);
                return true;
            }
            case R.id.action_share_text: {
                if (saveDocument(false)) {
                    _cu.shareText(getActivity(), getTextString(), GsContextUtils.MIME_TEXT_PLAIN);
                }
                return true;
            }
            case R.id.action_share_file: {
                if (saveDocument(false)) {
                    _cu.shareStream(getActivity(), _document.file, GsContextUtils.MIME_TEXT_PLAIN);
                }
                return true;
            }
            case R.id.action_share_html:
            case R.id.action_share_html_source: {
                if (saveDocument(false)) {
                    TextConverterBase converter = FormatRegistry.getFormat(_document.getFormat(), activity, _document).getConverter();
                    _cu.shareText(getActivity(),
                            converter.convertMarkup(getTextString(), getActivity(), false, _lineNumbersView.isLineNumbersEnabled(), _document.file),
                            "text/" + (item.getItemId() == R.id.action_share_html ? "html" : "plain")
                    );
                }
                return true;
            }
            case R.id.action_share_calendar_event: {
                if (saveDocument(false)) {
                    if (!_cu.createCalendarAppointment(getActivity(), _document.title, getTextString(), null)) {
                        Toast.makeText(activity, R.string.no_calendar_app_is_installed, Toast.LENGTH_SHORT).show();
                    }
                }
                return true;
            }
            case R.id.action_share_screenshot:
            case R.id.action_share_image:
            case R.id.action_share_pdf: {
                _appSettings.getSetWebViewFulldrawing(true);
                if (saveDocument(false)) {
                    _nextConvertToPrintMode = true;
                    setViewModeVisibility(true);
                    Toast.makeText(activity, R.string.please_wait, Toast.LENGTH_LONG).show();
                    if (_webView != null) {
                        _webView.postDelayed(() -> {
                            if (itemId == R.id.action_share_pdf) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                                    _cu.printOrCreatePdfFromWebview(_webView, _document, getTextString().contains("beamer\n"));
                                }
                            } else {
                                Bitmap bmp = _cu.getBitmapFromWebView(_webView, itemId == R.id.action_share_image);
                                _cu.shareImage(getContext(), bmp, null);
                            }
                        }, 7000);
                    }
                }

                return true;
            }
            case R.string.action_format_wikitext:
            case R.string.action_format_keyvalue:
            case R.string.action_format_todotxt:
            case R.string.action_format_csv:
            case R.string.action_format_plaintext:
            case R.string.action_format_asciidoc:
            case R.string.action_format_orgmode:
            case R.string.action_format_markdown: {
                if (itemId != _document.getFormat()) {
                    _document.setFormat(itemId);
                    applyTextFormat(itemId);
                    _appSettings.setDocumentFormat(_document.path, _document.getFormat());
                }
                return true;
            }
            case R.id.action_search: {
                setViewModeVisibility(false);
                _format.getActions().onSearch();
                return true;
            }
            case R.id.action_send_debug_log: {
                final String text = AppSettings.getDebugLog() + "\n\n------------------------\n\n\n\n" + Document.getMaskedContent(getTextString());
                _cu.draftEmail(getActivity(), "Debug Log " + getString(R.string.app_name_real), text, "debug@localhost.lan");
                return true;
            }
            case R.id.action_load_epub: {
                MarkorFileBrowserFactory.showFileDialog(new GsFileBrowserOptions.SelectionListenerAdapter() {
                                                            @Override
                                                            public void onFsViewerSelected(String request, File file, final Integer lineNumber) {
                                                                _hlEditor.setText(GsCoolExperimentalStuff.convertEpubToText(file, getString(R.string.page)));
                                                            }

                                                            @Override
                                                            public void onFsViewerConfig(GsFileBrowserOptions.Options dopt) {
                                                                dopt.titleText = R.string.select;
                                                            }
                                                        }, getParentFragmentManager(), activity,
                        (context, file) -> file != null && file.getAbsolutePath().toLowerCase().endsWith(".epub")
                );
                return true;
            }
            case R.id.action_speed_read: {
                GsCoolExperimentalStuff.showSpeedReadDialog(activity, getTextString());
                return true;
            }
            case R.id.action_wrap_words: {
                final boolean newState = !isWrapped();
                _appSettings.setDocumentWrapState(_document.path, newState);
                setWrapState(newState);
                if (_isPreviewVisible && _webView != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    _webView.evaluateJavascript("setWrapWords('" + newState + "');", null);
                }
                updateMenuToggleStates(0);
                return true;
            }
            case R.id.action_line_numbers: {
                final boolean newState = !_lineNumbersView.isLineNumbersEnabled();
                _appSettings.setDocumentLineNumbersEnabled(_document.path, newState);
                _lineNumbersView.setLineNumbersEnabled(newState);
                if (_isPreviewVisible && _webView != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    _webView.evaluateJavascript("setLineNumbers('" + newState + "');", null);
                }
                updateMenuToggleStates(0);
                return true;
            }
            case R.id.action_enable_highlighting: {
                final boolean newState = !_hlEditor.getHighlightingEnabled();
                _hlEditor.setHighlightingEnabled(newState);
                _appSettings.setDocumentHighlightState(_document.path, newState);
                updateMenuToggleStates(0);
                return true;
            }
            case R.id.action_enable_auto_format: {
                final boolean newState = !_hlEditor.getAutoFormatEnabled();
                _hlEditor.setAutoFormatEnabled(newState);
                _appSettings.setDocumentAutoFormatEnabled(_document.path, newState);
                updateMenuToggleStates(0);
                return true;
            }
            case R.id.action_info: {
                if (saveDocument(false)) {
                    FileInfoDialog.show(_document.file, getParentFragmentManager());
                }
                return true;
            }
            case R.id.action_set_font_size: {
                final int current = _isPreviewVisible ? _appSettings.getDocumentViewFontSize(_document.path) : _appSettings.getDocumentFontSize(_document.path);
                MarkorDialogFactory.showFontSizeDialog(activity, current, (newSize) -> {
                    if (_isPreviewVisible) {
                        if (_webView != null) {
                            _webView.getSettings().setTextZoom((int) (newSize * VIEW_FONT_SCALE));
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && _lineNumbersView.isLineNumbersEnabled()) {
                                _webView.evaluateJavascript("refreshLineNumbers();", null);
                            }
                        }
                        _appSettings.setDocumentViewFontSize(_document.path, newSize);
                    } else {
                        _hlEditor.setTextSize(TypedValue.COMPLEX_UNIT_SP, (float) newSize);
                        _appSettings.setDocumentFontSize(_document.path, newSize);
                    }
                });
                return true;
            }
            case R.id.action_show_file_browser: {
                _hlEditor.postDelayed(() -> MainActivity.launch(activity, _document.file, false), 250);
                return true;
            }
            default: {
                return super.onOptionsItemSelected(item);
            }
        }
    }

    public void checkTextChangeState() {
        final boolean isTextChanged = !_document.isContentSame(_hlEditor.getText());
        Drawable d;

        if (_saveMenuItem != null && _saveMenuItem.isEnabled() != isTextChanged && (d = _saveMenuItem.setEnabled(isTextChanged).getIcon()) != null) {
            d.mutate().setAlpha(isTextChanged ? 255 : 40);
        }
    }

    @Override
    public void applyTextFormat(final int textFormatId) {
        final Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        _format = FormatRegistry.getFormat(textFormatId, activity, _document);
        _document.setFormat(_format.getFormatId());
        _hlEditor.setHighlighter(_format.getHighlighter());
        _hlEditor.setAutoFormatters(_format.getAutoFormatInputFilter(), _format.getAutoFormatTextWatcher());
        _hlEditor.setAutoFormatEnabled(_appSettings.getDocumentAutoFormatEnabled(_document.path));
        _format.getActions()
                .setDocument(_document)
                .setUiReferences(activity, _hlEditor, _webView)
                .recreateActionButtons(_textActionsBar, _isPreviewVisible ? ActionButtonBase.ActionItem.DisplayMode.VIEW : ActionButtonBase.ActionItem.DisplayMode.EDIT);
        updateMenuToggleStates(_format.getFormatId());
        showHideActionBar();
    }

    private void showHideActionBar() {
        final Activity activity = getActivity();
        if (activity != null) {
            final View bar = activity.findViewById(R.id.document__fragment__edit__text_actions_bar);
            final View parent = activity.findViewById(R.id.document__fragment__edit__text_actions_bar__scrolling_parent);
            final View viewScroll = activity.findViewById(R.id.document__fragment_view_webview);

            if (bar != null && parent != null && _verticalScrollView != null) {
                final boolean hide = _textActionsBar.getChildCount() == 0;
                parent.setVisibility(hide ? View.GONE : View.VISIBLE);
                final int marginBottom = hide ? 0 : (int) getResources().getDimension(R.dimen.textactions_bar_height);
                setMarginBottom(_verticalScrollView, marginBottom);
                if (viewScroll != null) {
                    setMarginBottom(viewScroll, marginBottom);
                }
                syncEditorMinHeightOnce(_verticalScrollView);
            }
        }
    }

    private void setupSearchView(SearchView searchView) {
        if (searchView == null) {
            return;
        }
        if (!_isPreviewVisible || _webView == null) {
            return;
        }

        searchView.setQueryHint(getString(R.string.search));
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            private Runnable searchTask;
            private String searchText = "";

            private boolean search(String text) {
                if (_webView == null) {
                    return false;
                }
                if (searchTask == null) {
                    searchTask = TextViewUtils.makeDebounced(_webView.getHandler(), 500, () -> _webView.findAllAsync(searchText));
                }

                searchText = text;
                searchTask.run();
                return true;
            }

            @Override
            public boolean onQueryTextSubmit(String query) {
                if (_webView != null) {
                    _webView.findNext(true);
                    return true;
                }
                return false;
            }

            @Override
            public boolean onQueryTextChange(String text) {
                return search(text);
            }
        });
        searchView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@NonNull View v) {
            }

            @Override
            public void onViewDetachedFromWindow(@NonNull View v) {
                if (searchView.getQuery().length() > 0) {
                    searchView.setQuery("", false);
                }
                if (!searchView.isIconified()) {
                    searchView.setIconified(true);
                }
            }
        });

        ViewGroup searchPlate = searchView.findViewById(androidx.appcompat.R.id.search_plate);
        if (searchPlate == null) {
            searchView.setSubmitButtonEnabled(true);
            return;
        }

        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        layoutParams.gravity = Gravity.CENTER;

        Context searchViewContext = searchView.getContext();
        LinearLayout linearLayout = new LinearLayout(searchViewContext);
        linearLayout.setLayoutParams(layoutParams);

        TextView resultTextView = new TextView(searchViewContext);
        LinearLayout.LayoutParams textViewLayoutParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT);
        textViewLayoutParams.setMarginEnd(30);
        resultTextView.setLayoutParams(textViewLayoutParams);
        resultTextView.setGravity(Gravity.CENTER);
        resultTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        linearLayout.addView(resultTextView);

        ImageView previousButton = new ImageView(searchViewContext);
        previousButton.setImageResource(R.drawable.ic_baseline_keyboard_arrow_up_24);
        previousButton.setLayoutParams(layoutParams);
        previousButton.setPadding(24, 24, 24, 24);
        TextViewUtils.setSelectableItemBackgroundBorderless(previousButton, searchViewContext);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            previousButton.setTooltipText(getString(R.string.previous_match));
        }
        linearLayout.addView(previousButton);

        ImageButton nextButton = new ImageButton(searchViewContext);
        nextButton.setImageResource(R.drawable.ic_baseline_keyboard_arrow_down_24);
        nextButton.setLayoutParams(layoutParams);
        nextButton.setPadding(24, 24, 24, 24);
        TextViewUtils.setSelectableItemBackgroundBorderless(nextButton, searchViewContext);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nextButton.setTooltipText(getString(R.string.next_match));
        }
        linearLayout.addView(nextButton);

        searchPlate.addView(linearLayout, 1);

        previousButton.setOnClickListener(v -> {
            if (_webView != null) {
                _webView.findNext(false);
            }
        });
        nextButton.setOnClickListener(v -> {
            if (_webView != null) {
                _webView.findNext(true);
            }
        });
        _webView.setFindListener((activeMatchOrdinal, numberOfMatches, isDoneCounting) -> {
            if (isDoneCounting) {
                String searchResult = "";
                if (numberOfMatches > 0) {
                    searchResult = (activeMatchOrdinal + 1) + "/" + numberOfMatches;
                }
                resultTextView.setText(searchResult);
            }
        });
    }

    private void setMarginBottom(final View view, final int marginBottom) {
        final ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        if (params != null) {
            params.setMargins(params.leftMargin, params.topMargin, params.rightMargin, marginBottom);
            view.setLayoutParams(params);
        }
    }

    private void syncEditorMinHeightOnce(final View parent) {
        if (parent == null) {
            return;
        }
        parent.post(() -> {
            final int parentHeight = parent.getHeight();
            if (parentHeight > 0 && parentHeight != _hlEditor.getMinHeight()) {
                _hlEditor.setMinHeight(parentHeight);
            }
        });
    }

    private void updateMenuToggleStates(final int selectedFormatActionId) {
        MenuItem mi;
        if ((mi = _fragmentMenu.findItem(R.id.action_wrap_words)) != null) {
            mi.setChecked(isWrapped());
        }
        if ((mi = _fragmentMenu.findItem(R.id.action_enable_highlighting)) != null) {
            mi.setChecked(_hlEditor.getHighlightingEnabled());
        }
        if ((mi = _fragmentMenu.findItem(R.id.action_line_numbers)) != null) {
            mi.setChecked(_lineNumbersView.isLineNumbersEnabled());
        }
        if ((mi = _fragmentMenu.findItem(R.id.action_enable_auto_format)) != null) {
            mi.setChecked(_hlEditor.getAutoFormatEnabled());
        }

        final SubMenu su;
        if (selectedFormatActionId != 0 && (mi = _fragmentMenu.findItem(R.id.submenu_format_selection)) != null && (su = mi.getSubMenu()) != null) {
            for (int i = 0; i < su.size(); i++) {
                if ((mi = su.getItem(i)).getItemId() == selectedFormatActionId) {
                    mi.setChecked(true);
                    break;
                }
            }
        }
    }

    private boolean isWrapped() {
        return _horizontalScrollView == null || _hlEditor.getParent() != _horizontalScrollView;
    }

    private ViewGroup.LayoutParams makeLinearLayoutChildParams() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private ViewGroup.LayoutParams makeScrollViewChildParams() {
        return new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void setWrapState(final boolean wrap) {
        _hlEditor.setHorizontallyScrolling(!wrap);
        final Context context = getContext();
        if (context != null && _hlEditor != null && isWrapped() != wrap) {

            _hlEditor.setAlpha(0);

            final int[] sel = TextViewUtils.getSelection(_hlEditor);
            final boolean hlEnabled = _hlEditor.setHighlightingEnabled(false);

            if (_horizontalScrollView == null) {
                _horizontalScrollView = new HorizontalScrollView(context);
                _horizontalScrollView.setFillViewport(true);
            }

            if (wrap) {
                _horizontalScrollView.removeView(_hlEditor);
                _editorHolder.removeView(_horizontalScrollView);
                _hlEditor.setLayoutParams(makeLinearLayoutChildParams());
                _editorHolder.addView(_hlEditor, 1);
            } else {
                _editorHolder.removeView(_hlEditor);
                _hlEditor.setLayoutParams(makeScrollViewChildParams());
                _horizontalScrollView.addView(_hlEditor);
                _horizontalScrollView.setLayoutParams(makeLinearLayoutChildParams());
                _editorHolder.addView(_horizontalScrollView, 1);
            }

            _hlEditor.requestLayout();
            syncEditorMinHeightOnce(_editorHolder);

            _hlEditor.setHighlightingEnabled(hlEnabled);
            _hlEditor.post(() -> {
                TextViewUtils.setSelectionAndShow(_hlEditor, sel);
                _hlEditor.post(() -> _hlEditor.animate().alpha(1).setDuration(400).start());
            });
        }
    }

    @Override
    public String getFragmentTag() {
        return FRAGMENT_TAG;
    }

    public void errorClipText() {
        final String text = getTextString();
        if (!TextUtils.isEmpty(text)) {
            Context context = getContext();
            context = context == null ? ApplicationObject.get().getApplicationContext() : context;
            new MarkorContextUtils(context).setClipboard(getContext(), text);
        }
        Toast.makeText(getContext(), getString(R.string.error_could_not_open_file) + " " + getDocument().file, Toast.LENGTH_LONG).show();
        Log.i(DocumentEditAndViewFragment.class.getName(), "Triggering error text clipping");
    }

    public boolean isSdStatusBad() {
        if (_cu.isUnderStorageAccessFolder(getContext(), _document.file, false) &&
                _cu.getStorageAccessFrameworkTreeUri(getContext()) == null) {
            _cu.showMountSdDialog(getActivity());
            return true;
        }
        return false;
    }

    public boolean isStateBad() {
        return (_document == null ||
                _hlEditor == null ||
                _appSettings == null ||
                !_cu.canWriteFile(getContext(), _document.file, false, true));
    }

    public boolean saveDocument(final boolean forceSaveEmpty) {
        final Activity activity = getActivity();
        if (activity == null || isSdStatusBad() || isStateBad()) {
            errorClipText();
            return false;
        }

        final CharSequence text = _hlEditor.getText();
        if (!_document.isContentSame(text)) {
            final int minLength = GsContextUtils.TEXTFILE_OVERWRITE_MIN_TEXT_LENGTH;
            if (!forceSaveEmpty && text != null && text.length() < minLength) {
                final String message = activity.getString(R.string.wont_save_min_length, minLength);
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
                return true;
            }
            if (_document.saveContent(getActivity(), text, _cu, forceSaveEmpty)) {
                checkTextChangeState();
                return true;
            } else {
                errorClipText();
                return false;
            }
        } else {
            return true;
        }
    }

    private boolean isDisplayedAtMainActivity() {
        return getActivity() instanceof MainActivity;
    }

    public void updateViewModeText() {
        if (_webView == null) {
            return;
        }
        try {
            _format.getConverter().convertMarkupShowInWebView(_document, getTextString(), getActivity(), _webView, _nextConvertToPrintMode, _lineNumbersView.isLineNumbersEnabled());
        } catch (OutOfMemoryError e) {
            _format.getConverter().convertMarkupShowInWebView(_document, "updateViewModeText getTextString(): OutOfMemory  " + e, getActivity(), _webView, _nextConvertToPrintMode, _lineNumbersView.isLineNumbersEnabled());
        }
    }

    public void setViewModeVisibility(final boolean show) {
        setViewModeVisibility(show, true);
    }

    @SuppressLint({"SetJavaScriptEnabled"})
    private void setupWebViewIfNeeded(final Activity activity) {
        if (_webView == null) {
            _webView = (WebView) _webViewStub.inflate();
            _webView.setWebChromeClient(new GsWebViewChromeClient(_webView, activity, activity.findViewById(R.id.document__fragment_fullscreen_overlay)));
            _webView.addJavascriptInterface(this, "Android");
            _webView.setBackgroundColor(Color.TRANSPARENT);
            WebSettings webSettings = _webView.getSettings();
            webSettings.setBuiltInZoomControls(true);
            webSettings.setDisplayZoomControls(false);
            webSettings.setTextZoom((int) (_appSettings.getDocumentViewFontSize(_document.path) * VIEW_FONT_SCALE));
            webSettings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
            webSettings.setDatabaseEnabled(true);
            webSettings.setGeolocationEnabled(false);
            webSettings.setJavaScriptEnabled(true);
            webSettings.setDomStorageEnabled(true);
            webSettings.setAllowContentAccess(true);
            webSettings.setAllowFileAccess(true);
            webSettings.setAllowFileAccessFromFileURLs(false);
            webSettings.setAllowUniversalAccessFromFileURLs(false);
            webSettings.setMediaPlaybackRequiresUserGesture(false);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && BuildConfig.IS_TEST_BUILD && BuildConfig.DEBUG) {
                WebView.setWebContentsDebuggingEnabled(true);
            }

            _webViewClient = new MarkorWebViewClient(_webView, activity);
            _webView.setWebViewClient(_webViewClient);

            _webView.setOnLongClickListener(v -> {
                WebView.HitTestResult hitResult = _webView.getHitTestResult();
                if (hitResult.getType() == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
                    String url = hitResult.getExtra();
                    if (url != null) {
                        _cu.setClipboard(getContext(), url);
                        Toast.makeText(activity, R.string.link_copied, Toast.LENGTH_SHORT).show();
                        return true;
                    }
                }
                return false;
            });
        }
    }

    @SuppressLint({"AddJavascriptInterface", "SetJavaScriptEnabled"})
    public void setViewModeVisibility(boolean show, final boolean animate) {
        final Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        show |= _document.isBinaryFileNoTextLoading();
        _format.getActions().recreateActionButtons(_textActionsBar, show ? ActionButtonBase.ActionItem.DisplayMode.VIEW : ActionButtonBase.ActionItem.DisplayMode.EDIT);
        showHideActionBar();
        if (show) {
            setupWebViewIfNeeded(activity);
            updateViewModeText();
            _cu.showSoftKeyboard(activity, false, _hlEditor);
            _hlEditor.clearFocus();
            _hlEditor.postDelayed(() -> _cu.showSoftKeyboard(activity, false, _hlEditor), 300);
            GsContextUtils.fadeInOut(_webView, _verticalScrollView, animate);
        } else {
            if (_webView != null) {
                _webViewClient.setRestoreScrollY(_webView.getScrollY());
            }
            GsContextUtils.fadeInOut(_verticalScrollView, _webView, animate);
        }

        _nextConvertToPrintMode = false;
        _isPreviewVisible = show;

        ((AppCompatActivity) activity).supportInvalidateOptionsMenu();
    }

    @SuppressWarnings("unused")
    @JavascriptInterface
    public void webViewJavascriptCallback(final String[] jsArgs) {
        final String[] args = (jsArgs == null || jsArgs.length == 0 || jsArgs[0] == null) ? new String[0] : jsArgs;
        final String type = args.length == 0 || TextUtils.isEmpty(args[0]) ? "" : args[0];
        if (type.equalsIgnoreCase("toast") && args.length == 2) {
            Toast.makeText(getActivity(), args[1], Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onToolbarClicked(View v) {
        if (_format != null) {
            _format.getActions().runTitleClick();
        }
    }

    @Override
    protected boolean onToolbarLongClicked(View v) {
        if (isVisible() && isResumed()) {
            _format.getActions().runJumpBottomTopAction(_isPreviewVisible ? ActionButtonBase.ActionItem.DisplayMode.VIEW : ActionButtonBase.ActionItem.DisplayMode.EDIT);
            return true;
        }
        return false;
    }

    @Override
    public void onDestroy() {
        if (_webView != null) {
            try {
                _webView.loadUrl("about:blank");
                _webView.destroy();
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    public Document getDocument() {
        return _document;
    }

    public HighlightingEditor getEditor() {
        return _hlEditor;
    }

    public String getTextString() {
        final CharSequence text = _hlEditor != null ? _hlEditor.getText() : null;
        return text != null ? text.toString() : "";
    }

    //#################################
    //## Item 5 & 6: Side Panels Setup
    //#################################

    private void setupSidePanel(final View rootView) {
        _sidePanelDrawerLayout = rootView.findViewById(R.id.document__fragment__edit__drawer_layout);
        if (_sidePanelDrawerLayout == null) {
            return;
        }

        final View panel = rootView.findViewById(R.id.document__sidepanel_text_viewer__root);
        _sidePanelTitle = panel.findViewById(R.id.document__sidepanel_text_viewer__title);
        _sidePanelText = panel.findViewById(R.id.document__sidepanel_text_viewer__text);
        _sidePanelScroll = panel.findViewById(R.id.document__sidepanel_text_viewer__scroll);
        _sidePanelSearchBar = panel.findViewById(R.id.document__sidepanel_text_viewer__search_bar);
        _sidePanelSearchInput = panel.findViewById(R.id.document__sidepanel_text_viewer__search_input);

        final ImageButton closeBtn = panel.findViewById(R.id.document__sidepanel_text_viewer__close);
        final ImageButton changeBtn = panel.findViewById(R.id.document__sidepanel_text_viewer__change);
        final ImageButton searchToggleBtn = panel.findViewById(R.id.document__sidepanel_text_viewer__search_toggle);

        closeBtn.setOnClickListener(v -> _sidePanelDrawerLayout.closeDrawer(GravityCompat.END));
        changeBtn.setOnClickListener(v -> showSidePanelFilePicker());
        searchToggleBtn.setOnClickListener(v -> {
            final boolean show = _sidePanelSearchBar.getVisibility() != View.VISIBLE;
            _sidePanelSearchBar.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                _sidePanelSearchInput.requestFocus();
            }
        });
        _sidePanelSearchInput.setOnEditorActionListener((tv, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSidePanelSearch(_sidePanelSearchInput.getText().toString());
                return true;
            }
            return false;
        });

        _sidePanelScroll.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener) (v, scrollX, scrollY, oldScrollX, oldScrollY) -> _sidePanelPendingScrollY = scrollY);

        _sidePanelDrawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(@NonNull final View drawerView) {
                if (drawerView == panel) {
                    loadSidePanelForCurrentFolder();
                } else if (drawerView == _sidePanelFileListRoot) {
                    loadSidePanelFileList();
                }
            }

            @Override
            public void onDrawerClosed(@NonNull final View drawerView) {
                if (drawerView == panel) {
                    persistSidePanelScrollY();
                }
            }
        });

        setupSidePanelFileList(rootView);
    }

    private void setupSidePanelFileList(final View rootView) {
        _sidePanelFileListRoot = rootView.findViewById(R.id.document__sidepanel_file_list__root);
        if (_sidePanelFileListRoot == null) {
            return;
        }
        _sidePanelFileListTitle = _sidePanelFileListRoot.findViewById(R.id.document__sidepanel_file_list__title);
        _sidePanelFileListRecycler = _sidePanelFileListRoot.findViewById(R.id.document__sidepanel_file_list__recycler);
        final ImageButton closeBtn = _sidePanelFileListRoot.findViewById(R.id.document__sidepanel_file_list__close);
        closeBtn.setOnClickListener(v -> _sidePanelDrawerLayout.closeDrawer(GravityCompat.START));

        _sidePanelDrawerLayout.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                final int fullWidth = _sidePanelDrawerLayout.getWidth();
                if (fullWidth <= 0) {
                    return;
                }
                final ViewGroup.LayoutParams lp = _sidePanelFileListRoot.getLayoutParams();
                final int halfWidth = fullWidth / 2;
                if (lp.width != halfWidth) {
                    lp.width = halfWidth;
                    _sidePanelFileListRoot.setLayoutParams(lp);
                }
                _sidePanelDrawerLayout.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            }
        });
    }

    private void loadSidePanelFileList() {
        final File folder = getSidePanelFolder();
        if (folder == null || _sidePanelFileListRecycler == null) {
            return;
        }
        _sidePanelFileListTitle.setText(folder.getName());

        final File[] all = folder.listFiles();
        final List<File> entries = new ArrayList<>();
        if (all != null) {
            Arrays.sort(all);
            entries.addAll(Arrays.asList(all));
        }

        _sidePanelFileListRecycler.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
                final View row = getLayoutInflater().inflate(R.layout.document__sidepanel_file_list_item, parent, false);
                return new RecyclerView.ViewHolder(row) {
                };
            }

            @Override
            public void onBindViewHolder(@NonNull final RecyclerView.ViewHolder holder, final int position) {
                final File f = entries.get(position);
                final TextView tv = (TextView) holder.itemView;
                tv.setText(f.isDirectory() ? (f.getName() + "/") : f.getName());
                tv.setTextColor(f.equals(_document.file)
                        ? ContextCompat.getColor(tv.getContext(), R.color.accent)
                        : ContextCompat.getColor(tv.getContext(), R.color.primary_text));
                tv.setOnClickListener(v -> {
                    if (f.isFile() && FormatRegistry.isFileSupported(f, true) && !f.equals(_document.file)) {
                        _sidePanelDrawerLayout.closeDrawer(GravityCompat.START);
                        DocumentActivity.launch(getActivity(), f, null, null);
                    }
                });
            }

            @Override
            public int getItemCount() {
                return entries.size();
            }
        });
    }

    private File getSidePanelFolder() {
        return (_document != null && _document.file != null) ? _document.file.getParentFile() : null;
    }

    private void loadSidePanelForCurrentFolder() {
        final File folder = getSidePanelFolder();
        final File wanted = folder != null ? _appSettings.getSidePanelTextForFolder(folder) : null;

        if (wanted == null) {
            _sidePanelCurrentFile = null;
            _sidePanelTitle.setText(R.string.select_text);
            _sidePanelText.setText(R.string.side_panel_no_other_text_files);
            return;
        }

        if (wanted.equals(_sidePanelCurrentFile)) {
            return;
        }

        loadFileIntoSidePanel(wanted);
    }

    private void loadFileIntoSidePanel(final File file) {
        persistSidePanelScrollY();

        _sidePanelCurrentFile = file;
        _sidePanelTitle.setText(file.getName());
        _sidePanelText.setText("");
        _sidePanelPendingScrollY = -1;

        SIDEPANEL_EXECUTOR.execute(() -> {
            final String content = GsFileUtils.readTextFile(file);
            final Activity activity = getActivity();
            if (activity == null) {
                return;
            }
            activity.runOnUiThread(() -> {
                if (!file.equals(_sidePanelCurrentFile) || _sidePanelText == null) {
                    return;
                }
                _sidePanelText.setText(content);
                final int savedScrollY = _appSettings.getSidePanelScrollY(file);
                _sidePanelScroll.post(() -> {
                    if (file.equals(_sidePanelCurrentFile) && _sidePanelScroll != null) {
                        _sidePanelScroll.scrollTo(0, savedScrollY);
                    }
                });
            });
        });
    }

    private void persistSidePanelScrollY() {
        if (_sidePanelCurrentFile != null && _sidePanelPendingScrollY >= 0) {
            _appSettings.setSidePanelScrollY(_sidePanelCurrentFile, _sidePanelPendingScrollY);
        }
    }

    private void showSidePanelFilePicker() {
        final Activity activity = getActivity();
        final File folder = getSidePanelFolder();
        if (activity == null || folder == null) {
            return;
        }

        final File[] all = folder.listFiles();
        final List<File> candidates = new ArrayList<>();
        if (all != null) {
            Arrays.sort(all);
            for (final File f : all) {
                if (f.isFile() && !f.equals(_document.file) && FormatRegistry.isFileSupported(f, true)) {
                    candidates.add(f);
                }
            }
        }

        if (candidates.isEmpty()) {
            Toast.makeText(activity, R.string.side_panel_no_other_text_files, Toast.LENGTH_SHORT).show();
            return;
        }

        final String[] names = new String[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            names[i] = candidates.get(i).getName();
        }

        new AlertDialog.Builder(activity)
                .setTitle(R.string.select_text)
                .setItems(names, (dialog, which) -> {
                    final File chosen = candidates.get(which);
                    _appSettings.setSidePanelTextForFolder(folder, chosen);
                    loadFileIntoSidePanel(chosen);
                })
                .show();
    }

    private void performSidePanelSearch(final String query) {
        if (TextUtils.isEmpty(query) || _sidePanelText == null || _sidePanelText.getLayout() == null) {
            return;
        }

        final String haystack = _sidePanelText.getText().toString();
        final String needle = query.toLowerCase();
        final android.text.Layout layout = _sidePanelText.getLayout();

        final int topLine = layout.getLineForVertical(_sidePanelScroll.getScrollY());
        final int fromIndex = layout.getLineStart(Math.min(topLine + 1, layout.getLineCount() - 1));

        int matchIndex = haystack.toLowerCase().indexOf(needle, fromIndex);
        if (matchIndex < 0) {
            matchIndex = haystack.toLowerCase().indexOf(needle);
        }

        if (matchIndex < 0) {
            Toast.makeText(getActivity(), R.string.side_panel_search_not_found, Toast.LENGTH_SHORT).show();
            return;
        }

        final int line = layout.getLineForOffset(matchIndex);
        final int y = layout.getLineTop(line) + _sidePanelText.getPaddingTop();
        _sidePanelScroll.scrollTo(0, y);
    }
}
