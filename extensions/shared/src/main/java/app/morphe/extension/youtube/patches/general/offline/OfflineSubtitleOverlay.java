/*
 * Copyright (C) 2026 lavinhoque33
 *
 * This file is part of the revanced-patches project:
 * https://github.com/anddea/revanced-patches
 *
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-10-31.
 */

package app.morphe.extension.youtube.patches.general.offline;

import static app.morphe.extension.shared.utils.StringRef.str;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.List;

import app.morphe.extension.shared.utils.Utils;
import app.morphe.extension.youtube.settings.Settings;

/** An in-window caption layer, independent of the controls' auto-hide and the SurfaceView lifecycle. */
final class OfflineSubtitleOverlay {
    private static final int[] SIZES = {16, 20, 26, 32};
    private static final int[] COLOURS = {Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN};
    private static final int[] POSITIONS = {Gravity.BOTTOM, Gravity.CENTER_VERTICAL, Gravity.TOP};
    private final Activity activity;
    private final TextView text;
    private final TextView control;
    private final Runnable pauseHide;
    private final Runnable resumeHide;
    private List<OfflineCaptions.Saved> tracks = List.of();
    private String videoKey = "";
    private String rendered = "";
    private int selected;
    private int size = 1;
    private int colour;
    private int position;
    private int background;
    private boolean enabled = true;
    private String language = "";

    OfflineSubtitleOverlay(Activity activity, FrameLayout root, Runnable pauseHide, Runnable resumeHide) {
        this.activity = activity;
        this.pauseHide = pauseHide;
        this.resumeHide = resumeHide;
        readStyle();
        text = new TextView(activity);
        text.setGravity(Gravity.CENTER);
        text.setShadowLayer(Utils.dipToPixels(3), 0, Utils.dipToPixels(1), Color.BLACK);
        text.setPadding(Utils.dipToPixels(8), Utils.dipToPixels(4), Utils.dipToPixels(8), Utils.dipToPixels(4));
        text.setVisibility(View.GONE);
        root.addView(text);
        applyStyle();

        control = new TextView(activity);
        control.setText("CC");
        control.setTextColor(Color.WHITE);
        control.setTextSize(16);
        control.setGravity(Gravity.CENTER);
        control.setBackgroundColor(Color.TRANSPARENT);
        int padding = Utils.dipToPixels(12);
        control.setPadding(padding, padding, padding, padding);
        control.setMinimumWidth(Utils.dipToPixels(48));
        control.setMinimumHeight(Utils.dipToPixels(48));
        control.setContentDescription(str("revanced_offline_subtitles_cc"));
        control.setOnClickListener(v -> showMenu());
        control.setVisibility(View.GONE);
    }

    TextView control() {
        return control;
    }

    void update(OfflineVideo video, long timeMs) {
        String key = video == null ? "" : video.key();
        if (!videoKey.equals(key)) {
            videoKey = key;
            tracks = video == null || video.audioOnly ? List.of() : video.subtitles;
            selected = 0;
            for (int index = 0; index < tracks.size(); index++) {
                if (tracks.get(index).language().equals(language)) {
                    selected = index;
                    break;
                }
            }
            control.setVisibility(tracks.isEmpty() ? View.GONE : View.VISIBLE);
        }
        String next = enabled && !tracks.isEmpty()
                ? OfflineSubtitle.textAt(tracks.get(selected).cues(), timeMs) : "";
        if (!rendered.equals(next)) {
            rendered = next;
            text.setText(next);
            text.setVisibility(next.isEmpty() ? View.GONE : View.VISIBLE);
        }
        control.setAlpha(enabled ? 1f : 0.5f);
    }

    private void showMenu() {
        pauseHide.run();
        int count = tracks.size();
        String[] labels = new String[count + 5];
        labels[0] = str("revanced_offline_subtitles_off");
        for (int index = 0; index < count; index++) {
            OfflineCaptions.Saved track = tracks.get(index);
            labels[index + 1] = track.name() + ("translated".equals(track.kind())
                    ? " · " + str("revanced_offline_subtitles_translated") : "");
        }
        labels[count + 1] = str("revanced_offline_subtitles_size");
        labels[count + 2] = str("revanced_offline_subtitles_colour");
        labels[count + 3] = str("revanced_offline_subtitles_background");
        labels[count + 4] = str("revanced_offline_subtitles_position");
        OfflineUi.showChoices(activity, str("revanced_offline_subtitles_cc"), labels,
                enabled ? selected + 1 : 0, Color.WHITE, which -> {
                    if (which <= count) {
                        enabled = which != 0;
                        if (enabled) {
                            selected = which - 1;
                            language = tracks.get(selected).language();
                        }
                        saveStyle();
                        update(OfflinePlayer.getCurrent(), OfflinePlayer.getPosition());
                        resumeHide.run();
                    } else {
                        showStyleMenu(which - count - 1);
                    }
                });
    }

    private void showStyleMenu(int option) {
        String[] keys = switch (option) {
            case 0 -> new String[]{"small", "medium", "large", "extra_large"};
            case 1 -> new String[]{"white", "yellow", "cyan", "green"};
            case 2 -> new String[]{"background_none", "background_dim"};
            default -> new String[]{"bottom", "middle", "top"};
        };
        String[] labels = new String[keys.length];
        for (int index = 0; index < labels.length; index++) {
            labels[index] = str("revanced_offline_subtitles_" + keys[index]);
        }
        int current = switch (option) {
            case 0 -> size;
            case 1 -> colour;
            case 2 -> background;
            default -> position;
        };
        OfflineUi.showChoices(activity, null, labels, current, Color.WHITE, which -> {
            switch (option) {
                case 0 -> size = which;
                case 1 -> colour = which;
                case 2 -> background = which;
                default -> position = which;
            }
            applyStyle();
            saveStyle();
            resumeHide.run();
        });
    }

    private void applyStyle() {
        text.setTextSize(SIZES[size]);
        text.setTextColor(COLOURS[colour]);
        text.setBackgroundColor(background == 0 ? Color.TRANSPARENT : 0x99000000);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | POSITIONS[position]);
        params.setMargins(Utils.dipToPixels(16), Utils.dipToPixels(80),
                Utils.dipToPixels(16), Utils.dipToPixels(72));
        text.setLayoutParams(params);
    }

    private void readStyle() {
        String[] values = Settings.OFFLINE_SUBTITLES_STYLE.get().split(",", -1);
        if (values.length < 5) return;
        try {
            size = bounded(values[0], SIZES.length, 1);
            colour = bounded(values[1], COLOURS.length, 0);
            position = bounded(values[2], POSITIONS.length, 0);
            background = bounded(values[3], 2, 0);
            enabled = !"0".equals(values[4]);
            if (values.length > 5) language = values[5];
        } catch (NumberFormatException ignored) {
            // Imported settings from another release retain the defaults.
        }
    }

    private static int bounded(String value, int length, int fallback) {
        int parsed = Integer.parseInt(value);
        return parsed >= 0 && parsed < length ? parsed : fallback;
    }

    private void saveStyle() {
        Settings.OFFLINE_SUBTITLES_STYLE.save(size + "," + colour + "," + position + "," + background
                + "," + (enabled ? "1" : "0") + "," + language);
    }
}
