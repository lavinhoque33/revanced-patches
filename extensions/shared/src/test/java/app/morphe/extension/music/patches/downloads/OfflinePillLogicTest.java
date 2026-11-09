/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-09
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.List;
import java.util.Map;

public class OfflinePillLogicTest {
    private static final OfflinePillLogic.Queue QUEUE = new OfflinePillLogic.Queue("PPSVrvx:album",
            List.of("a", "b", "c"));
    private static final Map<String, String> PAIRS = Map.of("vb", "b", "vc", "c");

    @Test public void aPlayingSongWithAVideoCopyStartsItsVideo() {
        assertEquals(new OfflinePillLogic.Target("vb", 2, "PPSVrvx:album", 1, "b"),
                OfflinePillLogic.target(QUEUE, PAIRS, "b"));
    }

    @Test public void aPlayingVideoStartsItsSong() {
        assertEquals(new OfflinePillLogic.Target("c", 1, "PPSVrvx:album", 2, "c"),
                OfflinePillLogic.target(QUEUE, PAIRS, "vc"));
    }

    @Test public void aSongWithoutAVideoCopyIsNotOurs() {
        assertNull(OfflinePillLogic.target(QUEUE, PAIRS, "a"));
    }

    @Test public void theFirstIdThatNamesAPairWins() {
        assertEquals("vb", OfflinePillLogic.target(QUEUE, PAIRS, null, "", "b", "c").videoId());
        assertEquals("vc", OfflinePillLogic.target(QUEUE, PAIRS, "a", "c").videoId());
    }

    @Test public void aSongOutsideTheQueueOrNoQueueOrNoPairsIsNotOurs() {
        assertNull(OfflinePillLogic.target(QUEUE, Map.of("vz", "z"), "z"));
        assertNull(OfflinePillLogic.target(null, PAIRS, "b"));
        assertNull(OfflinePillLogic.target(QUEUE, Map.of(), "b"));
    }

    @Test public void aCopyThatPointsAtItselfIsNotAPair() {
        assertNull(OfflinePillLogic.target(QUEUE, Map.of("b", "b"), "b"));
    }
}
