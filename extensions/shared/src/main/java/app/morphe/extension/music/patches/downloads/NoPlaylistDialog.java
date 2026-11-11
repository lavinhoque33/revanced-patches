/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-10.
 */
package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.utils.StringRef.str;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import app.morphe.extension.shared.utils.BaseThemeUtils;
import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/**
 * The page of No Playlist: the songs that have a stream copy and belong to no downloaded playlist or album. It is our
 * own full screen page over the Downloads view, as the songs list has no page of the app to open, online or offline;
 * Back returns to the Downloads view. A tap plays the list in its shown order from the tapped song, from the copies:
 * offline as one queue; online as the app's own watch of the song (so radio and lyrics stay) with the rest of the
 * list queued after it, see {@link OnlineQueueCommand}.
 * Every song has a check at the right end, as every stream copy has, and a menu just left of it whose one entry,
 * Remove download, asks the same confirmation as the song menu and takes the row off the list.
 */
final class NoPlaylistDialog {
    private static final int ROW_HEIGHT_DP = 64;
    private static final int COVER_DP = 48;
    private static final int COVER_PIXELS = 96;
    private static final int HEADER_HEIGHT_DP = 56;
    private static final int CHECK_DP = 20;
    private static final int MENU_DP = 40;

    private NoPlaylistDialog() {}

    /** Shows the list. Main thread; the songs and their covers are read in the background first. */
    static void show() {
        Utils.runOnBackgroundThread(() -> {
            try {
                List<OfflineWatchNext.Item> songs = new ArrayList<>(OfflineReplay.queueOf(OfflineReplay.SONGS));
                Map<String, Bitmap> covers = new HashMap<>();
                for (OfflineWatchNext.Item song : songs) {
                    Bitmap cover = coverOf(song.videoId());
                    if (cover != null) covers.put(song.videoId(), cover);
                }
                Utils.runOnMainThread(() -> display(songs, covers));
            } catch (Exception ex) {
                Logger.printException(() -> "NoPlaylistDialog: could not read the songs", ex);
            }
        });
    }

    private static Bitmap coverOf(String videoId) {
        byte[] bytes = OfflineStreamBrowse.thumbnail(videoId);
        if (bytes == null) return null;

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, Math.min(bounds.outWidth, bounds.outHeight) / COVER_PIXELS);
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private static void display(List<OfflineWatchNext.Item> songs, Map<String, Bitmap> covers) {
        Activity activity = Utils.getActivity();
        if (activity == null) {
            Logger.printDebug(() -> "NoPlaylistDialog: no activity to show the list in");
            return;
        }
        try {
            Dialog dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar);
            LinearLayout page = new LinearLayout(activity);
            page.setOrientation(LinearLayout.VERTICAL);
            page.setFitsSystemWindows(true);
            page.setBackgroundColor(BaseThemeUtils.getAppBackgroundColor());
            TextView count = new TextView(activity);
            page.addView(headerOf(activity, count, dialog));
            page.addView(listOf(activity, songs, covers, dialog, count),
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            showCount(count, songs.size());

            dialog.setContentView(page);
            dialog.show();
            Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            }
        } catch (WindowManager.BadTokenException ex) {
            Logger.printException(() -> "Could not show the No Playlist page", ex);
        }
    }

    /** The back arrow, the title and the count of songs. */
    private static LinearLayout headerOf(Context context, TextView count, Dialog dialog) {
        int foreground = BaseThemeUtils.getAppForegroundColor();
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Utils.dipToPixels(HEADER_HEIGHT_DP)));

        TextView back = new TextView(context);
        back.setText("\u2190");
        back.setTextSize(24);
        back.setTextColor(foreground);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(view -> dialog.dismiss());
        header.addView(back, new LinearLayout.LayoutParams(Utils.dipToPixels(HEADER_HEIGHT_DP),
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText(str("morphe_music_downloads_no_playlist"));
        title.setTextSize(20);
        title.setTextColor(foreground);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(title);
        count.setTextSize(13);
        count.setTextColor(foreground & 0xB3FFFFFF);
        texts.addView(count);
        header.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return header;
    }

    private static void showCount(TextView count, int songs) {
        count.setText(songs == 1
                ? str("morphe_music_offline_library_song_one")
                : str("morphe_music_offline_library_songs_count", songs));
    }

    private static ListView listOf(Context context, List<OfflineWatchNext.Item> songs, Map<String, Bitmap> covers,
                                   Dialog dialog, TextView count) {
        ListView list = new ListView(context);
        list.setDivider(null);
        SongsAdapter adapter = new SongsAdapter(context, songs, covers, song -> {
            // The copy is gone: the row goes, the count follows, and an empty list closes.
            songs.remove(song);
            showCount(count, songs.size());
            if (songs.isEmpty()) dialog.dismiss();
            ((SongsAdapter) list.getAdapter()).notifyDataSetChanged();
        });
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            if (!OfflineReplay.play(OfflineReplay.SONGS, position)) {
                Utils.showToastShort(str("morphe_music_offline_library_not_ready"));
            }
        });
        return list;
    }

    /** One row per song: its cover, its title, its artist with its length, its menu and its check. */
    private static final class SongsAdapter extends BaseAdapter {
        private final Context context;
        private final List<OfflineWatchNext.Item> songs;
        private final Map<String, Bitmap> covers;
        private final Consumer<OfflineWatchNext.Item> onRemoved;

        SongsAdapter(Context context, List<OfflineWatchNext.Item> songs, Map<String, Bitmap> covers,
                     Consumer<OfflineWatchNext.Item> onRemoved) {
            this.context = context;
            this.songs = songs;
            this.covers = covers;
            this.onRemoved = onRemoved;
        }

        @Override public int getCount() {
            return songs.size();
        }

        @Override public Object getItem(int position) {
            return songs.get(position);
        }

        @Override public long getItemId(int position) {
            return position;
        }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            OfflineWatchNext.Item song = songs.get(position);
            LinearLayout row = recycled instanceof LinearLayout view ? view : newRow();
            ImageView cover = (ImageView) row.getChildAt(0);
            LinearLayout texts = (LinearLayout) row.getChildAt(1);

            cover.setImageBitmap(covers.get(song.videoId()));
            ((TextView) texts.getChildAt(0)).setText(song.title());
            String subtitle = OfflineLibraryLogic.songSubtitle(song.artist(), song.durationMs());
            ((TextView) texts.getChildAt(1)).setText(subtitle);
            row.getChildAt(2).setOnClickListener(menu -> showMenu(menu, song));
            return row;
        }

        private LinearLayout newRow() {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Utils.dipToPixels(ROW_HEIGHT_DP)));

            int coverSize = Utils.dipToPixels(COVER_DP);
            ImageView cover = new ImageView(context);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            row.addView(cover, new LinearLayout.LayoutParams(coverSize, coverSize));

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(Utils.dipToPixels(12), 0, 0, 0);
            texts.addView(textView(16, true, BaseThemeUtils.getAppForegroundColor()));
            texts.addView(textView(13, false, BaseThemeUtils.getAppForegroundColor() & 0xB3FFFFFF));
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            // The menu is just left of the check, which stays at the right end of the row.
            TextView menu = new TextView(context);
            menu.setText("\u22EE");
            menu.setTextSize(22);
            menu.setTextColor(BaseThemeUtils.getAppForegroundColor());
            menu.setGravity(Gravity.CENTER);
            row.addView(menu, new LinearLayout.LayoutParams(Utils.dipToPixels(MENU_DP),
                    ViewGroup.LayoutParams.MATCH_PARENT));

            // The check of a stream copy sits at the right end of the row.
            Drawable check = DownloadedCheck.createDrawable(context);
            if (check != null) {
                DownloadedCheck.tint(check, BaseThemeUtils.isDarkModeEnabled());
                ImageView mark = new ImageView(context);
                mark.setImageDrawable(check);
                int size = Utils.dipToPixels(CHECK_DP);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
                params.setMarginEnd(Utils.dipToPixels(16));
                row.addView(mark, params);
            }
            return row;
        }

        private void showMenu(View anchor, OfflineWatchNext.Item song) {
            PopupMenu popup = new PopupMenu(context, anchor);
            popup.getMenu().add(str("morphe_music_remove_download"));
            popup.setOnMenuItemClickListener(item -> {
                DownloadRemoval.confirmSong(song.videoId(), () -> onRemoved.accept(song));
                return true;
            });
            popup.show();
        }

        private TextView textView(int sizeSp, boolean bold, int color) {
            TextView view = new TextView(context);
            view.setTextSize(sizeSp);
            view.setTextColor(color);
            view.setSingleLine(true);
            view.setEllipsize(TextUtils.TruncateAt.END);
            if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
            return view;
        }
    }
}
