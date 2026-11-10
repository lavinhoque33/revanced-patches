/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-09.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Set;

public class ProtoShapeTest {
    @Test public void varintsShowTheirValueAndOtherFieldsTheirSize() {
        byte[] message = new ProtoWriter().int64(1, 21540).message(3, new byte[]{1, 2, 3}).toByteArray();

        assertEquals("1=21540 3:3B", ProtoShape.of(message, Set.of()));
    }

    @Test public void aRepeatedFieldIsOneEntryWithItsTotalAndCount() {
        byte[] message = new ProtoWriter().message(2, new byte[10]).message(2, new byte[5]).toByteArray();

        assertEquals("2:15Bx2", ProtoShape.of(message, Set.of()));
    }

    @Test public void anExpandedFieldShowsItsOwnFieldsOneLevelDeep() {
        byte[] inner = new ProtoWriter().int64(1, 7).message(5, new byte[4]).toByteArray();
        byte[] message = new ProtoWriter().message(4, inner).message(9, new byte[2]).toByteArray();

        assertEquals("4{1=7 5:4B} 9:2B", ProtoShape.of(message, Set.of(4)));
    }

    @Test public void fieldsAreInNumberOrderAndMalformedBytesDoNotThrow() {
        byte[] message = new ProtoWriter().int64(9, 1).int64(2, 2).toByteArray();

        assertEquals("2=2 9=1", ProtoShape.of(message, Set.of()));
        assertEquals("unreadable", ProtoShape.of(new byte[]{(byte) 0xff}, Set.of()));
        assertEquals("", ProtoShape.of(new byte[0], Set.of()));
    }
}
