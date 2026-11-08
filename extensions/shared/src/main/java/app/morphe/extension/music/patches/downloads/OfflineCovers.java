/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-08.
 */
package app.morphe.extension.music.patches.downloads;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.util.LruCache;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/**
 * The covers of the Downloads list: the thumbnail stored with a stream copy, decoded small, with a few kept in
 * memory. The disk read and the decode run in the background; a cover that is not stored stays a blank square.
 */
final class OfflineCovers {
    private static final int CACHED_COVERS = 64;
    private static final int COVER_PIXELS = 192;
    private static final int PLACEHOLDER_COLOR = 0x33808080;

    private static final LruCache<String, Bitmap> cache = new LruCache<>(CACHED_COVERS);
    /** Songs without a stored thumbnail: a scroll does not read the disk for them again. */
    private static final Set<String> missing = ConcurrentHashMap.newKeySet();

    private OfflineCovers() {}

    /** Shows the cover of a song in the view, now when it is in memory, otherwise when it has been decoded. */
    static void bind(ImageView view, @Nullable String videoId) {
        view.setTag(videoId);
        view.setImageDrawable(new ColorDrawable(PLACEHOLDER_COLOR));
        if (videoId == null || missing.contains(videoId)) return;

        Bitmap cached = cache.get(videoId);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }

        Utils.runOnBackgroundThread(() -> {
            Bitmap bitmap = decode(videoId);
            if (bitmap == null) {
                missing.add(videoId);
                return;
            }

            cache.put(videoId, bitmap);
            // A recycled row shows another song by now; its tag says so.
            Utils.runOnMainThread(() -> {
                if (videoId.equals(view.getTag())) view.setImageBitmap(bitmap);
            });
        });
    }

    @Nullable
    private static Bitmap decode(String videoId) {
        try {
            OfflineCopyFiles.Stored stored = OfflineCopyStore.find(videoId);
            byte[] bytes = stored == null ? null : stored.thumbnail();
            if (bytes == null) return null;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;

            options.inJustDecodeBounds = false;
            options.inSampleSize = OfflineLibraryLogic.sampleSize(options.outWidth, options.outHeight, COVER_PIXELS);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (RuntimeException | OutOfMemoryError ex) {
            Logger.printInfo(() -> "OfflineLibrary: no cover for v=" + videoId + " " + ex);
            return null;
        }
    }
}
