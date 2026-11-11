/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-10.
 */
package app.morphe.extension.music.patches.flyout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.CodedOutputStream;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class FlyoutMenuScanTest {
    private static final String SONG = "dQw4w9WgXcQ";
    private static final String OTHER = "lYBUbBu4W08";

    private static byte[] message(int field, byte[]... parts) {
        try {
            ByteArrayOutputStream inner = new ByteArrayOutputStream();
            for (byte[] part : parts) inner.write(part);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            CodedOutputStream out = CodedOutputStream.newInstance(bytes);
            out.writeByteArray(field, inner.toByteArray());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }

    private static byte[] string(int field, String value) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            CodedOutputStream out = CodedOutputStream.newInstance(bytes);
            out.writeString(field, value);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }

    /** An item of the given renderer whose command (field 3) is the given endpoint. */
    private static byte[] item(int renderer, byte[] endpoint) {
        return message(renderer, message(3, endpoint));
    }

    private static byte[] queueAdd(int renderer, String id) {
        return item(renderer, message(163162354, message(1, string(1, id))));
    }

    private static byte[] startMix(int renderer, String id) {
        return item(renderer, message(48687757, string(1, id)));
    }

    private static byte[] credits(int renderer, String browseId) {
        return item(renderer, message(48687626, string(2, browseId)));
    }

    private static FlyoutMenuScan.Result scan(byte[]... items) {
        return FlyoutMenuScan.scan(Arrays.asList(items));
    }

    @Test
    public void ordinaryQueueItemNamesTheSong() {
        FlyoutMenuScan.Result result = scan(startMix(66441108, OTHER), queueAdd(66441155, SONG));

        assertEquals(SONG, result.songId());
        assertEquals(1, result.insertAfter());
        assertFalse(result.hasDownload());
    }

    @Test
    public void conditionalQueueItemNamesTheSong() {
        FlyoutMenuScan.Result result = scan(queueAdd(77258115, SONG));

        assertEquals(SONG, result.songId());
        assertEquals(0, result.insertAfter());
    }

    @Test
    public void startMixIsTheFallbackAndCreditsComeLast() {
        FlyoutMenuScan.Result mix = scan(credits(66441108, "MPTC" + OTHER), startMix(77262503, SONG));
        assertEquals(SONG, mix.songId());
        assertEquals(1, mix.insertAfter());

        FlyoutMenuScan.Result credit = scan(credits(66441108, "MPTC" + OTHER));
        assertEquals(OTHER, credit.songId());
    }

    @Test
    public void queueIdBeatsTheNavigationIds() {
        assertEquals(SONG, scan(startMix(66441108, OTHER), credits(66441108, "MPTC" + OTHER),
                queueAdd(77258115, SONG)).songId());
    }

    @Test
    public void conflictingQueueIdsAreNoSong() {
        assertNull(scan(queueAdd(66441155, SONG), queueAdd(77258115, OTHER)).songId());
    }

    @Test
    public void creditsNeedTheCreditsPrefixAndAnId() {
        assertNull(scan(credits(66441108, "UCsomechannel1")).songId());
        assertNull(scan(credits(66441108, "MPTCshort")).songId());
        assertNull(scan(credits(66441108, "MPREb_12345678901")).songId());
    }

    @Test
    public void existingDownloadItemIsDetectedOnBothRenderers() {
        byte[] upsell = item(66441155, message(133724106, string(1, SONG)));
        byte[] video = item(77258115, message(73080600, string(1, SONG)));

        assertTrue(scan(queueAdd(66441155, SONG), upsell).hasDownload());
        assertTrue(scan(queueAdd(66441155, SONG), video).hasDownload());
    }

    @Test
    public void menuWithoutASongStaysUnchanged() {
        FlyoutMenuScan.Result result = scan(null, item(66441155, message(1, string(1, "Close"))),
                string(5, "noise"));

        assertNull(result.songId());
        assertEquals(-1, result.insertAfter());
        assertNull(FlyoutMenuScan.scan(Collections.<byte[]>emptyList()).songId());
    }

    @Test
    public void itemsThatAreNotMessagesAreSkipped() {
        List<byte[]> items = Arrays.asList(new byte[]{(byte) 0xff, (byte) 0xff}, queueAdd(66441155, SONG));

        assertEquals(SONG, FlyoutMenuScan.scan(items).songId());
    }
}
