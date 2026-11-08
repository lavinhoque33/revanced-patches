/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.*;

import org.junit.Test;

public class ElementRowLayoutTest {
    // The track row of an album page on a 2.5 density, 1080 px wide screen (measured in a view dump): the row is 160 px
    // high and the cell of the number is 85 x 120 px, 40 px from the start.
    private static final float DENSITY = 2.5f;

    @Test public void theCellOfTheTrackNumberIsRecognised() {
        assertTrue(ElementRowLayout.isNumberCell(40, 85, 120, 1080, 160, DENSITY));
    }

    @Test public void aThumbnailOrAButtonIsNotTheCellOfTheNumber() {
        // A 56 dp wide thumbnail, a button at the end of the row, a short view at the start.
        assertFalse(ElementRowLayout.isNumberCell(40, 140, 140, 1080, 160, DENSITY));
        assertFalse(ElementRowLayout.isNumberCell(978, 92, 120, 1080, 160, DENSITY));
        assertFalse(ElementRowLayout.isNumberCell(40, 85, 30, 1080, 160, DENSITY));
    }

    @Test public void onlyTheHeightOfATrackRowCounts() {
        assertFalse(ElementRowLayout.isNumberCell(40, 85, 120, 1080, 40, DENSITY));
        assertFalse(ElementRowLayout.isNumberCell(40, 85, 120, 1080, 400, DENSITY));
        assertFalse(ElementRowLayout.isNumberCell(40, 85, 120, 400, 160, DENSITY));
    }

    @Test public void theActionBarOfTheAlbumHeaderIsNoTrackRow() {
        // Measured on a device: 1080 x 120 px (48 dp), with a 97 x 120 px icon button cell that looked like a number.
        assertFalse(ElementRowLayout.isTrackRow(1080, 120, DENSITY));
        assertFalse(ElementRowLayout.isNumberCell(0, 97, 120, 1080, 120, DENSITY));
        assertTrue(ElementRowLayout.isTrackRow(1080, 160, DENSITY));
    }

    @Test public void theCheckSitsUnderTheNumberCenteredInTheCell() {
        int[] origin = ElementRowLayout.checkOrigin(40, 20, 85, 120, DENSITY);

        // 16 dp = 40 px: centered in 85 px, and 9 dp (23 px) under the middle of the cell (20 + 60).
        assertEquals(40 + (85 - 40) / 2, origin[0]);
        assertEquals(20 + 60 + 23, origin[1]);
        // It stays inside the row.
        assertTrue(origin[1] + 40 <= 160);
    }
}
