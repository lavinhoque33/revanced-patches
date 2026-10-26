/*
 * Copyright (C) 2026 lavinhoque33
 *
 * This file is part of the revanced-patches project:
 * https://github.com/anddea/revanced-patches
 *
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-10-10.
 * Modified by lavinhoque33, 2026-10-25.
 */

package app.morphe.extension.youtube.patches.general.offline;

import static app.morphe.extension.shared.utils.StringRef.str;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.text.InputType;
import android.text.style.ForegroundColorSpan;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toolbar;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.shared.utils.BaseThemeUtils;
import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;
import app.morphe.extension.youtube.patches.theme.ThemePatch;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.utils.ThemeUtils;

/**
 * The offline library: downloads in progress first, then the downloaded videos.
 */
@SuppressLint("SetTextI18n")
final class OfflineLibraryScreen implements OfflineScreens.Screen {
    private static final String[] SORTS = {
            OfflineScreens.SORT_NEWEST, OfflineScreens.SORT_OLDEST,
            OfflineScreens.SORT_TITLE, OfflineScreens.SORT_SIZE
    };

    /** Shared between screen instances, so returning to the library does not decode again. */
    private static final LruCache<String, Bitmap> thumbnails = new LruCache<>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final Set<String> thumbnailsLoading = ConcurrentHashMap.newKeySet();

    private final Activity activity;
    private final int foreground;
    private final int secondary;
    private final Adapter adapter = new Adapter();
    private final TextView emptyView;
    private final SearchView searchView;
    private final MenuItem searchItem;
    private final MenuItem cancelAllItem;

    private final List<Object> rows = new ArrayList<>();
    private String query = "";
    private boolean destroyed;

    private final Runnable changeListener = this::reload;

    OfflineLibraryScreen(Activity activity) {
        this.activity = activity;
        ThemePatch.applyToSettingsActivity(activity);
        activity.setTheme(Utils.getResourceIdentifierOrThrow(ThemeUtils.isDarkModeEnabled()
                ? "Theme.YouTube.Settings.Dark"
                : "Theme.YouTube.Settings", "style"));
        BaseThemeUtils.setNavigationBarColor(activity.getWindow());

        foreground = ThemeUtils.getAppForegroundColor();
        secondary = (foreground & 0x00FFFFFF) | 0xAA000000;
        final int background = ThemeUtils.getAppBackgroundColor();

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);
        root.setFitsSystemWindows(true);

        Toolbar toolbar = new Toolbar(activity);
        toolbar.setBackgroundColor(ThemeUtils.getToolbarBackgroundColor());
        toolbar.setNavigationIcon(ThemeUtils.getBackButtonDrawable());
        toolbar.setNavigationContentDescription(str("revanced_settings_navigate_up"));
        toolbar.setNavigationOnClickListener(v -> activity.finish());
        // modified by lavinhoque33, 2026-10-20: the Toolbar's own title view sits lower than the back arrow
        // when the theme gives it a title margin or the font has extra padding (lavinhoque33/revanced-patches#8).
        // Use a plain TextView centered in a toolbar of the same height as the RVX settings toolbar.
        TextView toolbarTitle = new TextView(activity);
        toolbarTitle.setText(str("revanced_offline_library_title"));
        toolbarTitle.setTextColor(foreground);
        toolbarTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        toolbarTitle.setSingleLine();
        toolbarTitle.setEllipsize(TextUtils.TruncateAt.END);
        toolbarTitle.setIncludeFontPadding(false);
        toolbarTitle.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(toolbarTitle, new Toolbar.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL));
        TypedValue actionBarSize = new TypedValue();
        final int toolbarHeight = activity.getTheme().resolveAttribute(
                android.R.attr.actionBarSize, actionBarSize, true)
                ? TypedValue.complexToDimensionPixelSize(actionBarSize.data, activity.getResources().getDisplayMetrics())
                : Utils.dipToPixels(56);
        root.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, toolbarHeight));

        searchView = new SearchView(activity);
        searchView.setQueryHint(str("revanced_offline_library_search"));
        searchView.setMaxWidth(Integer.MAX_VALUE);
        tintSearchView(searchView);
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String text) {
                searchView.clearFocus();
                return true;
            }

            @Override
            public boolean onQueryTextChange(String text) {
                query = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
                reload();
                return true;
            }
        });

        Menu menu = toolbar.getMenu();
        searchItem = menu.add(str("revanced_offline_library_search"));
        searchItem.setIcon(OfflineUi.icon(activity, "search", foreground));
        searchItem.setActionView(searchView);
        searchItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
        searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                searchView.setQuery("", false);
                return true;
            }
        });

        MenuItem sortItem = menu.add(str("revanced_offline_library_sort"));
        sortItem.setIcon(OfflineUi.icon(activity, "sort", foreground));
        sortItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        sortItem.setOnMenuItemClickListener(clicked -> {
            showSortDialog();
            return true;
        });

        // modified by lavinhoque33, 2026-10-23: a playlist queues many downloads, so they can be cancelled together.
        cancelAllItem = menu.add(str("revanced_offline_cancel_all"));
        cancelAllItem.setIcon(OfflineUi.icon(activity, "close", foreground));
        cancelAllItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        cancelAllItem.setVisible(false);
        cancelAllItem.setOnMenuItemClickListener(clicked -> {
            confirmCancelAll();
            return true;
        });

        MenuItem playlistItem = menu.add(str("revanced_offline_batch_menu"));
        playlistItem.setIcon(OfflineUi.icon(activity, "download", foreground));
        playlistItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        playlistItem.setOnMenuItemClickListener(clicked -> {
            askPlaylist();
            return true;
        });

        FrameLayout content = new FrameLayout(activity);
        ListView list = new ListView(activity);
        list.setDivider(null);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Object row = rows.get(position);
            if (row instanceof OfflineVideo video) {
                OfflineScreens.openPlayer(activity, video.key());
            }
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            Object row = rows.get(position);
            if (row instanceof OfflineVideo video) {
                showActions(video);
                return true;
            }
            return false;
        });
        content.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        emptyView = new TextView(activity);
        emptyView.setText(str("revanced_offline_library_empty"));
        emptyView.setTextColor(secondary);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        emptyView.setGravity(Gravity.CENTER);
        final int padding = Utils.dipToPixels(32);
        emptyView.setPadding(padding, padding, padding, padding);
        content.addView(emptyView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        list.setEmptyView(emptyView);

        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        activity.setContentView(root);

        OfflineIndex.addListener(changeListener);
        OfflineDownloader.addListener(changeListener);
        reload();
        verifyFiles();
    }

    private void showSortDialog() {
        String[] labels = new String[SORTS.length];
        for (int i = 0; i < SORTS.length; i++) {
            labels[i] = str("revanced_offline_library_sort_" + SORTS[i].toLowerCase(Locale.ROOT));
        }
        final String currentSort = Settings.OFFLINE_LIBRARY_SORT.get();
        int selected = 0;
        for (int i = 0; i < SORTS.length; i++) {
            if (SORTS[i].equals(currentSort)) selected = i;
        }
        OfflineUi.showChoices(activity, str("revanced_offline_library_sort"), labels, selected, foreground, which -> {
            Settings.OFFLINE_LIBRARY_SORT.save(SORTS[which]);
            reload();
        });
    }

    private void tintSearchView(SearchView view) {
        try {
            int id = activity.getResources().getIdentifier("android:id/search_src_text", null, null);
            TextView text = view.findViewById(id);
            if (text != null) {
                text.setTextColor(foreground);
                text.setHintTextColor(secondary);
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not tint the search view", ex);
        }
    }

    // region data

    private void reload() {
        if (destroyed) return;
        rows.clear();
        List<OfflineDownloader.Task> tasks = OfflineDownloader.getTasks();
        cancelAllItem.setVisible(tasks.size() > 1);
        for (OfflineDownloader.Task task : tasks) {
            if (matches(task.title)) rows.add(task);
        }
        for (OfflineVideo video : OfflineScreens.sorted(OfflineIndex.getAll())) {
            if (matches(video.title) || matches(video.author)) rows.add(video);
        }
        emptyView.setText(query.isEmpty()
                ? str("revanced_offline_library_empty")
                : str("revanced_offline_library_no_results"));
        adapter.notifyDataSetChanged();
    }

    private boolean matches(String text) {
        return query.isEmpty() || text.toLowerCase(Locale.ROOT).contains(query);
    }

    /** Drops entries whose file was deleted outside the app. */
    private void verifyFiles() {
        List<OfflineVideo> videos = OfflineIndex.getAll();
        Utils.runOnBackgroundThread(() -> {
            for (OfflineVideo video : videos) {
                if (!OfflineMediaStore.exists(activity.getApplicationContext(), video.contentUri)) {
                    Logger.printInfo(() -> "File of " + video.key() + " is gone, removing it from the library");
                    Utils.runOnMainThread(() -> OfflinePlayer.closeIfPlaying(video.key()));
                    OfflineIndex.remove(video.key());
                }
            }
        });
    }

    // endregion

    // region actions

    private void showActions(OfflineVideo video) {
        // modified by lavinhoque33, 2026-10-20: a tap opens YouTube's player, so the built-in one gets an entry.
        final boolean builtInEntry = Settings.OFFLINE_USE_YOUTUBE_PLAYER.get();
        final int offset = builtInEntry ? 1 : 0;
        String[] actions = new String[4 + offset];
        if (builtInEntry) actions[0] = str("revanced_offline_action_play_builtin");
        actions[offset] = str("revanced_offline_action_play_background");
        actions[offset + 1] = str("revanced_offline_action_share");
        actions[offset + 2] = str("revanced_offline_action_open_youtube");
        actions[offset + 3] = str("revanced_offline_action_delete");
        Utils.getDialogBuilder(activity)
                .setTitle(video.title)
                .setItems(actions, (dialog, which) -> {
                    if (builtInEntry && which == 0) {
                        OfflineScreens.openBuiltInPlayer(activity, video.key());
                        return;
                    }
                    switch (which - offset) {
                        case 0 -> {
                            OfflinePlayer.play(video, OfflineScreens.order());
                            Utils.showToastShort(str("revanced_offline_playing_background"));
                        }
                        case 1 -> share(video);
                        case 2 -> openOnYouTube(video);
                        case 3 -> confirmDelete(video);
                        default -> {
                        }
                    }
                })
                .show();
    }

    private void share(OfflineVideo video) {
        Uri uri = Uri.parse(video.contentUri);
        if ("file".equals(uri.getScheme())) {
            // Sharing file uris crashes the receiver on Android 7+, and the app has no FileProvider.
            Utils.showToastLong(str("revanced_offline_share_unsupported"));
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND)
                .setType(OfflineMediaStore.mimeType(video.displayName)) // modified by lavinhoque33, 2026-10-14: WebM downloads
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, video.title)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(Intent.createChooser(intent, video.title));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not share " + video.videoId, ex);
        }
    }

    private void openOnYouTube(OfflineVideo video) {
        Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/watch?v=" + video.videoId))
                .setPackage(activity.getPackageName());
        try {
            activity.startActivity(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not open " + video.videoId, ex);
        }
    }

    private void confirmDelete(OfflineVideo video) {
        Utils.getDialogBuilder(activity)
                .setTitle(str("revanced_offline_delete_title"))
                .setMessage(str("revanced_offline_delete_message", video.title))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    OfflinePlayer.closeIfPlaying(video.key());
                    if (OfflineMediaStore.delete(activity, video.contentUri)) {
                        Logger.printInfo(() -> "Deleted " + video.key());
                        OfflineIndex.remove(video.key());
                    }
                    // Otherwise the system asks for consent, and the next resume drops the entry
                    // if the user agreed.
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmCancelAll() {
        Utils.getDialogBuilder(activity)
                .setTitle(str("revanced_offline_cancel_all"))
                .setMessage(str("revanced_offline_cancel_all_message", OfflineDownloader.getTasks().size()))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> OfflineDownloader.cancelAll(false))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** YouTube shows its own playlist download button only to some accounts, so the library offers a way in. */
    private void askPlaylist() {
        AlertDialog.Builder builder = Utils.getDialogBuilder(activity);
        // The dialog has its own theme, which can differ from the screen's, so its colors are not set here.
        EditText input = new EditText(builder.getContext());
        input.setHint(str("revanced_offline_batch_input_hint"));
        input.setSingleLine();
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        try {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
            CharSequence copied = clip == null || clip.getItemCount() == 0
                    ? null : clip.getItemAt(0).coerceToText(activity);
            if (copied != null && OfflinePlaylist.parseId(copied.toString()) != null) {
                input.setText(copied.toString().trim());
                input.selectAll();
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not read the clipboard", ex);
        }
        final int padding = Utils.dipToPixels(24);
        FrameLayout container = new FrameLayout(builder.getContext());
        container.setPadding(padding, Utils.dipToPixels(8), padding, 0);
        container.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        builder
                .setTitle(str("revanced_offline_batch_menu"))
                .setMessage(str("revanced_offline_batch_input_message"))
                .setView(container)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String playlistId = OfflinePlaylist.parseId(input.getText().toString());
                    if (playlistId == null) {
                        Utils.showToastLong(str("revanced_offline_batch_input_invalid"));
                    } else {
                        OfflineDownloader.downloadPlaylist(activity, playlistId);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // endregion

    // region lifecycle

    @Override
    public boolean onBackPressed() {
        if (searchItem.isActionViewExpanded()) {
            searchItem.collapseActionView();
            return true;
        }
        return false;
    }

    @Override
    public void onResume() {
        reload();
        verifyFiles();
    }

    @Override
    public void onPause() {
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        OfflineIndex.removeListener(changeListener);
        OfflineDownloader.removeListener(changeListener);
    }

    // endregion

    // region rows

    static String formatDuration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        return hours > 0
                ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
                : String.format(Locale.US, "%d:%02d", minutes, secs);
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position) instanceof OfflineDownloader.Task ? 0 : 1;
        }

        @Override
        public View getView(int position, @Nullable View convertView, ViewGroup parent) {
            Object row = rows.get(position);
            if (row instanceof OfflineDownloader.Task task) {
                TaskRow view = convertView instanceof TaskRow existing ? existing : new TaskRow();
                view.bind(task);
                return view;
            }
            VideoRow view = convertView instanceof VideoRow existing ? existing : new VideoRow();
            view.bind((OfflineVideo) row);
            return view;
        }
    }

    private TextView text(float sizeSp, int color, int maxLines) {
        TextView view = new TextView(activity);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        view.setMaxLines(maxLines);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private final class TaskRow extends LinearLayout {
        private final TextView title = text(15, foreground, 2);
        private final TextView status = text(13, secondary, 1);
        private final ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        private String taskKey;

        TaskRow() {
            super(activity);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            final int padding = Utils.dipToPixels(16);
            setPadding(padding, padding / 2, padding / 2, padding / 2);

            LinearLayout texts = new LinearLayout(activity);
            texts.setOrientation(VERTICAL);
            texts.addView(title);
            texts.addView(progress, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            texts.addView(status);
            addView(texts, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            ImageButton cancel = new ImageButton(activity);
            cancel.setImageDrawable(OfflineUi.icon(activity, "close", foreground));
            cancel.setBackgroundColor(Color.TRANSPARENT);
            cancel.setContentDescription(str("revanced_offline_cancel"));
            cancel.setOnClickListener(v -> {
                if (taskKey != null) OfflineDownloader.cancel(taskKey);
            });
            addView(cancel, new LayoutParams(Utils.dipToPixels(48), Utils.dipToPixels(48)));
        }

        void bind(OfflineDownloader.Task task) {
            taskKey = task.key;
            title.setText(task.title);
            final int percent = task.getPercent();
            final boolean determinate = task.getState() == OfflineDownloader.State.DOWNLOADING && percent >= 0;
            progress.setIndeterminate(!determinate);
            if (determinate) progress.setProgress(percent);
            String state = switch (task.getState()) {
                case QUEUED -> str("revanced_offline_download_queued");
                case MERGING -> str("revanced_offline_download_merging");
                case SAVING -> str("revanced_offline_download_saving");
                default -> percent < 0
                        ? str("revanced_offline_download_starting")
                        : str("revanced_offline_download_progress", percent);
            };
            List<String> parts = new ArrayList<>();
            if (task.batch != null) parts.add(task.index + "/" + task.batch.total);
            if (!task.qualityLabel.isEmpty()) parts.add(task.qualityLabel);
            parts.add(state);
            status.setText(TextUtils.join(" · ", parts));
        }
    }

    private final class VideoRow extends LinearLayout {
        private final ImageView thumbnail = new ImageView(activity);
        private final TextView duration = text(12, Color.WHITE, 1);
        private final TextView title = text(15, foreground, 2);
        private final TextView channel = text(13, secondary, 1);
        private final TextView details = text(13, secondary, 2);
        private String videoKey;

        VideoRow() {
            super(activity);
            setOrientation(HORIZONTAL);
            final int padding = Utils.dipToPixels(12);
            setPadding(Utils.dipToPixels(16), padding, Utils.dipToPixels(16), padding);

            FrameLayout thumbnailFrame = new FrameLayout(activity);
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnail.setClipToOutline(true);
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(Utils.dipToPixels(8));
            shape.setColor(0x33808080);
            thumbnail.setBackground(shape);
            thumbnailFrame.addView(thumbnail, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            GradientDrawable badge = new GradientDrawable();
            badge.setCornerRadius(Utils.dipToPixels(4));
            badge.setColor(0xCC000000);
            duration.setBackground(badge);
            final int badgePadding = Utils.dipToPixels(4);
            duration.setPadding(badgePadding, 0, badgePadding, 0);
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
            badgeParams.setMargins(badgePadding, badgePadding, badgePadding, badgePadding);
            thumbnailFrame.addView(duration, badgeParams);
            addView(thumbnailFrame, new LayoutParams(Utils.dipToPixels(160), Utils.dipToPixels(90)));

            LinearLayout texts = new LinearLayout(activity);
            texts.setOrientation(VERTICAL);
            texts.setPadding(padding, 0, 0, 0);
            texts.addView(title);
            texts.addView(channel);
            texts.addView(details);
            addView(texts, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        void bind(OfflineVideo video) {
            videoKey = video.key();
            title.setText(video.title);
            channel.setText(video.author);
            channel.setVisibility(video.author.isEmpty() ? GONE : VISIBLE);
            details.setText(describe(video));
            duration.setText(formatDuration(video.lengthMs));
            duration.setVisibility(video.lengthMs > 0 ? VISIBLE : GONE);
            loadThumbnail(video);
        }

        /**
         * The meta line of the card: the quality and the size, then the technical details in the
         * accent color. The resume position gets a line of its own.
         */
        private CharSequence describe(OfflineVideo video) {
            StringBuilder head = new StringBuilder();
            appendPart(head, video.audioOnly ? audioCodecName(video) : video.qualityLabel);
            if (video.sizeBytes > 0) {
                appendPart(head, Formatter.formatShortFileSize(activity, video.sizeBytes));
            }

            SpannableStringBuilder text = new SpannableStringBuilder(head);
            String technical = technicalDetails(video);
            if (!technical.isEmpty()) {
                final int start = text.length();
                if (start > 0) text.append(" · ");
                text.append(technical);
                text.setSpan(new ForegroundColorSpan(technicalColor()), start, text.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            if (video.lastPositionMs > 0 && video.lengthMs > 0) {
                if (text.length() > 0) text.append('\n');
                text.append(str("revanced_offline_library_resume", formatDuration(video.lastPositionMs)));
            }
            return text;
        }

        /**
         * The codec and the format id of a video, or the bitrate and the format id of an audio
         * download. Entries made before these were stored show nothing here.
         */
        private String technicalDetails(OfflineVideo video) {
            if (video.codec.isEmpty()) return "";

            List<String> parts = new ArrayList<>(2);
            if (video.audioOnly) {
                if (video.bitrateKbps > 0) parts.add(str("revanced_offline_library_bitrate", video.bitrateKbps));
            } else {
                parts.add(video.codec);
            }
            if (video.itag > 0) parts.add(str("revanced_offline_picker_format_id", video.itag));
            return TextUtils.join(" · ", parts);
        }

        /** {@code Opus} and {@code M4A} instead of the {@code Opus audio} label of the picker. */
        private String audioCodecName(OfflineVideo video) {
            if (OfflineFormats.CODEC_OPUS.equals(video.codec)) {
                return str("revanced_offline_picker_audio_opus");
            }
            if (OfflineFormats.CODEC_AAC.equals(video.codec)) {
                return str("revanced_offline_picker_audio_m4a");
            }
            return video.qualityLabel;
        }

        private void appendPart(StringBuilder text, String part) {
            if (part.isEmpty()) return;
            if (text.length() > 0) text.append(" · ");
            text.append(part);
        }

        /** Readable on both the dark and the light settings theme. */
        private int technicalColor() {
            return BaseThemeUtils.isDarkModeEnabled() ? 0xFF81C784 : 0xFF2E7D32;
        }

        private void loadThumbnail(OfflineVideo video) {
            final String key = video.key();
            Bitmap cached = thumbnails.get(key);
            thumbnail.setImageBitmap(cached);
            if (cached != null || video.thumbnailPath.isEmpty() || !thumbnailsLoading.add(key)) {
                return;
            }
            Utils.runOnBackgroundThread(() -> {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 2;
                Bitmap bitmap = BitmapFactory.decodeFile(video.thumbnailPath, options);
                thumbnailsLoading.remove(key);
                if (bitmap == null) return;
                thumbnails.put(key, bitmap);
                Utils.runOnMainThread(() -> {
                    if (key.equals(videoKey)) thumbnail.setImageBitmap(bitmap);
                });
            });
        }
    }

}
