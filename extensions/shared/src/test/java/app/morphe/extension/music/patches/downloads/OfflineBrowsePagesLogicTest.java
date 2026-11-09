/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-08.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

public class OfflineBrowsePagesLogicTest {
    private static final Set<String> SOURCES = Set.of("PLa", "MPREb_c");

    @Test public void thePageOfADownloadedListIsRecorded() {
        assertTrue(OfflineBrowsePagesLogic.recordable("VLPLa", SOURCES::contains));
        assertTrue(OfflineBrowsePagesLogic.recordable("MPREb_c", SOURCES::contains));
    }

    @Test public void anOtherPageIsNotRecorded() {
        assertFalse(OfflineBrowsePagesLogic.recordable("VLPLother", SOURCES::contains));
        assertFalse(OfflineBrowsePagesLogic.recordable("FEmusic_home", SOURCES::contains));
    }

    @Test public void theNextRowsOfAListAreNotItsPage() {
        byte[] page = new ProtoWriter().message(9, new byte[10]).message(13, new byte[5]).toByteArray();
        byte[] rows = new ProtoWriter().message(OfflineBrowsePagesLogic.CONTINUATION_FIELD, new byte[10]).toByteArray();

        assertFalse(OfflineBrowsePagesLogic.isContinuation(page));
        assertTrue(OfflineBrowsePagesLogic.isContinuation(rows));
    }

    @Test public void theFieldsOfAResponseAreListedWithTheirSizes() {
        byte[] page = new ProtoWriter().message(9, new byte[10]).message(13, new byte[5]).toByteArray();

        assertEquals("9:11,13:6", OfflineBrowsePagesLogic.fieldSizes(page));
    }

    @Test public void onlyPlainIdsAreFileNames() {
        assertTrue(OfflineBrowsePagesLogic.isFileSafe("PL16FEhKJOy-bj-NlgyArbVsr_Yg_8ZqLg"));
        assertTrue(OfflineBrowsePagesLogic.isFileSafe("MPREb_abc123"));
        assertFalse(OfflineBrowsePagesLogic.isFileSafe(""));
        assertFalse(OfflineBrowsePagesLogic.isFileSafe("../x"));
        assertFalse(OfflineBrowsePagesLogic.isFileSafe("a/b"));
        assertFalse(OfflineBrowsePagesLogic.isFileSafe("x".repeat(65)));
    }

    @Test public void theFrameworkUpdatesAreMeasuredInTheResponse() {
        byte[] updates = new byte[300];
        byte[] response = new ProtoWriter()
                .string(1, "header")
                .message(OfflineBrowsePagesLogic.FRAMEWORK_UPDATES_FIELD, updates)
                .string(2, "rows")
                .toByteArray();

        assertEquals(300, OfflineBrowsePagesLogic.frameworkUpdatesSize(response));
        assertEquals(0, OfflineBrowsePagesLogic.frameworkUpdatesSize(new ProtoWriter().string(1, "x").toByteArray()));
        assertEquals(0, OfflineBrowsePagesLogic.frameworkUpdatesSize(new byte[]{(byte) 0xff}));
    }

    @Test public void aPageSurvivesCompression() throws IOException {
        byte[] page = "a page ".repeat(500).getBytes(StandardCharsets.UTF_8);

        byte[] packed = OfflineBrowsePagesLogic.gzip(page);

        assertTrue(packed.length < page.length);
        assertArrayEquals(page, OfflineBrowsePagesLogic.gunzip(packed));
    }
}
