/*
 * Copyright (C) 2026 lavinhoque33
 *
 * This file is part of the revanced-patches project:
 * https://github.com/anddea/revanced-patches
 *
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-10-10.
 */

package app.morphe.extension.youtube.patches.general.offline;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;

import app.morphe.extension.shared.utils.Logger;

/**
 * Stores finished videos in {@code Movies/RVX}. Android 10+ uses MediaStore, which needs no
 * storage permission for files the app inserts itself. Older versions use the app-specific
 * movies directory, which needs no permission either.
 */
@SuppressLint("NewApi")
final class OfflineMediaStore {
    static final String RELATIVE_PATH = Environment.DIRECTORY_MOVIES + "/RVX";
    private static final int COPY_BUFFER = 256 * 1024;

    private OfflineMediaStore() {
    }

    /** @return The uri of the saved video. */
    static Uri save(Context context, File source, String displayName) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH);
            values.put(MediaStore.Video.Media.IS_PENDING, 1);

            Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            deleteStaleRows(context, collection, displayName);
            Uri uri = resolver.insert(collection, values);
            if (uri == null) throw new IOException("MediaStore refused the new video");
            try {
                try (InputStream input = new FileInputStream(source);
                     OutputStream output = resolver.openOutputStream(uri, "w")) {
                    if (output == null) throw new IOException("Could not open the MediaStore item");
                    copy(input, output);
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Video.Media.IS_PENDING, 0);
                resolver.update(uri, done, null, null);
                return uri;
            } catch (IOException | RuntimeException ex) {
                resolver.delete(uri, null, null);
                throw ex;
            }
        }

        File directory = new File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "RVX");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create " + directory);
        }
        File destination = new File(directory, displayName);
        if (!source.renameTo(destination)) {
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = new FileOutputStream(destination)) {
                copy(input, output);
            }
        }
        return Uri.fromFile(destination);
    }

    /**
     * A row whose file was removed behind MediaStore's back (root shell, raw-path file managers)
     * still owns the path, and publishing a new file with the same name then fails.
     */
    private static void deleteStaleRows(Context context, Uri collection, String displayName) {
        ContentResolver resolver = context.getContentResolver();
        try (Cursor cursor = resolver.query(collection,
                new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                new String[]{displayName, RELATIVE_PATH + "/"}, null)) {
            if (cursor == null) return;
            while (cursor.moveToNext()) {
                Uri row = ContentUris.withAppendedId(collection, cursor.getLong(0));
                if (exists(context, row.toString())) continue;
                Logger.printInfo(() -> "Removing the stale MediaStore row " + row);
                resolver.delete(row, null, null);
            }
        } catch (Exception ex) {
            // Not owned by the app (e.g. after a reinstall). Publishing may still fail then.
            Logger.printDebug(() -> "Could not clean up stale rows of " + displayName, ex);
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        output.flush();
    }

    /** Must be called off the main thread. */
    static boolean exists(Context context, String uriString) {
        Uri uri = Uri.parse(uriString);
        if ("file".equals(uri.getScheme())) {
            String path = uri.getPath();
            return path != null && new File(path).isFile();
        }
        // Open the file rather than query the row: deleting with root or raw-path file managers
        // leaves a stale MediaStore row behind.
        try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "r")) {
            return descriptor != null;
        } catch (FileNotFoundException ex) {
            return false;
        } catch (SecurityException ex) {
            // Not readable anymore, but not proven deleted either.
            return true;
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not open " + uriString, ex);
            return false;
        }
    }

    /**
     * Deletes the file. Files the app does not own anymore (e.g. after a reinstall) need the
     * user's consent, which is requested with the platform delete dialog.
     *
     * @return True if the file is gone now, false if a consent dialog was shown instead.
     */
    static boolean delete(Activity activity, String uriString) {
        Uri uri = Uri.parse(uriString);
        if ("file".equals(uri.getScheme())) {
            String path = uri.getPath();
            if (path != null) {
                //noinspection ResultOfMethodCallIgnored
                new File(path).delete();
            }
            return true;
        }
        ContentResolver resolver = activity.getContentResolver();
        try {
            resolver.delete(uri, null, null);
            return true;
        } catch (SecurityException ex) {
            try {
                PendingIntent request = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    request = MediaStore.createDeleteRequest(resolver, Collections.singletonList(uri));
                } else if (ex instanceof RecoverableSecurityException recoverable) {
                    request = recoverable.getUserAction().getActionIntent();
                }
                if (request != null) {
                    activity.startIntentSenderForResult(request.getIntentSender(), 0, null, 0, 0, 0);
                    return false;
                }
            } catch (Exception requestException) {
                Logger.printException(() -> "Could not request deletion of " + uriString, requestException);
            }
            Logger.printException(() -> "Could not delete " + uriString, ex);
            return false;
        }
    }
}
