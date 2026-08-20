/*#######################################################
 *
 * SPDX-FileCopyrightText: 2017-2025 Gregor Santner <gsantner AT mailbox DOT org>
 * SPDX-License-Identifier: Unlicense OR CC0-1.0
 *
 * Written 2018-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 * To the extent possible under law, the author(s) have dedicated all copyright and related and neighboring rights to this software to the public domain worldwide. This software is distributed without any warranty.
 * You should have received a copy of the CC0 Public Domain Dedication along with this software. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
#########################################################*/
package net.gsantner.opoc.frontend.filebrowser;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Parcelable;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.style.StrikethroughSpan;
import android.util.Log;
import android.util.LruCache;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.gsantner.markor.R;
import net.gsantner.markor.frontend.textview.TextViewUtils;
import net.gsantner.markor.model.AppSettings;
import net.gsantner.opoc.util.GsCollectionUtils;
import net.gsantner.opoc.util.GsContextUtils;
import net.gsantner.opoc.util.GsFileUtils;
import net.gsantner.opoc.wrapper.GsCallback;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@SuppressWarnings({"WeakerAccess", "unused"})
public class GsFileBrowserListAdapter extends RecyclerView.Adapter<GsFileBrowserListAdapter.FilesystemViewerViewHolder> implements Filterable, View.OnClickListener, View.OnLongClickListener {
    //########################
    //## Static
    //########################
    public static final File VIRTUAL_STORAGE_ROOT = new File("/storage/");
    public static final File VIRTUAL_STORAGE_EMULATED = new File(VIRTUAL_STORAGE_ROOT, "emulated");
    public static final File VIRTUAL_STORAGE_RECENTS = new File(VIRTUAL_STORAGE_ROOT, "Recent");
    public static final File VIRTUAL_STORAGE_FAVOURITE = new File(VIRTUAL_STORAGE_ROOT, "Favourites");
    public static final File VIRTUAL_STORAGE_POPULAR = new File(VIRTUAL_STORAGE_ROOT, "Popular");
    public static final File VIRTUAL_STORAGE_APP_DATA_PRIVATE = new File(VIRTUAL_STORAGE_ROOT, "AppData (private)");
    public static final String EXTRA_CURRENT_FOLDER = "EXTRA_CURRENT_FOLDER";
    public static final String EXTRA_DOPT = "EXTRA_DOPT";
    public static final String EXTRA_RECYCLER_SCROLL_STATE = "EXTRA_RECYCLER_SCROLL_STATE";
    public static final String EXTRA_REQ_FOLDER = "EXTRA_REQ_FOLDER";
    public static final int FAVOURITE_COLOR = 0xFFE3B51B;

    private static final File GO_BACK_SIGNIFIER = new File("__GO_BACK__");
    private static final StrikethroughSpan STRIKE_THROUGH_SPAN = new StrikethroughSpan();

    //########################
    //## Members
    //########################
    private final GsFileBrowserOptions.Options _dopt;
    private final List<File> _adapterData; // List of current folder
    private final List<File> _adapterDataFiltered; // Filtered list of current folder
    private final Set<File> _currentSelection;
    private File _fileToShowAfterNextLoad;
    private File _currentFolder;
    private File _goUpFile;
    private final Context _context;
    private final StringFilter _filter;
    private RecyclerView _recyclerView;
    private LinearLayoutManager _layoutManager;
    private final Map<File, File> _virtualMapping = new LinkedHashMap<>();
    private final Map<File, Integer> _fileIdMap = new HashMap<>();
    private final Map<File, Parcelable> _folderScrollMap = new HashMap<>();
    private final Stack<File> _backStack = new Stack<>();
    private final int _userId = getUserId();
    private long _prevModSum = 0;
    private static final int FOLDER_OBSERVER_MASK =
            FileObserver.CREATE | FileObserver.DELETE | FileObserver.MOVED_FROM
                    | FileObserver.MOVED_TO | FileObserver.MODIFY;
    private FileObserver _folderObserver;
    private final Runnable _folderReloadDebounced = TextViewUtils.makeDebounced(300, this::reloadCurrentFolder);

    //########################
    //## Methods
    //########################
    public GsFileBrowserListAdapter(GsFileBrowserOptions.Options options, Context context) {
        _dopt = options;
        _adapterData = new ArrayList<>();
        _adapterDataFiltered = new ArrayList<>();
        _currentSelection = new HashSet<>();
        _context = context;
        GsContextUtils.instance.setAppLocale(_context, Locale.getDefault());

        // Prevents view flicker - https://stackoverflow.com/a/32488059
        setHasStableIds(true);

        GsContextUtils cu = GsContextUtils.instance;
        if (_dopt.primaryColor == 0) {
            _dopt.primaryColor = cu.getResId(context, GsContextUtils.ResType.COLOR, "primary");
        }
        if (_dopt.accentColor == 0) {
            _dopt.accentColor = cu.getResId(context, GsContextUtils.ResType.COLOR, "accent");
        }
        if (_dopt.primaryTextColor == 0) {
            _dopt.primaryTextColor = cu.getResId(context, GsContextUtils.ResType.COLOR, "primary_text");
        }
        if (_dopt.secondaryTextColor == 0) {
            _dopt.secondaryTextColor = cu.getResId(context, GsContextUtils.ResType.COLOR, "secondary_text");
        }
        if (_dopt.titleTextColor == 0) {
            _dopt.titleTextColor = _dopt.primaryTextColor;
        }
        if (_dopt.fileColor == 0) {
            _dopt.fileColor = cu.getResId(context, GsContextUtils.ResType.COLOR, "file");
        }
        if (_dopt.folderColor == 0) {
            _dopt.folderColor = cu.getResId(context, GsContextUtils.ResType.COLOR, "folder");
        }

        updateVirtualFolders();
        _filter = new StringFilter(this);
    }

    public void updateVirtualFolders() {
        final GsContextUtils cu = GsContextUtils.instance;

        _virtualMapping.clear();
        _virtualMapping.put(VIRTUAL_STORAGE_EMULATED, VIRTUAL_STORAGE_EMULATED);

        final File appDataFolder = _context.getFilesDir();
        if (appDataFolder.exists() || appDataFolder.mkdir()) {
            _virtualMapping.put(VIRTUAL_STORAGE_APP_DATA_PRIVATE, appDataFolder);
        }

        final File[] externals = ContextCompat.getExternalFilesDirs(_context, null);
        for (int i = 0; i < externals.length; i++) {
            final File file = externals[i];
            if (file != null) {
                final File parent = file.getParentFile();
                if (parent != null) {
                    final String name = parent.getName();
                    final File remap = new File(VIRTUAL_STORAGE_ROOT, "AppData (external-" + i + ")");
                    _virtualMapping.put(remap, file);
                }
            }
        }

        _virtualMapping.put(VIRTUAL_STORAGE_RECENTS, VIRTUAL_STORAGE_RECENTS);
        _virtualMapping.put(VIRTUAL_STORAGE_POPULAR, VIRTUAL_STORAGE_POPULAR);
        _virtualMapping.put(VIRTUAL_STORAGE_FAVOURITE, VIRTUAL_STORAGE_FAVOURITE);

        _virtualMapping.putAll(_dopt.storageMaps);

    }

    @NonNull
    @Override
    public FilesystemViewerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        final int layoutRes = viewType == GsFileBrowserOptions.FileBrowserViewMode.GRID.ordinal()
                ? R.layout.opoc_filesystem_item_grid
                : R.layout.opoc_filesystem_item;
        View v = LayoutInflater.from(parent.getContext()).inflate(layoutRes, parent, false);
        return new FilesystemViewerViewHolder(v);
    }

    @Override
    public int getItemViewType(final int position) {
        return _dopt.viewMode.ordinal();
    }

    public boolean isCurrentFolderEmpty() {
        return _adapterData.size() < 2;
    }

    public boolean isFileWriteable(File file, boolean isGoUp) {
        return file != null && (canWrite(file) || isGoUp || _virtualMapping.containsKey(file));
    }

    @Override
    @SuppressWarnings("ConstantConditions")
    public void onBindViewHolder(@NonNull FilesystemViewerViewHolder holder, int position) {
        final File displayFile = _adapterDataFiltered.get(position);

        if (displayFile == null) {
            holder.title.setText("????");
            return;
        }

        final File file = resolveVirtualFile(displayFile);

        final boolean isGoUp = displayFile.equals(_goUpFile);
        final boolean isVirtual = _virtualMapping.containsKey(displayFile);
        final boolean isSelected = _currentSelection.contains(displayFile);
        final boolean isFavourite = _dopt.favouriteFiles != null && _dopt.favouriteFiles.contains(displayFile);
        final boolean isPopular = _dopt.popularFiles != null && _dopt.popularFiles.contains(displayFile);
        final boolean isFile = displayFile.isFile();

        String titleText = displayFile.getName();
        if (isCurrentFolderVirtual() && "index.html".equals(titleText)) {
            final String currentFolderName = _currentFolder != null ? _currentFolder.getName() : "";
            titleText += " [" + currentFolderName + "]";
        }

        // Set title
        holder.title.setText(isGoUp ? ".." : titleText, TextView.BufferType.SPANNABLE);
        holder.title.setTextColor(ContextCompat.getColor(_context, _dopt.primaryTextColor));

        if (!isFileWriteable(displayFile, isGoUp) && !isVirtual && holder.title.length() > 0) {
            try {
                ((Spannable) holder.title.getText()).setSpan(STRIKE_THROUGH_SPAN, 0, holder.title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } catch (Exception ignored) {
            }
        }

        // Set description (hidden in LIST and GRID view modes - only DETAILED_LIST shows it)
        final boolean showDescription = _dopt.viewMode == GsFileBrowserOptions.FileBrowserViewMode.DETAILED_LIST;
        if (holder.description != null) {
            if (showDescription) {
                if (!_dopt.descModtimeInsteadOfParent || isGoUp) {
                    holder.description.setText(file.getAbsolutePath());
                } else {
                    holder.description.setText(formatFileDescription(file, _dopt.descriptionFormat));
                }
                holder.description.setTextColor(ContextCompat.getColor(_context, _dopt.secondaryTextColor));
            } else {
                holder.description.setText(""); // Avoid stale text on a recycled view leaking into content descriptions
            }
            holder.description.setVisibility(showDescription ? View.VISIBLE : View.GONE);
        }

        // Set icon
        final boolean isGridMode = _dopt.viewMode == GsFileBrowserOptions.FileBrowserViewMode.GRID;
        if (isSelected && !isGridMode) {
            // List / detailed-list: swap the icon for a checkmark, same as before.
            holder.image.setImageResource(_dopt.selectedItemImage);
        } else if (_dopt.iconMaps != null && _dopt.iconMaps.containsKey(displayFile)) {
            holder.image.setImageResource(_dopt.iconMaps.get(displayFile));
        } else if (!isFile && !isGridMode && _dopt.hideGenericFolderIconInList) {
            // The notebook's list already has directory navigation in the toolbar. Do not repeat
            // the generic folder/choose-directory icon on every folder row.
            holder.image.setImageDrawable(null);
        } else {
            holder.image.setImageResource(isFile ? _dopt.fileImage : _dopt.folderImage);
        }
        // Grid mode keeps showing the real icon/cover even when selected - a highlighted
        // background around it (applied below) is the selection indicator instead, so a
        // selected item's cover image doesn't get hidden behind a plain checkmark.

        holder.image.setColorFilter(ContextCompat.getColor(
                        _context,
                        isSelected ? _dopt.accentColor : isFile ? _dopt.fileColor : _dopt.folderColor),
                android.graphics.PorterDuff.Mode.SRC_ATOP
        );

        if (!isSelected && !isGoUp && isFavourite) {
            holder.image.setColorFilter(FAVOURITE_COLOR);
        }

        // Grid view: apply spacing, rectangle sizing, rounded corners, and either a cover image
        // (if the folder has one) or the default icon centered in a placeholder rectangle - plus
        // a noticeable highlight background when selected.
        applyGridCoverImageIfAny(holder, file, isGoUp, isVirtual, isFile);
        if (isGridMode) {
            applyGridSelectionHighlight(holder, isSelected);
        }

        // Some extras
        if (isGridMode) {
            // Gap between grid items is independent from the outer list padding. This lets the
            // icon/cover remain large while keeping the first/last column away from screen edges.
            final int gapPx = (int) (AppSettings.get(_context).getGridSpacingDp() * _context.getResources().getDisplayMetrics().density);
            holder.itemRoot.setPadding(gapPx, gapPx, gapPx, gapPx / 2);
        } else if (_dopt.itemSidePadding > 0) {
            int dp = (int) (_dopt.itemSidePadding * _context.getResources().getDisplayMetrics().density);
            holder.itemRoot.setPadding(dp, holder.itemRoot.getPaddingTop(), dp, holder.itemRoot.getPaddingBottom());
        }

        final int descriptionRes = isSelected ? _dopt.contentDescriptionSelected : (displayFile.isDirectory() ? _dopt.contentDescriptionFolder : _dopt.contentDescriptionFile);
        holder.itemRoot.setContentDescription((descriptionRes != 0 ? (_context.getString(descriptionRes) + " ") : "") + titleText + " " + holder.description.getText().toString());
        // Reset recycled hover state, then highlight both the icon and title while the pointer
        // is over either one. This is mainly useful for mouse/trackpad use in grid mode.
        holder.itemRoot.setTag(new TagContainer(displayFile, position));
        setGridHoverState(holder, false);
        if (isGridMode) {
            final View.OnHoverListener hoverListener = (view, event) -> {
                if (event.getAction() == android.view.MotionEvent.ACTION_HOVER_ENTER) {
                    setGridHoverState(holder, true);
                } else if (event.getAction() == android.view.MotionEvent.ACTION_HOVER_EXIT) {
                    setGridHoverState(holder, false);
                }
                return false;
            };
            holder.itemRoot.setOnHoverListener(hoverListener);
            holder.image.setOnHoverListener(hoverListener);
            holder.title.setOnHoverListener(hoverListener);
        } else {
            holder.itemRoot.setOnHoverListener(null);
            holder.image.setOnHoverListener(null);
            holder.title.setOnHoverListener(null);
        }

        // In grid mode a long-press on the icon is a selection gesture, not a directory/open
        // gesture and not a diagnostic toast. The normal tap still opens the folder/file.
        holder.image.setOnLongClickListener(view -> {
            toggleSelection(new TagContainer(displayFile, position));
            _dopt.listener.onFsViewerItemLongPressed(displayFile, _dopt.doSelectMultiple);
            return true;
        });
        holder.image.setOnClickListener(view -> onClick(holder.itemRoot));

        holder.itemRoot.setOnClickListener(this);
        holder.itemRoot.setOnLongClickListener(this);
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull final RecyclerView view) {
        super.onAttachedToRecyclerView(view);
        _recyclerView = view;
        _layoutManager = (LinearLayoutManager) view.getLayoutManager();
        reloadCurrentFolder();
    }

    /**
     * Call after swapping the RecyclerView's LayoutManager at runtime (e.g. switching between
     * list and grid view mode) so scroll-state tracking (save/restore/scrollTo) keeps pointing
     * at the LayoutManager instance that is actually attached.
     * GridLayoutManager extends LinearLayoutManager, so this covers both.
     */
    public void onLayoutManagerChanged() {
        if (_recyclerView != null && _recyclerView.getLayoutManager() instanceof LinearLayoutManager) {
            _layoutManager = (LinearLayoutManager) _recyclerView.getLayoutManager();
        }
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull final RecyclerView view) {
        stopFolderObserver();
        super.onDetachedFromRecyclerView(view);
    }

    private void rebindFolderObserver() {
        stopFolderObserver();
        final File folder = _currentFolder;
        if (folder == null || !folder.isDirectory() || !folder.canRead()) {
            return;
        }
        _folderObserver = new FileObserver(folder.getAbsolutePath(), FOLDER_OBSERVER_MASK) {
            @Override
            public void onEvent(int event, @Nullable String path) {
                if (path == null) {
                    return;
                }
                _folderReloadDebounced.run();
            }
        };
        _folderObserver.startWatching();
    }

    private void stopFolderObserver() {
        if (_folderObserver != null) {
            _folderObserver.stopWatching();
            _folderObserver = null;
        }
    }

    public String formatFileDescription(final File file, String format) {
        if (TextUtils.isEmpty(format)) {
            return DateUtils.formatDateTime(_context, file.lastModified(), (DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_NUMERIC_DATE));
        } else {
            format = format.replaceAll("FS(?=([^']*'[^']*')*[^']*$)", '\'' + GsFileUtils.getHumanReadableByteCountSI(file.length()) + '\'');
            return new SimpleDateFormat(format, Locale.getDefault()).format(file.lastModified());
        }
    }

    public void saveInstanceState(final @NonNull Bundle outState) {
        if (_currentFolder != null) {
            outState.putSerializable(EXTRA_CURRENT_FOLDER, _currentFolder.getAbsolutePath());
        }

        if (_recyclerView != null) {
            if (_recyclerView.getLayoutManager() != null) {
                outState.putParcelable(EXTRA_RECYCLER_SCROLL_STATE, _layoutManager.onSaveInstanceState());
            }
        }
    }

    public void restoreSavedInstanceState(final Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            return;
        }

        if (_dopt != null && _dopt.listener != null) {
            _dopt.listener.onFsViewerConfig(_dopt);
        }

        if (savedInstanceState.containsKey(EXTRA_CURRENT_FOLDER)) {
            final String path = savedInstanceState.getString(EXTRA_CURRENT_FOLDER);
            if (path != null) {
                final File f = new File(path);
                final boolean isVirtualDirectory = _virtualMapping.containsKey(f);

                if (f.isDirectory() || isVirtualDirectory) {
                    loadFolder(f, null);
                }
            }
        }

        if (savedInstanceState.containsKey(EXTRA_RECYCLER_SCROLL_STATE) && _layoutManager != null) {
            _recyclerView.postDelayed(() -> _layoutManager.onRestoreInstanceState(savedInstanceState.getParcelable(EXTRA_RECYCLER_SCROLL_STATE)), 200);
        }
    }

    public void reloadCurrentFolder() {
        if (_currentFolder != null) {
            loadFolder(_currentFolder, null);
        } else if (_dopt.startFolder != null) {
            loadFolder(_dopt.startFolder, null);
        } else {
            loadFolder(_dopt.rootFolder, null);
        }
    }

    public void setCurrentFolder(final File folder) {
        loadFolder(folder, GsFileUtils.isChild(_currentFolder, folder) ? folder : null);
    }

    public static class TagContainer {
        public final File file;
        public final int position;

        public TagContainer(File file_, int position_) {
            file = file_;
            position = position_;
        }
    }

    // Prevents view flicker - https://stackoverflow.com/a/32488059
    @Override
    public long getItemId(final int position) {
        final File f = _adapterDataFiltered.get(position);
        final Integer key = _fileIdMap.get(f);
        if (key == null) {
            final int newId = _fileIdMap.size();
            _fileIdMap.put(f, newId);
            return newId;
        } else {
            return key;
        }
    }

    public File getCurrentFolder() {
        return _currentFolder;
    }

    @Override
    public int getItemCount() {
        return _adapterDataFiltered.size();
    }

    @Override
    public Filter getFilter() {
        return _filter;
    }

    public boolean isCurrentFolderWriteable() {
        return canWrite(_currentFolder);
    }

    private int getPathLevel(String path) {
        final int end = path.lastIndexOf('/');
        int level = 0;
        for (int i = 0; i <= end; i++) {
            if (path.charAt(i) == '/') {
                level++;
            }
        }
        return level;
    }

    @Override
    @SuppressWarnings("UnnecessaryReturnStatement")
    public void onClick(View view) {
        final TagContainer data = (TagContainer) view.getTag();

        if (!_currentSelection.isEmpty()) {
            // Blink in multi-select
            GsContextUtils.blinkView(view);
        }

        switch (view.getId()) {
            case R.id.opoc_filesystem_item__root: {
                // A own item was clicked
                if (data.file != null) {
                    if (areItemsSelected()) {
                        // There are 1 or more items selected yet
                        if (!toggleSelection(data) && data.file.isDirectory()) {
                            loadFolder(data.file, null);
                        }
                    } else {
                        // No pre-selection
                        if (data.file.isDirectory() || _virtualMapping.containsKey(data.file)) {
                            loadFolder(data.file, data.file.equals(_goUpFile) ? _currentFolder : null);
                        } else if (data.file.isFile()) {
                            _dopt.listener.onFsViewerSelected(_dopt.requestId, data.file, null);
                        }
                    }
                }
                return;
            }
            case R.id.ui__filesystem_dialog__home: {
                loadFolder(_dopt.rootFolder, _currentFolder);
                return;
            }
            case R.id.ui__filesystem_dialog__button_ok: {
                if (_dopt.doSelectMultiple && areItemsSelected()) {
                    _dopt.listener.onFsViewerMultiSelected(_dopt.requestId, _currentSelection.toArray(new File[0]));
                } else {
                    _dopt.listener.onFsViewerSelected(_dopt.requestId, _currentFolder, null);
                }
                return;
            }
        }
    }

    public void toggleSelectionAll() {
        for (int i = 0; i < _adapterDataFiltered.size(); i++) {
            final TagContainer data = new TagContainer(_adapterDataFiltered.get(i), i);
            toggleSelection(data);
        }
    }

    public void selectAll() {
        for (int i = 0; i < _adapterDataFiltered.size(); i++) {
            final TagContainer data = new TagContainer(_adapterDataFiltered.get(i), i);
            if (!_currentSelection.contains(data.file)) {
                if (data.file.isDirectory() && getCurrentFolder().getParentFile() != null && getCurrentFolder().getParentFile().equals(data.file)) {
                    continue;
                }
                _currentSelection.add(data.file);
                notifyItemChanged(data.position);
            }
        }
        _dopt.listener.onFsViewerDoUiUpdate(this);
    }

    public void unselectAll() {
        for (int i = 0; i < _adapterDataFiltered.size(); i++) {
            final TagContainer data = new TagContainer(_adapterDataFiltered.get(i), i);
            if (_currentSelection.contains(data.file)) {
                _currentSelection.remove(data.file);
                notifyItemChanged(data.position);
            }
        }
        _dopt.listener.onFsViewerDoUiUpdate(this);
    }

    public boolean areItemsSelected() {
        return !_currentSelection.isEmpty();
    }

    public Set<File> getCurrentSelection() {
        return _currentSelection;
    }

    public boolean isFilesOnlySelected() {
        for (File f : _currentSelection) {
            if (f.isDirectory()) {
                return false;
            }
        }
        return true;
    }

    public boolean toggleSelection(final TagContainer data) {
        if (data == null) {
            return false;
        }

        boolean clickHandled = false;
        if (data.file != null && _currentFolder != null && !data.file.equals(_goUpFile)) {
            if (_currentSelection.contains(data.file)) {
                // Single selection
                _currentSelection.remove(data.file);
                clickHandled = true;
            } else if (_dopt.doSelectMultiple) {
                // Multi selection
                if (_dopt.doSelectFile && !data.file.isDirectory()) {
                    // Multi selection - file
                    _currentSelection.add(data.file);
                    clickHandled = true;
                }
                if (_dopt.doSelectFolder && data.file.isDirectory()) {
                    // Multi selection - folder
                    _currentSelection.add(data.file);
                    clickHandled = true;
                }
            }
        }

        notifyItemChanged(data.position);
        _dopt.listener.onFsViewerDoUiUpdate(this);

        return clickHandled;
    }

    public boolean goBack() {
        if (!_backStack.isEmpty()) {
            File show = _currentFolder;
            if (VIRTUAL_STORAGE_ROOT.equals(_backStack.peek())) {
                show = GsCollectionUtils.reverseSearch(_virtualMapping, _currentFolder);
            }
            loadFolder(GO_BACK_SIGNIFIER, show);
            return true;
        }
        return false;
    }

    private @Nullable File getCurrentParent() {
        if (_currentFolder == null) {
            return null;
        }

        final File parent = _currentFolder.getParentFile();
        if ((parent != null && parent.canWrite()) || GsFileUtils.isChild(VIRTUAL_STORAGE_ROOT, parent)) {
            return parent;
        }

        if (VIRTUAL_STORAGE_ROOT.equals(parent) || _virtualMapping.containsValue(_currentFolder)) {
            return VIRTUAL_STORAGE_ROOT;
        }

        return null;
    }

    @Override
    public boolean onLongClick(final View view) {
        GsContextUtils.blinkView(view);
        if (view.getId() == R.id.opoc_filesystem_item__root) {
            final TagContainer data = (TagContainer) view.getTag();
            toggleSelection(data);
            _dopt.listener.onFsViewerItemLongPressed(data.file, _dopt.doSelectMultiple);
            return true;
        }
        return false;
    }

    public File createDirectoryHere(final CharSequence name) {
        if (name == null || _currentFolder == null || !_currentFolder.canWrite()) {
            return null;
        }

        final String trimmed = name.toString().trim();

        if (trimmed.isEmpty()) {
            return null;
        }

        try {
            final File file = new File(_currentFolder, trimmed);
            if (file.exists() || file.mkdir()) {
                loadFolder(_currentFolder, file);
                return file;
            }
        } catch (SecurityException ignored) {
        }

        Toast.makeText(_context, R.string.file_does_not_exist_and_cant_be_created, Toast.LENGTH_LONG).show();
        return null;
    }

    // Switch to folder and show the file
    public void showFile(final File file) {
        if (file == null || !file.exists() || _recyclerView == null) {
            return;
        }

        final File dir = file.getParentFile();
        if (dir != null) {
            loadFolder(dir, file);
        }
    }

    private void doAfterChange(final GsCallback.a0 callback) {
        _recyclerView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                _recyclerView.removeOnLayoutChangeListener(this);
                callback.callback();
            }
        });
    }

    private void postScrollToAndFlash(final File file) {
        if (_recyclerView != null && file != null) {
            _recyclerView.post(() -> scrollToAndFlash(file));
        }
    }

    /**
     * Scroll to a file in current folder and flash
     *
     * @param file File to blink
     */
    public boolean scrollToAndFlash(final File file) {
        final int pos = _adapterDataFiltered.indexOf(file);
        if (pos >= 0 && _layoutManager != null) {
            _layoutManager.scrollToPosition(pos);
            _recyclerView.post(() ->
                    _recyclerView.postDelayed(() -> {
                        final RecyclerView.ViewHolder holder = _recyclerView.findViewHolderForLayoutPosition(pos);
                        if (holder != null) {
                            GsContextUtils.blinkView2(holder.itemView);
                            holder.itemView.requestFocus();
                        }
                    }, 400));
            return true;
        }
        return false;
    }

    private static final ExecutorService executorService = new ThreadPoolExecutor(0, 3, 60, TimeUnit.SECONDS, new SynchronousQueue<>());

    // Small in-memory cache for grid-view folder cover images, keyed by "path:mtime:wxh"
    // so a changed/replaced cover file or a settings size change invalidates old cache entries.
    private static final LruCache<String, Bitmap> _coverImageCache = new LruCache<>(60);

    /**
     * Grid view only: sizes the icon/cover container to the user-configured rectangle
     * (shared with {@link AppSettings#getGridCoverImageWidthDp()}/{@code HeightDp()}), so a
     * folder/file without a cover uses the exact same resizable rectangle as one that has a
     * cover image. If {@code folder} is a real, non-virtual, non-"go up" directory containing a
     * cover image file (name configurable via {@link AppSettings#getGridCoverImageFilename()}),
     * that cover is decoded and shown (cropped to fill the rectangle) instead of the default
     * folder/file icon. Otherwise the default icon is shown centered inside the rectangle, on
     * top of a neutral placeholder background, so it doesn't stretch to fill the whole shape.
     * <p>
     * List / detailed-list views are left untouched (they still use their own small square icon
     * at the layout's fixed default size).
     */
    private void applyGridCoverImageIfAny(final FilesystemViewerViewHolder holder, final File folder,
                                           final boolean isGoUp, final boolean isVirtual, final boolean isFile) {
        final boolean isGridMode = _dopt.viewMode == GsFileBrowserOptions.FileBrowserViewMode.GRID;

        if (!isGridMode || holder.imageFrame == null) {
            // List / detailed-list: revert to this holder's XML-default (small square) size,
            // in case this view was previously recycled from grid mode.
            final ViewGroup.LayoutParams lp = holder.image.getLayoutParams();
            if (lp != null && (lp.width != holder.defaultImageWidth || lp.height != holder.defaultImageHeight)) {
                lp.width = holder.defaultImageWidth;
                lp.height = holder.defaultImageHeight;
                holder.image.setLayoutParams(lp);
            }
            holder.image.setTag(null);
            return;
        }

        final AppSettings settings = AppSettings.get(_context);
        final float density = _context.getResources().getDisplayMetrics().density;
        final int wPx = Math.max(1, (int) (settings.getGridCoverImageWidthDp() * density));
        final int hPx = Math.max(1, (int) (settings.getGridCoverImageHeightDp() * density));
        setImageContainerSize(holder, wPx, hPx);

        final boolean eligibleForCover = !isGoUp && !isVirtual && !isFile && folder != null;
        if (!eligibleForCover) {
            holder.image.setTag(null);
            showDefaultGridIcon(holder, wPx);
            return;
        }

        final File coverFile = new File(folder, settings.getGridCoverImageFilename());
        // Guard token: an async decode result is only applied if the holder is still bound to
        // this exact cover path when it completes (prevents stale results on a recycled view).
        holder.image.setTag(coverFile.getAbsolutePath());

        if (!coverFile.isFile()) {
            showDefaultGridIcon(holder, wPx);
            return;
        }

        final String cacheKey = coverFile.getAbsolutePath() + ":" + coverFile.lastModified() + ":" + wPx + "x" + hPx;
        final Bitmap cached = _coverImageCache.get(cacheKey);
        if (cached != null) {
            setGridCoverBitmap(holder, cached);
            return;
        }

        // Until the cover loads (or if it fails to decode) show the default icon so the cell
        // isn't left blank.
        showDefaultGridIcon(holder, wPx);

        try {
            executorService.execute(() -> {
                final Bitmap bmp = GsContextUtils.instance.loadImageFromFilesystem(coverFile, Math.max(wPx, hPx));
                if (bmp != null) {
                    _coverImageCache.put(cacheKey, bmp);
                }
                holder.image.post(() -> {
                    if (bmp != null && coverFile.getAbsolutePath().equals(holder.image.getTag())) {
                        setGridCoverBitmap(holder, bmp);
                    }
                });
            });
        } catch (RejectedExecutionException ignored) {
            // Pool momentarily full - the icon simply stays as the default icon
            // until this row is bound again (e.g. on the next scroll pass).
        }
    }

    private void setImageContainerSize(final FilesystemViewerViewHolder holder, final int wPx, final int hPx) {
        final ViewGroup.LayoutParams flp = holder.imageFrame.getLayoutParams();
        if (flp != null) {
            flp.width = wPx;
            flp.height = hPx;
            holder.imageFrame.setLayoutParams(flp);
        }
        final ViewGroup.LayoutParams ilp = holder.image.getLayoutParams();
        if (ilp != null) {
            ilp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            ilp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            holder.image.setLayoutParams(ilp);
        }
    }

    /**
     * Shows the already-set folder/file drawable centered (not stretched) inside the rectangle,
     * with a neutral placeholder background behind it, rather than the cover-image crop style.
     */
    private void showDefaultGridIcon(final FilesystemViewerViewHolder holder, final int containerWidthPx) {
        holder.image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        final int pad = (int) (containerWidthPx * 0.22f);
        holder.image.setPadding(pad, pad, pad, pad);
        holder.imageFrame.setTag(R.id.opoc_filesystem_item__image_frame, "placeholder");
    }

    private void setGridCoverBitmap(final FilesystemViewerViewHolder holder, final Bitmap bmp) {
        holder.image.clearColorFilter();
        holder.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        holder.image.setPadding(0, 0, 0, 0);
        holder.image.setImageBitmap(bmp);
        holder.imageFrame.setTag(R.id.opoc_filesystem_item__image_frame, "cover");
    }

    /**
     * Builds a rounded-rectangle cell mark (background / selection / hover) at runtime instead
     * of using a static drawable resource. The old drawables (grid_item_bg_placeholder,
     * grid_item_bg_normal, grid_item_bg_selected, grid_item_bg_hover) baked in a fixed 10dp
     * corner radius and fixed 1-3dp stroke width, which only ever matched the corner-radius
     * setting's default value. A cover image has no baked-in radius of its own, so it always
     * looked correctly rounded (clipped purely by the ViewHolder's dynamic outline provider) -
     * but the icon-without-cover placeholder background, drawn from that static drawable, was
     * clipped by the dynamic outline AND rounded to its own smaller/mismatched baked-in radius,
     * so pushing the corner-radius setting above 10dp had no visible effect on it. Building the
     * shape here with the live radius fixes that, and scaling the stroke width with the
     * configured cover size makes the selection/hover mark's thickness track the covers' size
     * too, instead of staying a fixed 1-3dp line regardless of how big the covers are.
     */
    private GradientDrawable buildGridCellDrawable(final int fillColor, final int strokeColor, final int strokeWidthPx, final float radiusPx) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fillColor);
        drawable.setCornerRadius(radiusPx);
        if (strokeWidthPx > 0) {
            drawable.setStroke(strokeWidthPx, strokeColor);
        }
        return drawable;
    }

    /**
     * Grid view only: shows a noticeable accent-colored highlight behind the icon/cover when the
     * item is selected, instead of replacing the icon with a checkmark (which would hide a cover
     * image). Falls back to the normal / placeholder background when not selected.
     */
    private void applyGridSelectionHighlight(final FilesystemViewerViewHolder holder, final boolean isSelected) {
        if (holder.imageFrame == null) {
            return;
        }

        final AppSettings settings = AppSettings.get(_context);
        final float density = _context.getResources().getDisplayMetrics().density;
        final float radiusPx = density * settings.getGridCornerRadiusDp();
        // Selection/hover stroke thickness relative to the covers' configured size, clamped to
        // a sane 1dp-4dp range so it stays visible on tiny covers and isn't overpowering on big ones.
        final int minCoverDp = Math.min(settings.getGridCoverImageWidthDp(), settings.getGridCoverImageHeightDp());
        final int markStrokeWidthPx = Math.round(density * Math.max(1f, Math.min(4f, minCoverDp * 0.035f)));

        // Keep the normal/placeholder background underneath the image, but put the selection
        // drawable in the FrameLayout foreground so its stroke is drawn ON TOP of the icon/cover.
        // Previously it was a background and could be completely covered by the ImageView.
        final boolean isPlaceholder = "placeholder".equals(holder.imageFrame.getTag(R.id.opoc_filesystem_item__image_frame));
        holder.imageFrame.setBackground(buildGridCellDrawable(
                isPlaceholder ? ContextCompat.getColor(_context, android.R.color.darker_gray) : Color.TRANSPARENT,
                0, 0, radiusPx));

        if (isSelected) {
            holder.imageFrame.setForeground(buildGridCellDrawable(
                    0x33F04B4B, ContextCompat.getColor(_context, R.color.accent), markStrokeWidthPx, radiusPx));
        } else {
            holder.imageFrame.setForeground(null);
        }
    }

    private void setGridHoverState(final FilesystemViewerViewHolder holder, final boolean hovered) {
        if (holder.imageFrame == null || _dopt.viewMode != GsFileBrowserOptions.FileBrowserViewMode.GRID) {
            return;
        }

        final AppSettings settings = AppSettings.get(_context);
        final float density = _context.getResources().getDisplayMetrics().density;
        final float radiusPx = density * settings.getGridCornerRadiusDp();
        final int minCoverDp = Math.min(settings.getGridCoverImageWidthDp(), settings.getGridCoverImageHeightDp());
        final int markStrokeWidthPx = Math.round(density * Math.max(1f, Math.min(4f, minCoverDp * 0.035f)));

        final boolean selected = _currentSelection.contains(
                holder.itemRoot.getTag() instanceof TagContainer
                        ? ((TagContainer) holder.itemRoot.getTag()).file : null);
        if (selected) {
            holder.imageFrame.setForeground(buildGridCellDrawable(
                    0x33F04B4B, ContextCompat.getColor(_context, R.color.accent), markStrokeWidthPx, radiusPx));
        } else if (hovered) {
            holder.imageFrame.setForeground(buildGridCellDrawable(
                    0x12000000, ContextCompat.getColor(_context, R.color.accent), Math.max(1, markStrokeWidthPx / 3), radiusPx));
        } else {
            holder.imageFrame.setForeground(null);
        }

        holder.title.setTextColor(ContextCompat.getColor(
                _context, hovered ? _dopt.accentColor : _dopt.primaryTextColor));
    }

    private void animateFolderContent(final int direction) {
        if (_recyclerView == null || direction == 0) {
            return;
        }
        final int width = Math.max(1, _recyclerView.getWidth());
        _recyclerView.animate().cancel();
        _recyclerView.setTranslationX(direction > 0 ? width : -width);
        _recyclerView.animate()
                .translationX(0f)
                .setDuration(180L)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    private void loadFolder(final File folder, final File show) {
        if (folder == null || _recyclerView == null) {
            return;
        }

        final File previousFolder = _currentFolder;
        final boolean folderChanged = !folder.equals(previousFolder);
        // Forward navigation (opening a child) enters from the right; back navigation enters
        // from the left, matching the direction of the page transition.
        final int navigationDirection = !folderChanged ? 0 :
                (GO_BACK_SIGNIFIER == folder || (previousFolder != null && GsFileUtils.isChild(folder, previousFolder)) ? -1 : 1);

        if (folderChanged && previousFolder != null && _layoutManager != null) {
            _folderScrollMap.put(previousFolder, _layoutManager.onSaveInstanceState());
        }

        // Update current folder. Keep a snapshot because loading happens asynchronously; reading
        // the mutable _currentFolder from the worker allowed a fast open/close sequence to apply
        // stale results to the newly selected folder.
        if (GO_BACK_SIGNIFIER == folder) {
            _currentFolder = _backStack.pop();
        } else {
            if (folderChanged && previousFolder != null) {
                _backStack.push(previousFolder);
            }
            _currentFolder = resolveVirtualFile(folder);
        }

        if (folderChanged) {
            _currentSelection.clear();

            // Move the RecyclerView off-screen (in the direction the new content will enter
            // from) BEFORE clearing it. Without this, the clear below renders on its own frame
            // at translationX=0 - a visible flash of blank content - and only the later
            // repopulation (once the background scan finishes) would be off-screen/animated.
            // Doing both in the same synchronous block means the blank state is never drawn
            // on-screen at all, so the whole transition reads as a single smooth slide instead
            // of a flash followed by an animation.
            if (navigationDirection != 0) {
                final int width = Math.max(1, _recyclerView.getWidth());
                _recyclerView.animate().cancel();
                _recyclerView.setTranslationX(navigationDirection > 0 ? width : -width);
            }

            // Clear the old folder immediately. Without this, RecyclerView keeps drawing the old
            // main-page contents until the background directory scan finishes, causing a visible
            // flash of the main page when opening a folder and of the first rows when closing it.
            _adapterData.clear();
            _adapterDataFiltered.clear();
            _goUpFile = null;
            _fileIdMap.clear();
            notifyDataSetChanged();

            rebindFolderObserver();
        }

        _dopt.listener.onFsViewerFolderLoad(_currentFolder);

        if (VIRTUAL_STORAGE_ROOT.equals(_currentFolder)) {
            updateVirtualFolders();
        }

        final File targetFolder = _currentFolder;
        if (targetFolder != null) {
            final File toShow = show == null ? _fileToShowAfterNextLoad : show;
            _fileToShowAfterNextLoad = null;

            try {
                executorService.execute(() -> _loadFolder(targetFolder, folderChanged, toShow, navigationDirection));
            } catch (RejectedExecutionException err) { // during exit
                Log.d(GsFileBrowserListAdapter.class.getName(), err.toString());
            }
        }
    }

    // This function is not called on the main thread
    private synchronized void _loadFolder(final File targetFolder, final boolean folderChanged, final @Nullable File toShow, final int navigationDirection) {

        final List<File> newData = new ArrayList<>();

        // Make sure /storage/emulated/0 is browsable, even though filesystem says it's not accessible
        if (targetFolder.equals(new File("/"))) {
            newData.add(VIRTUAL_STORAGE_ROOT);
        } else if (targetFolder.equals(VIRTUAL_STORAGE_ROOT)) {
            newData.addAll(_virtualMapping.keySet());

            // SD Card and other external storage directories that are also not listable
            for (final Pair<File, String> p : GsContextUtils.instance.getAppDataPublicDirs(_context, false, true, false)) {
                File f = p.first;
                while (f.getParentFile() != null && !f.getParentFile().getName().equals("storage")) {
                    f = f.getParentFile();
                }
                newData.add(f);
            }
        } else if (targetFolder.equals(VIRTUAL_STORAGE_EMULATED)) {
            newData.add(new File(targetFolder, "" + _userId));
        } else if (targetFolder.equals(VIRTUAL_STORAGE_RECENTS)) {
            newData.addAll(_dopt.recentFiles);
        } else if (targetFolder.equals(VIRTUAL_STORAGE_POPULAR)) {
            newData.addAll(_dopt.popularFiles);
        } else if (targetFolder.equals(VIRTUAL_STORAGE_FAVOURITE)) {
            newData.addAll(_dopt.favouriteFiles);
        }

        if (targetFolder.isDirectory() && targetFolder.canRead()) {
            GsCollectionUtils.addAll(newData, targetFolder.listFiles());
        }

        GsCollectionUtils.keepIf(newData, this::accept);
        GsCollectionUtils.deduplicate(newData);

        // Don't sort recent or virtual root items - use the default order
        if (isFolderSortable(targetFolder)) {
            GsFileUtils.sortFiles(newData, _dopt.sortOrder);
        }

        // Testing if modtimes have changed (modtimes generally only increase)
        final long modSum = GsCollectionUtils.accumulate(newData, (f, s) -> s + f.lastModified(), 0L);
        final boolean modSumChanged = modSum != _prevModSum;

        final File goUp = getParentForFolder(targetFolder);

        if (folderChanged || modSumChanged || !newData.equals(_adapterData)) {
            final ArrayList<File> filteredData = new ArrayList<>();
            _filter._filter(newData, filteredData);

            _recyclerView.post(() -> {
                // A newer navigation may have happened while this directory was being scanned.
                // Never let an older worker overwrite the current folder.
                if (_currentFolder == null || !targetFolder.equals(_currentFolder)) {
                    return;
                }

                // Modify all these values in the UI thread
                _goUpFile = goUp;
                _adapterData.clear();
                _adapterDataFiltered.clear();
                if (_goUpFile != null) {
                    _adapterData.add(_goUpFile);
                    _adapterDataFiltered.add(_goUpFile);
                }
                _adapterData.addAll(newData);
                _adapterDataFiltered.addAll(filteredData);
                _currentSelection.retainAll(_adapterDataFiltered);
                _prevModSum = modSum;

                if (folderChanged) {
                    _fileIdMap.clear();
                }

                // TODO - add logic to notify the changed bits
                notifyDataSetChanged();

                if (folderChanged) {
                    animateFolderContent(navigationDirection);
                    _recyclerView.post(() -> {
                        if (_layoutManager != null) {
                            _layoutManager.onRestoreInstanceState(_folderScrollMap.remove(_currentFolder));
                        }

                        postScrollToAndFlash(toShow);
                    });
                } else {
                    postScrollToAndFlash(toShow);
                }

                if (_dopt.listener != null) {
                    _dopt.listener.onFsViewerDoUiUpdate(GsFileBrowserListAdapter.this);
                }
            });
        } else {
            if (_currentFolder != null && targetFolder.equals(_currentFolder)) {
                postScrollToAndFlash(toShow);
            }
        }
    }

    public boolean canWrite(final File file) {
        return canWrite(file, _dopt.mountedStorageFolder);
    }

    public static boolean canWrite(final File file, final File mountedStorageFolder) {
        return file != null && (file.canWrite() || file.equals(mountedStorageFolder) || GsFileUtils.isChild(mountedStorageFolder, file));
    }

    public boolean accept(File file) {
        file = resolveVirtualFile(file);
        final boolean isDirectory = GsFileUtils.isDirectory(file);
        final File parent = file.getParentFile();
        final String name = file.getName().toLowerCase();
        final boolean filterYes = isDirectory || _dopt.fileOverallFilter == null || _dopt.fileOverallFilter.callback(_context, file);
        final boolean dotYes = _dopt.sortOrder.showDotFiles || !name.startsWith(".") && !isAccessoryFolder(parent, name, file);
        final boolean selFileYes = _dopt.doSelectFile || isDirectory;
        final boolean textYes = isDirectory || !_dopt.hideNonTextFiles || GsFileUtils.isTextFile(file);
        return filterYes && dotYes && selFileYes && textYes;
    }

    public boolean accept(final File dir, final String filename) {
        return accept(new File(dir, filename));
    }

    private boolean isAccessoryFolder(File dir, String filename, File file) {
        return file.isDirectory() &&
                ((filename.endsWith("_files") && new File(dir, filename.replaceFirst("_files$", ".html")).isFile()) ||
                        (filename.endsWith(".assets") && new File(dir, filename.replaceFirst("\\.assets$", ".md")).isFile()));
    }

    public GsFileBrowserOptions.Options getFsOptions() {
        return _dopt;
    }

    public boolean isCurrentFolderHome() {
        return _currentFolder != null && _dopt.rootFolder != null && _dopt.rootFolder.getAbsolutePath().equals(_currentFolder.getAbsolutePath());
    }

    //########################
    //##
    //## StringFilter
    //##
    //########################
    private static class StringFilter extends Filter {
        private final GsFileBrowserListAdapter _adapter;
        private final List<File> _filteredList;
        public String _lastFilter = "";

        private StringFilter(final GsFileBrowserListAdapter adapter) {
            super();
            _adapter = adapter;
            _filteredList = new ArrayList<>();
        }

        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            final FilterResults results = new FilterResults();

            _lastFilter = constraint.toString().toLowerCase().trim();
            _filter(_adapter._adapterData, _filteredList);

            results.values = _filteredList;
            results.count = _filteredList.size();
            return results;
        }

        public void _filter(final List<File> all, final List<File> filtered) {
            filtered.clear();
            if (_lastFilter.isEmpty()) {
                filtered.addAll(all);
            } else {
                for (final File file : all) {
                    if (file.getName().toLowerCase().contains(_lastFilter)) {
                        filtered.add(file);
                    }
                }
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void publishResults(CharSequence constraint, FilterResults results) {
            _adapter._adapterDataFiltered.clear();
            _adapter._adapterDataFiltered.addAll((ArrayList<File>) results.values);
            _adapter.notifyDataSetChanged();
        }
    }

    @SuppressWarnings({"WeakerAccess", "unused"})
    public static class FilesystemViewerViewHolder extends RecyclerView.ViewHolder {
        //########################
        //## UI Binding
        //########################
        final LinearLayout itemRoot;
        final ImageView image;
        final TextView title;
        final TextView description;

        // Only present in grid mode's layout (opoc_filesystem_item_grid.xml) - null in list/detailed-list.
        // Wraps the icon/cover ImageView so we can round its corners and show a selection highlight
        // behind it without disturbing the image content itself.
        final FrameLayout imageFrame;

        // Original (XML-defined) icon size for this holder's layout, so a cover image's custom
        // size can be reverted to normal when a recycled view is rebound to a non-cover item.
        final int defaultImageWidth;
        final int defaultImageHeight;

        //########################
        //## Methods
        //########################
        FilesystemViewerViewHolder(final View row) {
            super(row);
            itemRoot = row.findViewById(R.id.opoc_filesystem_item__root);
            image = row.findViewById(R.id.opoc_filesystem_item__image);
            title = row.findViewById(R.id.opoc_filesystem_item__title);
            description = row.findViewById(R.id.opoc_filesystem_item__description);
            imageFrame = row.findViewById(R.id.opoc_filesystem_item__image_frame);

            final ViewGroup.LayoutParams ilp = image.getLayoutParams();
            defaultImageWidth = ilp != null ? ilp.width : ViewGroup.LayoutParams.WRAP_CONTENT;
            defaultImageHeight = ilp != null ? ilp.height : ViewGroup.LayoutParams.WRAP_CONTENT;

            // Make the icon/cover corners roundish (grid mode only - imageFrame is null elsewhere).
            // Using an outline + clipToOutline (rather than baking rounding into a drawable) means
            // this works correctly for both the placeholder background AND any bitmap content
            // (folder icon, file icon, or a decoded cover photo) at whatever size is set later.
            if (imageFrame != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                final float density = row.getResources().getDisplayMetrics().density;
                final ViewOutlineProvider provider = new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        final int configuredRadius = (int) (AppSettings.get(view.getContext()).getGridCornerRadiusDp() * density);
                        // Clamp to the actual item size so changing icon/cover dimensions can
                        // never cause the rounded outline to collapse/cut the image away.
                        final float radius = Math.min(configuredRadius, Math.min(view.getWidth(), view.getHeight()) / 2f);
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                    }
                };
                image.setOutlineProvider(provider);
                image.setClipToOutline(true);
                imageFrame.setOutlineProvider(provider);
                imageFrame.setClipToOutline(true);
            }
        }
    }

    public boolean isCurrentFolderVirtual() {
        return isVirtualFolder(_currentFolder);
    }

    // Is the folder a virtual folder - does it contain links or other special items
    public static boolean isVirtualFolder(final File file) {
        return VIRTUAL_STORAGE_RECENTS.equals(file) ||
                VIRTUAL_STORAGE_FAVOURITE.equals(file) ||
                VIRTUAL_STORAGE_POPULAR.equals(file) ||
                VIRTUAL_STORAGE_ROOT.equals(file);
    }

    public void showFileAfterNextLoad(final File file) {
        _fileToShowAfterNextLoad = file;
    }

    private int getUserId() {
        try {
            final String path = Environment.getExternalStorageDirectory().getAbsolutePath();
            final String[] parts = path.split("/");
            return Integer.parseInt(parts[parts.length - 1]);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private boolean isFolderSortable(final File folder) {
        return folder != null && !VIRTUAL_STORAGE_ROOT.equals(folder) && !VIRTUAL_STORAGE_RECENTS.equals(folder);
    }

    private File getParentForFolder(final File folder) {
        if (folder == null) {
            return null;
        }

        final File parent = folder.getParentFile();
        if ((parent != null && parent.canWrite()) || GsFileUtils.isChild(VIRTUAL_STORAGE_ROOT, parent)) {
            return parent;
        }

        if (VIRTUAL_STORAGE_ROOT.equals(parent) || _virtualMapping.containsValue(folder)) {
            return VIRTUAL_STORAGE_ROOT;
        }

        return null;
    }

    public boolean isCurrentFolderSortable() {
        return isFolderSortable(_currentFolder);
    }

    public File resolveVirtualFile(final File file) {
        return GsCollectionUtils.getOrDefault(_virtualMapping, file, file);
    }
}
