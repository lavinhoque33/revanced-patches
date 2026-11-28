/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-27.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.net.Uri;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.shared.utils.Utils;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class RawAudioStoreTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void nativeTrackRemovalLeavesIssuedStreamOnlyTokenUsable() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        File bytes = folder.newFile();
        Files.write(bytes.toPath(), new byte[]{0x1a, 0x45, (byte) 0xdf, (byte) 0xa3, 1, 2, 3, 4});

        var run = new FutureTask<Void>(() -> {
            String id = "streamOnly";
            var prepared = RawAudioFiles.prepare(id, bytes, 251, "audio/webm; codecs=\"opus\"", 8, 123, null, true);
            assertTrue(RawAudioStore.retain(prepared));
            var copy = RawAudioStore.find(id);
            assertNotNull(copy);
            var source = StreamSwapSources.issue(id, copy, "");
            assertNotNull(source);
            Uri token = Uri.parse(source.token);
            assertTrue(StreamSwapDataSource.isAvailableToken(token));

            RawAudioStore.deleteWithNativeTrack(id);

            assertTrue(copy.file().isFile());
            assertTrue(StreamSwapDataSource.isAvailableToken(token));
            return null;
        });
        Thread worker = new Thread(run, "raw-audio-store-test");
        worker.setDaemon(true);
        worker.start();
        run.get(10, TimeUnit.SECONDS);
    }
}
