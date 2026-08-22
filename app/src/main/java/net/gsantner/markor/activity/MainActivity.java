/*#######################################################
 *
 *
 *   Maintained 2017-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import net.gsantner.markor.BuildConfig;
import net.gsantner.markor.R;
import net.gsantner.markor.format.FormatRegistry;
import net.gsantner.markor.frontend.NewFileDialog;
import net.gsantner.markor.frontend.filebrowser.MarkorFileBrowserFactory;
import net.gsantner.markor.model.Document;
import net.gsantner.markor.util.MarkorContextUtils;
import net.gsantner.markor.widget.TodoWidgetProvider;
import net.gsantner.opoc.format.GsSimpleMarkdownParser;
import net.gsantner.opoc.frontend.base.GsFragmentBase;
import net.gsantner.opoc.frontend.filebrowser.GsFileBrowserFragment;
import net.gsantner.opoc.frontend.filebrowser.GsFileBrowserListAdapter;
import net.gsantner.opoc.frontend.filebrowser.GsFileBrowserOptions;
import net.gsantner.opoc.util.GsContextUtils;
import net.gsantner.opoc.util.GsFileUtils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import other.writeily.widget.WrMarkorWidgetProvider;

public class MainActivity extends MarkorBaseActivity implements GsFileBrowserFragment.FilesystemFragmentOptionsListener {

    public static boolean IS_DEBUG_ENABLED = false;

    private BottomNavigationView _bottomNav;
    private ViewPager2 _viewPager;
    private View _notebookFolderUnavailableView;
    private SectionsPagerAdapter _sectionsAdapter;
    private GsFileBrowserFragment _notebook, _book;
    private DocumentEditAndViewFragment _quicknote, _todo;
    private MoreFragment _more;
    private FloatingActionButton _fab;

    private MarkorContextUtils _cu;
    private File _quickSwitchPrevFolder = null;
    private File _startFolder = null, _showFile = null;

    @SuppressLint("SdCardPath")
    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        IS_DEBUG_ENABLED |= BuildConfig.IS_TEST_BUILD;

        try {
            // Only auto-create the (still-default, never explicitly picked) notebook folder on
            // first run. Once the user has picked a folder via the directory picker, silently
            // recreating it here if it's missing would mask exactly the "folder no longer
            // exists" situation the unavailable-state UI (see updateNotebookFolderAvailability)
            // is meant to catch and ask the user about.
            if (_appSettings.getNotebookDirectoryTreeUri() == null) {
                //noinspection ResultOfMethodCallIgnored
                _appSettings.getNotebookDirectory().mkdirs();
            }
        } catch (Exception ignored) {
        }

        _cu = new MarkorContextUtils(this);
        setContentView(R.layout.main__activity);
        _bottomNav = findViewById(R.id.bottom_navigation_bar);
        if (!_appSettings.isBookTabEnabled()) {
            MenuItem bookItem = _bottomNav.getMenu().findItem(R.id.nav_book);
            if (bookItem != null) bookItem.setVisible(false);
        }
        _viewPager = findViewById(R.id.main__view_pager_container);
        _notebookFolderUnavailableView = findViewById(R.id.main__notebook_folder_unavailable);
        _notebookFolderUnavailableView.findViewById(R.id.main__notebook_folder_unavailable_button)
                .setOnClickListener(v -> pickNewNotebookFolder());
        _fab = findViewById(R.id.fab_add_new_item);
        _fab.setOnClickListener(this::onClickFab);
        _fab.setOnLongClickListener(this::onLongClickFab);
        _viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                onViewPagerPageSelected(position);
            }
        });

        setSupportActionBar(findViewById(R.id.toolbar));
        applyUiColors();
        optShowRate();

        // Setup viewpager
        _sectionsAdapter = new SectionsPagerAdapter(getSupportFragmentManager());
        _viewPager.setAdapter(_sectionsAdapter);
        // Keep created fragments alive, but only realize them once the user visits.
        _viewPager.setOffscreenPageLimit(_bottomNav.getMenu().size());
        // ViewPager2 fires onPageSelected once layout settles, but that can happen a frame or two
        // late; check explicitly now too so the very first frame is already correct if the app
        // launches straight onto the Notebook tab with an unavailable folder.
        updateNotebookFolderAvailability(getCurrentPos());
        _bottomNav.setOnItemSelectedListener((item) -> {
            final int itemId = item.getItemId();
            if (itemId == R.id.nav_quicknote) {
                showLargeFileOpenToastIfNeeded(_appSettings.getQuickNoteFile());
            } else if (itemId == R.id.nav_todo) {
                showLargeFileOpenToastIfNeeded(_appSettings.getTodoFile());
            }
            final int pos = tabIdToPos(item.getItemId());
            _sectionsAdapter.ensureRealized(pos);
            _viewPager.setCurrentItem(pos);
            return true;
        });

        reduceViewpagerSwipeSensitivity();

        // noinspection PointlessBooleanExpression - Send Test intent
        if (BuildConfig.IS_TEST_BUILD && false) {
            DocumentActivity.launch(this, new File("/sdcard/Documents/mordor/aa-beamer.md"), true, null);
        }

        _cu.applySpecialLaunchersVisibility(this, _appSettings.isSpecialFileLaunchersEnabled());

        // Determine start folder
        final File fallback = _appSettings.getFolderToLoadByMenuId(_appSettings.getAppStartupFolderMenuId());
        final Intent intent = getIntent();
        _startFolder = MarkorContextUtils.getValidIntentFile(intent, fallback);
        if (!GsFileUtils.isDirectory(_startFolder)) {
            _showFile = _startFolder;
            _startFolder = _startFolder.getParentFile();
        }
        if (!GsFileUtils.isDirectory(_startFolder)) {
            _startFolder = _appSettings.getNotebookDirectory();
        }
    }

    private void applyUiColors() {
        final int primary = _appSettings.getUiPrimaryColor() != 0
                ? _appSettings.getUiPrimaryColor() : ContextCompat.getColor(this, R.color.primary);
        final int accent = _appSettings.getUiAccentColor() != 0
                ? _appSettings.getUiAccentColor() : ContextCompat.getColor(this, R.color.accent);
        final int background = _appSettings.getUiBackgroundColor() != 0
                ? _appSettings.getUiBackgroundColor() : ContextCompat.getColor(this, R.color.background);
        final int primaryText = _appSettings.getUiPrimaryTextColor() != 0
                ? _appSettings.getUiPrimaryTextColor() : ContextCompat.getColor(this, R.color.primary_text);
        final int secondaryText = _appSettings.getUiSecondaryTextColor() != 0
                ? _appSettings.getUiSecondaryTextColor() : ContextCompat.getColor(this, R.color.secondary_text);

        final View root = findViewById(R.id.main_content);
        if (root != null) root.setBackgroundColor(background);
        final androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setBackgroundColor(primary);
            toolbar.setTitleTextColor(primaryText);
            toolbar.setSubtitleTextColor(secondaryText);
        }
        if (_bottomNav != null) {
            _bottomNav.setBackgroundColor(primary);
            final int[][] states = new int[][]{
                    new int[]{android.R.attr.state_checked},
                    new int[]{}
            };
            final int[] colors = new int[]{accent, primaryText};
            final ColorStateList tint = new ColorStateList(states, colors);
            _bottomNav.setItemIconTintList(tint);
            _bottomNav.setItemTextColor(tint);
        }
        if (_fab != null) {
            _fab.setBackgroundTintList(ColorStateList.valueOf(accent));
        }
    }

    @Override
    public void onActivityFirstTimeVisible() {
        super.onActivityFirstTimeVisible();
        // Switch to tab if specific folder _not_ requested, and not recreating from saved instance
        final int startTab = _appSettings.getAppStartupTab();
        if (MarkorContextUtils.getValidIntentFile(getIntent(), null) == null) {
            _viewPager.postDelayed(() -> { int p = tabIdToPos(startTab); _sectionsAdapter.ensureRealized(p); _viewPager.setCurrentItem(p); }, 100);
        }
    }

    @Override
    public Integer getNewNavigationBarColor() {
        final int configured = _appSettings.getUiPrimaryColor();
        return configured != 0 ? configured : ContextCompat.getColor(this, R.color.primary);
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);

        // Save references to fragments
        try {
            final FragmentManager manager = getSupportFragmentManager();
            // Put and get notebook first. Most important for correct operation.
            manager.putFragment(outState, Integer.toString(R.id.nav_book), _book);
            manager.putFragment(outState, Integer.toString(R.id.nav_notebook), _notebook);
            manager.putFragment(outState, Integer.toString(R.id.nav_quicknote), _quicknote);
            manager.putFragment(outState, Integer.toString(R.id.nav_todo), _todo);
            manager.putFragment(outState, Integer.toString(R.id.nav_more), _more);
        } catch (NullPointerException | IllegalStateException ignored) {
            Log.d(MainActivity.class.getName(), "Child fragments null in onSaveInstanceState()");
        }
    }

    @Override
    public void onRestoreInstanceState(final Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);

        if (savedInstanceState == null) {
            return;
        }

        // Get back references to fragments
        try {
            final FragmentManager manager = getSupportFragmentManager();
            _book = (GsFileBrowserFragment) manager.getFragment(savedInstanceState, Integer.toString(R.id.nav_book));
            _notebook = (GsFileBrowserFragment) manager.getFragment(savedInstanceState, Integer.toString(R.id.nav_notebook));
            _quicknote = (DocumentEditAndViewFragment) manager.getFragment(savedInstanceState, Integer.toString(R.id.nav_quicknote));
            _todo = (DocumentEditAndViewFragment) manager.getFragment(savedInstanceState, Integer.toString(R.id.nav_todo));
            _more = (MoreFragment) manager.getFragment(savedInstanceState, Integer.toString(R.id.nav_more));

            if (_sectionsAdapter != null) {
                _sectionsAdapter.restoreFragment(tabIdToPos(R.id.nav_book));
                _sectionsAdapter.restoreFragment(tabIdToPos(R.id.nav_notebook));
                _sectionsAdapter.restoreFragment(tabIdToPos(R.id.nav_quicknote));
                _sectionsAdapter.restoreFragment(tabIdToPos(R.id.nav_todo));
                _sectionsAdapter.restoreFragment(tabIdToPos(R.id.nav_more));
            }

            final NewFileDialog nf = (NewFileDialog) manager.findFragmentByTag(NewFileDialog.FRAGMENT_TAG);
            if (nf != null) {
                nf.setCallback(this::newItemCallback);
            }

        } catch (NullPointerException | IllegalStateException ignored) {
            Log.d(MainActivity.class.getName(), "Child fragment not found in onRestoreInstanceState()");
        }
    }

    // Reduces swipe sensitivity
    // Inspired by https://stackoverflow.com/a/72067439
    private void reduceViewpagerSwipeSensitivity() {
        final int SLOP_MULTIPLIER = 4;
        try {
            final Field ff = ViewPager2.class.getDeclaredField("mRecyclerView");
            ff.setAccessible(true);
            final RecyclerView recyclerView = (RecyclerView) ff.get(_viewPager);
            // Set a constant so we don't continuously reduce this value with every call
            recyclerView.setScrollingTouchSlop(RecyclerView.TOUCH_SLOP_PAGING);
            final Field touchSlopField = RecyclerView.class.getDeclaredField("mTouchSlop");
            touchSlopField.setAccessible(true);
            final int touchSlop = (int) touchSlopField.get(recyclerView);
            touchSlopField.set(recyclerView, touchSlop * SLOP_MULTIPLIER);
        } catch (Exception e) {
            Log.d(MainActivity.class.getName(), e.getMessage());
        }
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        final File file = MarkorContextUtils.getValidIntentFile(intent, null);
        if (_notebook != null && file != null) {
            hideKeyboard();
            _viewPager.setCurrentItem(tabIdToPos(R.id.nav_notebook), false);
            if (GsFileUtils.isDirectory(file)) {
                _notebook.getAdapter().setCurrentFolder(file);
            } else {
                _notebook.getAdapter().showFile(file);
            }
            _notebook.setReloadRequiredOnResume(false);
        }
    }

    public static void launch(final Activity activity, final File file, final boolean finishfromActivity) {
        if (activity != null && file != null) {
            final Intent intent = new Intent(activity, MainActivity.class);
            intent.putExtra(Document.EXTRA_FILE, file);
            // intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            GsContextUtils.instance.animateToActivity(activity, intent, finishfromActivity, null);
        }
    }

    private void optShowRate() {
        try {
            new com.pixplicity.generate.Rate.Builder(this)
                    .setTriggerCount(4)
                    .setMinimumInstallTime((int) TimeUnit.MINUTES.toMillis(30))
                    .setFeedbackAction(() -> _cu.showGooglePlayEntryForThisApp(MainActivity.this))
                    .build().count().showRequest();
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        super.onOptionsItemSelected(item);
        if (item.getItemId() == R.id.action_settings) {
            _cu.animateToActivity(this, SettingsActivity.class, false, null);
            return true;
        }
        return false;
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.main__menu, menu);
        menu.findItem(R.id.action_settings).setVisible(_appSettings.isShowSettingsOptionInMainToolbar());

        final int menuTextColor = _appSettings.getUiPrimaryTextColor() != 0
                ? _appSettings.getUiPrimaryTextColor()
                : _cu.rcolor(this, R.color.dark__primary_text);
        _cu.tintMenuItems(menu, true, menuTextColor);
        _cu.setSubMenuIconsVisibility(menu, true);
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!IntroActivity.isFirstStart(this)) {
            StoragePermissionActivity.requestPermissions(this);
        }

        if (_appSettings.isRecreateMainRequired()) {
            // recreate(); // does not remake fragments
            final Intent intent = getIntent();
            overridePendingTransition(0, 0);
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            finish();
            overridePendingTransition(0, 0);
            startActivity(intent);
        }

        _cu.setKeepScreenOn(this, _appSettings.isKeepScreenOn());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && _appSettings.isMultiWindowEnabled()) {
            setTaskDescription(new ActivityManager.TaskDescription(getString(R.string.app_name)));
        }

        // Introduction dialog and show changelog etc.
        final boolean firstStart = IntroActivity.optStart(this);
        try {
            if (!firstStart && _appSettings.isAppCurrentVersionFirstStart(true)) {
                GsSimpleMarkdownParser smp = GsSimpleMarkdownParser.get().setDefaultSmpFilter(GsSimpleMarkdownParser.FILTER_ANDROID_TEXTVIEW);
                String html = "";
                html += smp.parse(getString(R.string.copyright_license_text_official).replace("\n", "  \n"), "").getHtml();
                html += "<br/><br/><br/><big><big>" + getString(R.string.changelog) + "</big></big><br/>" + smp.parse(getResources().openRawResource(R.raw.changelog), "", GsSimpleMarkdownParser.FILTER_ANDROID_TEXTVIEW);
                html += "<br/><br/><br/><big><big>" + getString(R.string.licenses) + "</big></big><br/>" + smp.parse(getResources().openRawResource(R.raw.licenses_3rd_party), "").getHtml();
                if (GsContextUtils.instance.isDarkModeEnabled(this)) {
                    html = html.replace("font color='#000000'", "font color='#D3D3D3'");
                }
                _cu.showDialogWithHtmlTextView(this, 0, html);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onPostResume() {
        super.onPostResume();
    }

    // Cycle between recent, favourite, and current
    public boolean onLongClickFab(View view) {
        if (_notebook != null) {
            final File current = _notebook.getCurrentFolder();
            final File dest;
            if (GsFileBrowserListAdapter.VIRTUAL_STORAGE_RECENTS.equals(current)) {
                dest = GsFileBrowserListAdapter.VIRTUAL_STORAGE_FAVOURITE;
            } else if (GsFileBrowserListAdapter.VIRTUAL_STORAGE_FAVOURITE.equals(current)) {
                if (_quickSwitchPrevFolder != null) {
                    dest = _quickSwitchPrevFolder;
                } else {
                    dest = GsFileBrowserListAdapter.VIRTUAL_STORAGE_RECENTS;
                }
            } else {
                _quickSwitchPrevFolder = current;
                dest = GsFileBrowserListAdapter.VIRTUAL_STORAGE_FAVOURITE;
            }
            _notebook.getAdapter().setCurrentFolder(dest);
        }
        return true;
    }

    public void onClickFab(final View view) {
        if (_notebook == null || _notebook.getAdapter() == null) {
            return;
        }

        if (!_notebook.getAdapter().isCurrentFolderWriteable()) {
            _notebook.getAdapter().setCurrentFolder(_appSettings.getNotebookDirectory());
            return;
        }

        if (view.getId() == R.id.fab_add_new_item) {
            if (_cu.isUnderStorageAccessFolder(this, _notebook.getCurrentFolder(), true) && _cu.getStorageAccessFrameworkTreeUri(this) == null) {
                _cu.showMountSdDialog(this);
                return;
            }

            NewFileDialog.newInstance(_notebook.getCurrentFolder(), true, this::newItemCallback)
                    .show(getSupportFragmentManager(), NewFileDialog.FRAGMENT_TAG);
        }
    }

    private void newItemCallback(final File file) {
        if (file.isFile()) {
            DocumentActivity.launch(MainActivity.this, file, false, null);
        }
        if (_notebook != null && _notebook.getAdapter() != null) {
            _notebook.getAdapter().showFile(file);
        }
    }

    private void showLargeFileOpenToastIfNeeded(final File file) {
        final long LARGE_FILE_TOAST_THRESHOLD_BYTES = 128L * 1024L;

        // Check if file is large and if true show a toast notification for user to wait
        if (file != null && file.isFile() && !FormatRegistry.CONVERTER_EMBEDBINARY.isFileOutOfThisFormat(file)) {
            final long fileBytes = file.length();
            if (fileBytes > LARGE_FILE_TOAST_THRESHOLD_BYTES) {
                final String readableSize = GsFileUtils.getReadableFileSize(fileBytes, true);
                Toast.makeText(this, getString(R.string.loading_large_file_may_take_a_moment_witharg, readableSize), Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onBackPressed() {
        // Check if fragment handled back press
        final GsFragmentBase<?, ?> frag = getPosFragment(getCurrentPos());
        if (frag == null || !frag.onBackPressed()) {
            super.onBackPressed();
        }
    }

    public String getFileBrowserTitle() {
        final File file = _notebook != null ? _notebook.getCurrentFolder() : null;
        if (file != null && !_appSettings.getNotebookDirectory().equals(file)) {
            return "> " + file.getName();
        } else {
            return getString(R.string.app_name);
        }
    }

    public int tabIdToPos(final int id) {
        if (id == R.id.nav_book) return 0;
        if (id == R.id.nav_notebook) return 1;
        if (id == R.id.nav_todo) return 2;
        if (id == R.id.nav_quicknote) return 3;
        if (id == R.id.nav_more) return 4;
        return 1;
    }

    public int tabIdFromPos(final int pos) {
        return _bottomNav.getMenu().getItem(pos).getItemId();
    }

    public int getCurrentPos() {
        return _viewPager.getCurrentItem();
    }

    public String getPosTitle(final int pos) {
        if (pos == 0) return getString(R.string.book);
        if (pos == 1) return getFileBrowserTitle();
        if (pos == 2) return getString(R.string.todo);
        if (pos == 3) return getString(R.string.quicknote);
        if (pos == 4) return getString(R.string.more);
        return "";
    }

    public GsFragmentBase<?, ?> getPosFragment(final int pos) {
        if (pos == 0) return _book;
        if (pos == 1) return _notebook;
        if (pos == 2) return _todo;
        if (pos == 3) return _quicknote;
        if (pos == 4) return _more;
        return null;
    }

    /**
     * Restores the default toolbar. Used when changing the tab or moving to another activity
     * while {@link GsFileBrowserFragment} action mode is active (e.g. when renaming a file)
     */
    private void restoreDefaultToolbar() {
        GsFileBrowserFragment wrFragment = getNotebook();
        if (wrFragment != null) wrFragment.clearSelection();
        if (_book != null) _book.clearSelection();
    }

    public void hideKeyboard() {
        if (_quicknote != null) {
            _cu.showSoftKeyboard(this, false, _quicknote.getEditor());
        }
        if (_todo != null) {
            _cu.showSoftKeyboard(this, false, _todo.getEditor());
        }
    }

    public void onViewPagerPageSelected(final int pos) {
        _bottomNav.getMenu().getItem(pos).setChecked(true);
        if (_sectionsAdapter != null) {
            _sectionsAdapter.ensureRealized(pos);
        }

        if (pos == tabIdToPos(R.id.nav_notebook)) {
            _fab.show();
            hideKeyboard();
        } else {
            _fab.hide();
        }

        updateNotebookFolderAvailability(pos);
        setTitle(getPosTitle(pos));
    }

    /**
     * Shows a "pick a new folder" state over the Notebook tab - instead of the Notebook fragment
     * itself (which would otherwise just silently show an empty list / a scan error, or in the
     * worst case throw trying to read a folder it no longer has access to) - when the configured
     * Notebook root folder no longer exists or its permission grant was revoked. Any other tab
     * always shows its normal content regardless of the Notebook folder's state, since this
     * overlay sits on top of the whole ViewPager area.
     */
    private void updateNotebookFolderAvailability(final int currentPos) {
        if (_notebookFolderUnavailableView == null || _viewPager == null) {
            return;
        }
        final boolean showUnavailable = currentPos == tabIdToPos(R.id.nav_notebook)
                && !_appSettings.isNotebookDirectoryAccessible(this);
        _notebookFolderUnavailableView.setVisibility(showUnavailable ? View.VISIBLE : View.GONE);
        if (showUnavailable) {
            _fab.hide();
        } else if (currentPos == tabIdToPos(R.id.nav_notebook)) {
            _fab.show();
        }
    }

    /**
     * Opens the same Storage Access Framework directory picker used in Settings, for the "Select
     * Notebook folder" button shown when the configured folder is unavailable. On success this
     * restarts the activity (the same recreate-on-resume mechanism every other Notebook-folder
     * change already goes through - see AppSettings#setRecreateMainRequired) rather than trying
     * to hot-swap the root folder on an already-live fragment/adapter.
     */
    private void pickNewNotebookFolder() {
        _cu.requestDirectory(this, treeUri -> {
            final File file = _cu.resolveTreeUriToFile(this, treeUri);
            if (file == null) {
                Toast.makeText(this, R.string.could_not_access_selected_folder, Toast.LENGTH_LONG).show();
                return;
            }
            _appSettings.setNotebookDirectory(file, treeUri);
            _appSettings.setRecreateMainRequired(true);
        });
    }

    private GsFileBrowserOptions.Options _filesystemDialogOptions = null;

    @Override
    public GsFileBrowserOptions.Options getFilesystemFragmentOptions(GsFileBrowserOptions.Options existingOptions) {
        final boolean book = existingOptions != null && existingOptions.requestBookOptions;
        final GsFileBrowserOptions.Options opts = MarkorFileBrowserFactory.prepareFsViewerOpts(this, false, new GsFileBrowserOptions.SelectionListenerAdapter() {
            @Override
            public void onFsViewerConfig(GsFileBrowserOptions.Options dopt) {
                dopt.descModtimeInsteadOfParent = true;
                dopt.rootFolder = _appSettings.getNotebookDirectory();
                dopt.confineToRootFolder = true;
                dopt.startFolder = dopt.rootFolder;
                dopt.doSelectMultiple = dopt.doSelectFolder = dopt.doSelectFile = true;
                dopt.hideGenericFolderIconInList = true;
                dopt.mountedStorageFolder = _cu.getStorageAccessFolder(MainActivity.this);
                if (book) {
                    dopt.bookMode = true;
                    dopt.onlyShowDirectories = true;
                    dopt.hideIconsInList = true;
                    dopt.viewMode = _appSettings.getBookViewMode();
                } else {
                    dopt.viewMode = GsFileBrowserOptions.FileBrowserViewMode.DETAILED_LIST;
                    dopt.useCustomFileFolderImages = true;
                    dopt.folderImage = R.drawable.file_tab_folder;
                    dopt.fileImage = R.drawable.file_tab_file;
                }
            }

            @Override
            public void onFsViewerDoUiUpdate(final GsFileBrowserListAdapter adapter) {
                if (adapter != null && adapter.getCurrentFolder() != null && !TextUtils.isEmpty(adapter.getCurrentFolder().getName())) {
                    _appSettings.setFileBrowserLastBrowsedFolder(adapter.getCurrentFolder());
                    if (getCurrentPos() == tabIdToPos(R.id.nav_notebook)) setTitle(getFileBrowserTitle());
                }
                if (_showFile != null && adapter != null && !book) {
                    adapter.showFile(_showFile);
                    _showFile = null;
                }
            }

            @Override
            public void onFsViewerSelected(String request, File file, final Integer lineNumber) {
                showLargeFileOpenToastIfNeeded(file);
                DocumentActivity.launch(MainActivity.this, file, null, lineNumber);
            }
        });
        return opts;
    }

    class SectionsPagerAdapter extends FragmentStateAdapter {
        private final boolean[] _realized;

        SectionsPagerAdapter(FragmentManager fragMgr) {
            super(fragMgr, MainActivity.this.getLifecycle());
            final int count = _bottomNav.getMenu().size();
            _realized = new boolean[count];
            _realized[_viewPager.getCurrentItem()] = true;
        }

        @NonNull
        @Override
        public Fragment createFragment(final int pos) {
            if (!_realized[pos]) {
                return new Fragment();
            }
            final GsFragmentBase<?, ?> frag;
            final int id = tabIdFromPos(pos);
            if (id == R.id.nav_book) {
                frag = _book = GsFileBrowserFragment.newBookInstance();
            } else if (id == R.id.nav_quicknote) {
                frag = _quicknote = DocumentEditAndViewFragment.newInstance(new Document(_appSettings.getQuickNoteFile()), -1, false);
            } else if (id == R.id.nav_todo) {
                frag = _todo = DocumentEditAndViewFragment.newInstance(new Document(_appSettings.getTodoFile()), -1, false);
            } else if (id == R.id.nav_more) {
                frag = _more = MoreFragment.newInstance();
            } else {
                frag = _notebook = GsFileBrowserFragment.newInstance();
            }
            frag.setMenuVisibility(false);
            return frag;
        }

        @Override
        public int getItemCount() { return _bottomNav.getMenu().size(); }

        @Override
        public long getItemId(final int position) { return position * 2L + (_realized[position] ? 1 : 0); }

        @Override
        public boolean containsItem(final long itemId) {
            final int pos = (int) (itemId / 2L);
            if (pos < 0 || pos >= _realized.length) return false;
            return _realized[pos] == ((itemId % 2L) == 1L);
        }

        void ensureRealized(final int pos) {
            if (pos < 0 || pos >= _realized.length || _realized[pos]) return;
            _realized[pos] = true;
            notifyItemChanged(pos);
        }

        void restoreFragment(final int pos) {
            if (pos < 0 || pos >= _realized.length) return;
            _realized[pos] = true;
        }
    }

    public GsFileBrowserFragment getNotebook() { return _notebook; }

    @Override
    protected void onPause() {
        super.onPause();
        WrMarkorWidgetProvider.updateLauncherWidgets();
        TodoWidgetProvider.updateTodoWidgets();

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED |
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        restoreDefaultToolbar();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        _cu.extractResultFromActivityResult(this, requestCode, resultCode, data);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return super.onReceiveKeyPress(getPosFragment(getCurrentPos()), keyCode, event) ? true : super.onKeyDown(keyCode, event);
    }
}
