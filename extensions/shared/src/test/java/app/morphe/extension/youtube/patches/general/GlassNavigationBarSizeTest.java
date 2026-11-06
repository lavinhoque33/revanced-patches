/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-06.
 */
package app.morphe.extension.youtube.patches.general;

import org.junit.Test;

import app.morphe.extension.shared.settings.preference.SeekBarPreference;
import app.morphe.extension.shared.settings.preference.SeekBarPreference.SeekBarConfig;

import static org.junit.Assert.assertEquals;

/** Exercises the production seekbar conversion used by the three size controls. */
public class GlassNavigationBarSizeTest {
    private static void assertRange(int min, int max, int step, int defaultValue, String unit) {
        SeekBarConfig config = new SeekBarConfig(null, min, max, step, unit);
        assertEquals(min, SeekBarPreference.progressToValue(config,
                SeekBarPreference.valueToProgress(config, Integer.MIN_VALUE)));
        assertEquals(max, SeekBarPreference.progressToValue(config,
                SeekBarPreference.valueToProgress(config, Integer.MAX_VALUE)));
        assertEquals(defaultValue, SeekBarPreference.progressToValue(config,
                SeekBarPreference.valueToProgress(config, defaultValue)));
        for (int progress = 0; progress <= (max - min) / step; progress++) {
            int value = SeekBarPreference.progressToValue(config, progress);
            assertEquals(progress, SeekBarPreference.valueToProgress(config, value));
            if (progress > 0) {
                assertEquals(step, value - SeekBarPreference.progressToValue(config, progress - 1));
            }
        }
    }

    @Test public void capsuleHeightClampsAndUsesFivePercentSteps() {
        assertRange(60, 160, 5, 100, "%");
    }

    @Test public void maximumWidthClampsAndUsesFivePercentSteps() {
        assertRange(50, 100, 5, 100, "%");
    }

    @Test public void buttonSpacingClampsAndUsesFourDpSteps() {
        assertRange(8, 96, 4, 48, "dp");
    }
}
