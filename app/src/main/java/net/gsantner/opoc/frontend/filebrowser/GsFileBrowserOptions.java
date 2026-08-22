/*#######################################################
 *
 * SPDX-FileCopyrightText: 2017-2025 Gregor Santner <gsantner AT mailbox DOT org>
 * SPDX-License-Identifier: Unlicense OR CC0-1.0
 *
 * Written 2017-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 * To the extent possible under law, the author(s) have dedicated all copyright and related and neighboring rights to this software to the public domain worldwide. This software is distributed without any warranty.
 * You should have received a copy of the CC0 Public Domain Dedication along with this software. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
#########################################################*/
package net.gsantner.opoc.frontend.filebrowser;

import android.content.Context;
import android.content.DialogInterface;
import android.os.Environment;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

import net.gsantner.opoc.util.GsFileUtils;
import net.gsantner.opoc.wrapper.GsCallback;

import java.io.File;
import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@SuppressWarnings({"unused", "WeakerAccess"})
public class GsFileBrowserOptions {

    // How the current folder's contents are displayed
    public enum FileBrowserViewMode {
        LIST, DETAILED_LIST, GRID;

        public static FileBrowserViewMode fromString(final String value) {
            if (value != null) {
                for (final FileBrowserViewMode mode : values()) {
                    if (mode.name().equals(value)) {
                        return mode;
                    }
                }
            }
            return DETAILED_LIST;
        }
    }

    public interface SelectionListener {
        void onFsViewerSelected(final String request, final File file, final Integer lineNumber);

        void onFsViewerMultiSelected(final String request, final File... files);

        void onFsViewerCancel(final String request);

        void onFsViewerConfig(final Options dopt);

        void onFsViewerDoUiUpdate(final GsFileBrowserListAdapter adapter);

        void onFsViewerItemLongPressed(final File file, boolean doSelectMultiple);

        void onFsViewerFolderLoad(final File newFolder);

        void onFsViewerNeutralButtonPressed(final File currentFolder);
    }

    public static class Options {
        public SelectionListener listener = new SelectionListenerAdapter();
        public File
                rootFolder = Environment.getExternalStorageDirectory(),
                mountedStorageFolder = null,
                startFolder = null;
        public String requestId = "show_dialog";

        public String descriptionFormat = null;

        // Dialog type
        public boolean
                doSelectFolder = true,
                doSelectFile = false,
                doSelectMultiple = false;

        public boolean descModtimeInsteadOfParent = false;

        // When true, navigation is confined to rootFolder and its subfolders: the "go up" row
        // never appears at rootFolder, any attempt to navigate above it is redirected back to it,
        // and browser chrome for jumping to arbitrary filesystem locations (the "Go to /storage"
        // menu action, virtual storage-root shortcuts) is hidden. Used by the Notebook tab so it
        // behaves as a dedicated workspace rooted at one folder rather than a general file
        // manager. Defaults to false so every other caller of this shared browser (move/copy
        // dialogs, attach-file pickers, the notebook-root folder picker itself, etc.) keeps
        // today's unrestricted browsing behavior unless it explicitly opts in.
        public boolean confineToRootFolder = false;

        // Dedicated Book/Files presentation modes. These are opt-in so the shared browser
        // remains backwards compatible for dialogs and other callers.
        public boolean bookMode = false;
        public boolean requestBookOptions = false;
        public boolean onlyShowDirectories = false;
        public boolean hideIconsInList = false;
        public boolean useCustomFileFolderImages = false;

        public int itemSidePadding = 16; // dp

        // Main notebook list can omit the generic folder/choose-directory icon. Grid mode keeps
        // folder icons because they are part of the visual grid.
        public boolean hideGenericFolderIconInList = false;

        // Visibility of elements
        public boolean
                utilsBarEnable = true,
                searchEnable = true,
                upButtonEnable = true,
                homeButtonEnable = true,
                cancelButtonEnable = true,
                okButtonEnable = true,
                newDirButtonEnable = true,
                dismissAfterCallback = true;

        public GsFileUtils.SortOrder sortOrder = new GsFileUtils.SortOrder();
        public boolean hideNonTextFiles = false;

        public FileBrowserViewMode viewMode = FileBrowserViewMode.DETAILED_LIST;
        public boolean viewModeIsFolderLocal = false;
        public int gridColumns = 3;

        public GsCallback.b2<Context, File> fileOverallFilter = (context, file) -> true;

        @StringRes
        public int cancelButtonText = android.R.string.cancel;
        @StringRes
        public int okButtonText = android.R.string.ok;
        @StringRes
        public int neutralButtonText = 0;
        @StringRes
        public int titleText = android.R.string.untitled;
        @StringRes
        public int searchHint = android.R.string.search_go;
        @StringRes
        public int contentDescriptionFolder = 0;
        @StringRes
        public int contentDescriptionSelected = 0;
        @StringRes
        public int contentDescriptionFile = 0;
        @StringRes
        public int newDirButtonText = 0;
        @DrawableRes
        public int homeButtonImage = android.R.drawable.star_big_on;
        @DrawableRes
        public int searchButtonImage = android.R.drawable.ic_menu_search;
        @DrawableRes
        public int newDirButtonImage = android.R.drawable.ic_menu_add;
        @DrawableRes
        public int folderImage = android.R.drawable.ic_menu_view;
        @DrawableRes
        public int selectedItemImage = android.R.drawable.checkbox_on_background;
        @DrawableRes
        public int fileImage = android.R.drawable.ic_menu_edit;

        @ColorRes
        public int backgroundColor = android.R.color.background_light;
        @ColorRes
        public int primaryColor = 0;
        @ColorRes
        public int accentColor = 0;
        @ColorRes
        public int primaryTextColor = 0;
        @ColorRes
        public int secondaryTextColor = 0;
        @ColorRes
        public int titleTextColor = 0;
        @ColorRes
        public int fileColor = 0;
        @ColorRes
        public int folderColor = 0;

        public final Map<File, File> storageMaps = new LinkedHashMap<>();
        public final Map<File, Integer> iconMaps = new HashMap<>();
        public Collection<File> favouriteFiles, recentFiles, popularFiles = null;
        public GsCallback.a1<CharSequence> setTitle = null, setSubtitle = null;

        public void addVirtualFile(final String name, final File target, final int icon) {
            final File file = new File(GsFileBrowserListAdapter.VIRTUAL_STORAGE_ROOT, name);
            storageMaps.put(file, target);
            iconMaps.put(file, icon);
        }

        public DialogInterface dialogInterface = null;
    }


    public static class SelectionListenerAdapter implements SelectionListener, Serializable {
        @Override
        public void onFsViewerSelected(String request, File file, final Integer lineNumber) {
        }

        @Override
        public void onFsViewerMultiSelected(String request, File... files) {
        }

        @Override
        public void onFsViewerCancel(String request) {
        }

        @Override
        public void onFsViewerConfig(Options dopt) {
        }

        @Override
        public void onFsViewerDoUiUpdate(GsFileBrowserListAdapter adapter) {
        }

        @Override
        public void onFsViewerItemLongPressed(File file, boolean doSelectMultiple) {
        }

        @Override
        public void onFsViewerFolderLoad(File newFolder) {
        }

        @Override
        public void onFsViewerNeutralButtonPressed(File currentFolder) {
        }
    }
}
