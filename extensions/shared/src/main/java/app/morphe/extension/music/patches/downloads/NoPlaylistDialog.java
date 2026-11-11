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
import android.text.TextUtils;
import android.util.Pair;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.utils.BaseThemeUtils;
import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/**
 * The list of No Playlist: the songs that have a stream copy and belong to no downloaded playlist or album. It is
 * our own screen, as the songs list has no page of the app to open, online or offline. A tap plays the list in its
 * shown order, from the tapped song, from the copies.
 */
final class NoPlaylistDialog {
    private static final int ROW_HEIGHT_DP = 64;
    private static final int COVER_DP = 48;
    private static final int COVER_PIXELS = 96;
    private static final int MAX_HEIGHT_PERCENT = 60;

    private NoPlaylistDialog() {}

    /** Shows the list. Main thread; the songs and their covers are read in the background first. */
    static void show() {
        Utils.runOnBackgroundThread(() -> {
            try {
                List<OfflineWatchNext.Item> songs = OfflineReplay.queueOf(OfflineReplay.SONGS);
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
            Pair<Dialog, LinearLayout> created = CustomDialog.create(activity,
                    str("morphe_music_downloads_no_playlist"), null, null, null, () -> { }, null, null, null, true);
            Dialog dialog = created.first;
            ListView list = listOf(activity, songs, covers, dialog);
            // After the title, before the button.
            created.second.addView(list, 1);
            dialog.show();
        } catch (WindowManager.BadTokenException ex) {
            Logger.printException(() -> "Could not show the No Playlist list", ex);
        }
    }

    private static ListView listOf(Context context, List<OfflineWatchNext.Item> songs, Map<String, Bitmap> covers,
                                   Dialog dialog) {
        ListView list = new ListView(context);
        list.setDivider(null);
        list.setAdapter(new SongsAdapter(context, songs, covers));
        list.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            if (!OfflineReplay.play(OfflineReplay.SONGS, position)) {
                Utils.showToastShort(str("morphe_music_offline_library_not_ready"));
            }
        });

        int wanted = Utils.dipToPixels(ROW_HEIGHT_DP) * songs.size();
        int height = Math.min(wanted, Dim.getScreenHeight() * MAX_HEIGHT_PERCENT / 100);
        list.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
        return list;
    }

    /** One row per song: its cover, its title, and its artist with its length. */
    private static final class SongsAdapter extends BaseAdapter {
        private final Context context;
        private final List<OfflineWatchNext.Item> songs;
        private final Map<String, Bitmap> covers;

        SongsAdapter(Context context, List<OfflineWatchNext.Item> songs, Map<String, Bitmap> covers) {
            this.context = context;
            this.songs = songs;
            this.covers = covers;
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
            return row;
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
