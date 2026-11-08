/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.CodedOutputStream;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

public class RowModelDumpTest {
    private static final String VIDEO = "dQw4w9WgXcQ";
    private static final String ALBUM = "OLAK5uy_nmDUsWOMoEcz0SsVqUwir0oxu-k1oUyXE";

    private interface Fields {
        void write(CodedOutputStream output) throws Exception;
    }

    private static byte[] message(Fields fields) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        fields.write(output);
        output.flush();
        return bytes.toByteArray();
    }

    private static String outline(byte[] message) {
        return String.join("", RowModelDump.lines(message, 12_000));
    }

    @Test public void stringsUpToSixtyFourCharactersAreShownWholeAndLongerOnesOnlyByLength() throws Exception {
        String longText = "x".repeat(65);
        byte[] model = message(output -> {
            output.writeString(1, "Never Gonna Give You Up");
            output.writeString(2, VIDEO);
            output.writeString(3, ALBUM);
            output.writeString(4, longText);
        });

        assertEquals("1:\"Never Gonna Give You Up\" 2:\"" + VIDEO + "\" 3:\"" + ALBUM + "\" 4:s65", outline(model));
    }

    @Test public void everyIdOfTheModelIsListedOnItsOwn() throws Exception {
        byte[] watch = message(output -> {
            output.writeString(1, VIDEO);
            output.writeString(2, ALBUM);
            output.writeInt32(3, 4);
        });
        String url = "https://music.youtube.com/watch?v=" + VIDEO + "&list=" + ALBUM;
        byte[] model = message(output -> {
            output.writeByteArray(5, message(inner -> inner.writeByteArray(9, watch)));
            output.writeString(6, url);
        });

        assertEquals(new java.util.LinkedHashSet<>(Arrays.asList(VIDEO, ALBUM)), RowModelDump.ids(model));
    }

    @Test public void theOutlineStopsAtTheCharacterLimitAndSaysSo() throws Exception {
        byte[] model = message(output -> {
            for (int index = 0; index < 400; index++) output.writeString(1 + index % 50, "y".repeat(40));
        });

        String cut = String.join("", RowModelDump.lines(model, 1_000));

        assertTrue(cut.length() < 1_400);
        assertTrue(cut.endsWith("...cut at about 1000 characters"));
        assertFalse(String.join("", RowModelDump.lines(model, 200_000)).contains("...cut"));
    }

    @Test public void nestedMessagesAreOpenedAndSmallVarintsShown() throws Exception {
        byte[] watch = message(output -> {
            output.writeString(1, VIDEO);
            output.writeInt32(3, 7);
        });
        byte[] model = message(output -> output.writeByteArray(5, message(inner -> inner.writeByteArray(9, watch))));

        assertEquals("5:{9:{1:\"" + VIDEO + "\" 3:v7}}", outline(model));
    }

    @Test public void aBase64StringThatHoldsAMessageIsOpened() throws Exception {
        byte[] watch = message(output -> output.writeString(1, VIDEO));
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(watch);
        byte[] model = message(output -> output.writeString(4, encoded));

        assertEquals("4:b64{1:\"" + VIDEO + "\"}", outline(model));
    }

    @Test public void aUrlShowsItsVideoAndPlaylistParameters() throws Exception {
        byte[] model = message(output ->
                output.writeString(2, "https://music.youtube.com/watch?v=" + VIDEO + "&list=" + ALBUM));

        assertEquals("2:url" + ("https://music.youtube.com/watch?v=" + VIDEO + "&list=" + ALBUM).length()
                + "[v=" + VIDEO + "][list=" + ALBUM + "]", outline(model));
    }

    @Test public void bytesThatAreNoMessageShowOnlyTheirLength() throws Exception {
        byte[] noise = {(byte) 0xFF, (byte) 0xFE, 0x01, 0x02};

        assertEquals("1:b4", outline(message(output -> output.writeByteArray(1, noise))));
    }

    @Test public void aLongOutlineIsCutIntoLines() throws Exception {
        byte[] model = message(output -> {
            for (int index = 0; index < 800; index++) output.writeString(1 + index % 50, "x".repeat(40));
        });

        List<String> lines = RowModelDump.lines(model, 100_000);

        assertTrue(lines.size() > 1);
        for (String line : lines) assertTrue(line.length() <= 3000);
    }

    @Test public void aTruncatedMessageNeverThrows() throws Exception {
        byte[] model = message(output -> output.writeString(1, ALBUM));
        for (int cut = 0; cut < model.length; cut++) RowModelDump.lines(Arrays.copyOf(model, cut), 12_000);
    }
}
