/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0 or later.
 * Modified by lavinhoque33, 2026-11-17.
 * Written by lavinhoque33, 2026-11-17.
 */
package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.net.Uri;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowBitmapFactory;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;

import app.morphe.extension.shared.utils.Utils;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.settings.preference.CustomSeekbarThumbPreference;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 26)
public class SeekbarCustomizationTest {
    private Context context;

    @Before
    public void setUp() throws Exception {
        // Match Android's decoder: the default legacy shadow accepts arbitrary bytes as an image.
        ShadowBitmapFactory.setAllowInvalidImageData(false);
        var application = RuntimeEnvironment.getApplication();
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = application.getPackageName();
        packageInfo.versionName = "21.45.90";
        packageInfo.applicationInfo = application.getApplicationInfo();
        Shadows.shadowOf(application.getPackageManager()).installPackage(packageInfo);

        Field contextField = Utils.class.getDeclaredField("context");
        contextField.setAccessible(true);
        contextField.set(null, application);
        context = application;
        CustomSeekbarThumbPatch.resetThumb(context);
        Settings.CUSTOM_SEEKBAR_THUMB.save(false);
    }

    @Test
    public void correctsAccumulatedColumnDriftAndIncludesLastPixel() throws Exception {
        BitmapRegionDecoder decoder = decoder(1600, 900);
        Rect frame = new Rect(9 * 159, 9 * 90, 9 * 159 + 158, 9 * 90 + 89);

        SeekbarThumbnailPreviewPatch.fixStoryboardFrameRect(frame, decoder);

        assertEquals(new Rect(1440, 810, 1600, 900), frame);
        decoder.recycle();
    }

    @Test
    public void handlesPartialLastStoryboardSheet() throws Exception {
        BitmapRegionDecoder decoder = decoder(480, 180);
        Rect frame = new Rect(318, 90, 476, 179);

        SeekbarThumbnailPreviewPatch.fixStoryboardFrameRect(frame, decoder);

        assertEquals(new Rect(320, 90, 480, 180), frame);
        decoder.recycle();
    }

    @Test
    public void importsValidatedImageAndResetRemovesItsPrivateCopy() throws Exception {
        File selected = new File(context.getCacheDir(), "thumb.png");
        try (FileOutputStream out = new FileOutputStream(selected)) {
            Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, out);
        }

        assertTrue(CustomSeekbarThumbPatch.importThumbFile(context, Uri.fromFile(selected)));
        Settings.CUSTOM_SEEKBAR_THUMB_FILE.save("thumb.png");
        File stored = new File(context.getFilesDir(), "morphe_custom_seekbar_thumb");
        assertArrayEquals(Files.readAllBytes(selected.toPath()), Files.readAllBytes(stored.toPath()));

        CustomSeekbarThumbPatch.resetThumb(context);

        assertFalse(stored.exists());
        assertEquals("", Settings.CUSTOM_SEEKBAR_THUMB_FILE.get());
    }

    @Test
    public void rejectsInvalidReplacementWithoutLosingPreviousImage() throws Exception {
        File stored = new File(context.getFilesDir(), "morphe_custom_seekbar_thumb");
        Files.write(stored.toPath(), new byte[]{1, 2, 3});
        File selected = new File(context.getCacheDir(), "invalid.png");
        Files.write(selected.toPath(), new byte[]{4, 5, 6});

        assertFalse(CustomSeekbarThumbPatch.importThumbFile(context, Uri.fromFile(selected)));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(stored.toPath()));
        assertFalse(new File(context.getFilesDir(), "morphe_custom_seekbar_thumb.tmp").exists());
    }

    @Test
    public void cancellationLeavesSavedSelectionUntouched() {
        Settings.CUSTOM_SEEKBAR_THUMB_FILE.save("saved.gif");

        CustomSeekbarThumbPreference.handleActivityResult(context, android.app.Activity.RESULT_CANCELED, null);

        assertEquals("saved.gif", Settings.CUSTOM_SEEKBAR_THUMB_FILE.get());
    }

    private static BitmapRegionDecoder decoder(int width, int height) throws Exception {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, encoded);
        byte[] image = encoded.toByteArray();
        return BitmapRegionDecoder.newInstance(image, 0, image.length, false);
    }
}
