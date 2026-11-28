/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27.
 */
package app.morphe.extension.music.patches.downloads;

import java.util.Arrays;

final class NativeMirrorRules {
    enum Action { KEEP, INSERT, UPDATE, REMOVE, LEAVE_STOCK }

    private NativeMirrorRules() {}

    static boolean supportsSchema(int version) {
        return version == 38;
    }

    static boolean same(long saved, byte[] fingerprint, long markedSaved, byte[] markedFingerprint) {
        return saved == markedSaved && Arrays.equals(fingerprint, markedFingerprint);
    }

    /** Never reclaim a row the app took over or recreate one it removed unless explicitly downloaded again. */
    static Action action(boolean wanted, boolean rowExists, boolean marked, boolean owned, boolean hasStockChildren,
                         boolean changed, boolean explicit) {
        if (owned) return !wanted ? Action.REMOVE : changed ? Action.UPDATE : Action.KEEP;
        if (rowExists || hasStockChildren) return Action.LEAVE_STOCK;
        if (wanted && (!marked || explicit)) return Action.INSERT;
        return Action.LEAVE_STOCK;
    }
}
