/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-08.
 */
package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.utils.StringRef.str;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.List;

import app.morphe.extension.shared.utils.BaseThemeUtils;
import app.morphe.extension.shared.utils.Utils;

/**
 * The full-screen Downloads list: a toolbar with a back arrow and a title above a list of headers, covers with two
 * lines of text, and plain rows, in the app's own theme colors. It only draws what it is given; every action is a
 * callback of an item.
 */
final class OfflineLibraryScreen {
    /** One row. {@code onClick} is null for a row that does nothing; {@code dimmed} greys it out. */
    record Item(Item.Kind kind, String title, String subtitle, @Nullable String coverVideoId, boolean dimmed,
                @Nullable Runnable onClick) {
        enum Kind { HEADER, ENTRY, PLAIN }

        static Item header(String title) {
            return new Item(Kind.HEADER, title, "", null, false, null);
        }

        /** A row with a cover: {@code coverVideoId} names the song whose stored thumbnail it shows, if any. */
        static Item entry(String title, String subtitle, @Nullable String coverVideoId, boolean dimmed,
                          @Nullable Runnable onClick) {
            return new Item(Kind.ENTRY, title, subtitle, coverVideoId, dimmed, onClick);
        }

        /** A row of text only. */
        static Item plain(String title, boolean dimmed, @Nullable Runnable onClick) {
            return new Item(Kind.PLAIN, title, "", null, dimmed, onClick);
        }
    }

    private static final float DIMMED_ALPHA = 0.5f;
    private static final int SECONDARY_TEXT_ALPHA = 0xB3;

    private final Activity activity;
    private final Dialog dialog;
    private final TextView title;
    private final ItemAdapter adapter = new ItemAdapter();
    private final ListView list;
    private final int foreground = BaseThemeUtils.getAppForegroundColor();
    private final int secondary = (foreground & 0x00FFFFFF) | (SECONDARY_TEXT_ALPHA << 24);
    private final int selectableBackground;

    /** {@code onBack} runs for the toolbar arrow and the system back; it decides between a view and closing. */
    OfflineLibraryScreen(Activity activity, Runnable onBack) {
        this.activity = activity;
        this.selectableBackground = resolveSelectableBackground(activity);
        int background = BaseThemeUtils.getAppBackgroundColor();

        dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar) {
            @Override
            public void onBackPressed() {
                onBack.run();
            }
        };
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(background));
            window.setStatusBarColor(background);
            window.setNavigationBarColor(background);
            if (!BaseThemeUtils.isDarkModeEnabled()) {
                window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
        }

        title = new TextView(activity);
        list = new ListView(activity);
        list.setDivider(null);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Runnable action = adapter.items.get(position).onClick();
            if (action != null) action.run();
        });

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.addView(createToolbar(onBack), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dialog.setContentView(root);
    }

    void show() {
        dialog.show();
    }

    void dismiss() {
        dialog.dismiss();
    }

    /** Replaces the title and the rows, and shows the list from its top. */
    void setContent(String text, List<Item> items) {
        title.setText(text);
        adapter.items = items;
        adapter.notifyDataSetChanged();
        list.setSelection(0);
    }

    private LinearLayout createToolbar(Runnable onBack) {
        ImageButton back = new ImageButton(activity);
        back.setImageDrawable(BaseThemeUtils.getBackButtonDrawable());
        back.setBackgroundResource(selectableBackground);
        back.setContentDescription(str("morphe_music_offline_library_back"));
        back.setOnClickListener(view -> onBack.run());

        title.setTextColor(foreground);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);

        LinearLayout toolbar = new LinearLayout(activity);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.MATCH_PARENT));
        toolbar.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return toolbar;
    }

    private static int resolveSelectableBackground(Activity activity) {
        TypedArray attributes = activity.obtainStyledAttributes(new int[]{android.R.attr.selectableItemBackground});
        try {
            return attributes.getResourceId(0, 0);
        } finally {
            attributes.recycle();
        }
    }

    private static int dp(int value) {
        return Utils.dipToPixels(value);
    }

    private final class ItemAdapter extends BaseAdapter {
        private final Item.Kind[] kinds = Item.Kind.values();
        List<Item> items = List.of();

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Item getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return kinds.length;
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).kind().ordinal();
        }

        @Override
        public boolean isEnabled(int position) {
            return items.get(position).onClick() != null;
        }

        @Override
        public View getView(int position, @Nullable View convertView, ViewGroup parent) {
            Item item = items.get(position);
            View view = convertView != null ? convertView : createRow(item.kind());
            Row row = (Row) view.getTag();

            row.title.setText(item.title());
            if (item.kind() == Item.Kind.ENTRY) {
                row.subtitle.setText(item.subtitle());
                row.subtitle.setVisibility(item.subtitle().isEmpty() ? View.GONE : View.VISIBLE);
                OfflineCovers.bind(row.cover, item.coverVideoId());
            }
            view.setAlpha(item.dimmed() ? DIMMED_ALPHA : 1f);
            return view;
        }

        private View createRow(Item.Kind kind) {
            Row row = new Row();
            LinearLayout view = new LinearLayout(activity);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setTag(row);

            row.title = new TextView(activity);
            row.title.setTextColor(foreground);
            row.title.setSingleLine();
            row.title.setEllipsize(TextUtils.TruncateAt.END);

            if (kind == Item.Kind.HEADER) {
                row.title.setTextSize(14);
                row.title.setTypeface(Typeface.DEFAULT_BOLD);
                row.title.setTextColor(secondary);
                view.setPadding(dp(16), dp(20), dp(16), dp(8));
                view.addView(row.title);
                return view;
            }

            view.setBackgroundResource(selectableBackground);
            view.setMinimumHeight(dp(64));
            view.setPadding(dp(16), dp(8), dp(16), dp(8));
            row.title.setTextSize(16);

            LinearLayout texts = new LinearLayout(activity);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(row.title);
            if (kind == Item.Kind.ENTRY) {
                row.cover = new ImageView(activity);
                row.cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                view.addView(row.cover, new LinearLayout.LayoutParams(dp(48), dp(48)));

                row.subtitle = new TextView(activity);
                row.subtitle.setTextColor(secondary);
                row.subtitle.setTextSize(13);
                row.subtitle.setSingleLine();
                row.subtitle.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(row.subtitle);
            }
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            textParams.leftMargin = kind == Item.Kind.ENTRY ? dp(16) : 0;
            view.addView(texts, textParams);
            return view;
        }
    }

    /** The views of a row, found again when the list recycles it. */
    private static final class Row {
        TextView title;
        TextView subtitle;
        ImageView cover;
    }
}
