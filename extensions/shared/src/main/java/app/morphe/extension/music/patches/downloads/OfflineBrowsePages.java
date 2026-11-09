/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-08.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/**
 * The pages of the downloaded lists as the app received them: the browse response of every playlist and album page
 * a downloaded list was opened with while online, one compressed file per list, replaced by each later online open.
 * With no network the app answers the browse of such a list with the recorded response instead of an error, so the
 * list opens as the page it is online. {@link #has} is in memory; reading and writing belong to a worker thread.
 */
public final class OfflineBrowsePages {
    /** Added by the Downloads patch to the browse result the app's success callback receives. */
    public interface Bytes {
        /** The serialized browse response the result holds. */
        byte[] rvxBrowseBytes();
    }

    private static final String DIRECTORY = "rvx_offline_pages";
    private static final String SUFFIX = ".page";

    private static final Set<String> ids = ConcurrentHashMap.newKeySet();

    private OfflineBrowsePages() {}

    private static File directory() {
        return new File(Utils.getContext().getFilesDir(), DIRECTORY);
    }

    private static File fileOf(String sourceId) {
        return new File(directory(), sourceId + SUFFIX);
    }

    /** Learns which pages are recorded. A worker thread; the answers of {@link #has} are false until it ran. */
    static void load() {
        File[] files = directory().listFiles();
        if (files == null) return;

        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(SUFFIX)) ids.add(name.substring(0, name.length() - SUFFIX.length()));
        }
    }

    /** Whether a page is recorded for the list. In memory, so the main thread may ask; false until loaded. */
    static boolean has(String sourceId) {
        return ids.contains(sourceId);
    }

    /** Records the page of the list, replacing the earlier one. A worker thread. Returns the bytes written. */
    static int write(String sourceId, byte[] response) throws IOException {
        Utils.verifyOffMainThread();
        if (!OfflineBrowsePagesLogic.isFileSafe(sourceId)) throw new IOException("Not a file name: " + sourceId);

        byte[] packed = OfflineBrowsePagesLogic.gzip(response);
        File directory = directory();
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("No pages directory");

        File file = fileOf(sourceId);
        File pending = new File(file.getPath() + ".part");
        try {
            Files.write(pending.toPath(), packed);
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(pending.toPath());
        }
        ids.add(sourceId);
        return packed.length;
    }

    /** The recorded page of the list, or null when there is none or it cannot be read. */
    @Nullable
    static byte[] read(String sourceId) {
        if (!ids.contains(sourceId) || !OfflineBrowsePagesLogic.isFileSafe(sourceId)) return null;

        try {
            return OfflineBrowsePagesLogic.gunzip(Files.readAllBytes(fileOf(sourceId).toPath()));
        } catch (IOException ex) {
            Logger.printException(() -> "OfflineBrowsePages: could not read the page of " + sourceId, ex);
            return null;
        }
    }

    /** Forgets the pages of the lists that are no longer kept. A worker thread. */
    static void retainOnly(Set<String> sourceIds) {
        for (String id : Set.copyOf(ids)) {
            if (sourceIds.contains(id)) continue;

            try {
                Files.deleteIfExists(fileOf(id).toPath());
                ids.remove(id);
            } catch (IOException ex) {
                Logger.printException(() -> "OfflineBrowsePages: could not delete the page of " + id, ex);
            }
        }
    }
}
