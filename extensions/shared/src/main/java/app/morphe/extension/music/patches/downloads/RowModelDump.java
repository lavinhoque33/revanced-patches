/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A readable outline of a serialized row model for the debug log, to see where a row keeps its song: field numbers,
 * every string of up to 64 printable characters whole (longer ones by length), nested messages in braces, and base64
 * strings that hold a message opened as {@code b64{...}}. The outline is complete up to a character limit; the video
 * ids, playlist ids and positions of the whole model are also listed on their own by {@link #ids}.
 */
final class RowModelDump {
    private static final int MAX_DEPTH = 24;
    private static final int MAX_LINE = 3000;
    private static final int MAX_STRING = 64;
    private static final int MIN_BASE64 = 16;
    private static final int MIN_PLAYLIST_ID = 13;
    private static final int VIDEO_ID_LENGTH = 11;
    private static final String[] PLAYLIST_PREFIXES = {"PL", "OLAK5uy_", "RD", "VL", "MPRE", "UU", "LM"};

    /** What a walk over a message collects. */
    private static final class Walk {
        final Set<String> ids = new LinkedHashSet<>();
        /** Characters the outline may still take, or -1 for no limit. */
        int budget;

        Walk(int budget) {
            this.budget = budget;
        }

        boolean exhausted() {
            return budget == 0;
        }

        void spend(int characters) {
            if (budget > 0) budget = Math.max(0, budget - characters);
        }
    }

    private RowModelDump() {}

    /** The outline of at most about [maxChars] characters, cut into pieces that each fit one log line. */
    static List<String> lines(byte[] message, int maxChars) {
        Walk walk = new Walk(maxChars);
        StringBuilder outline = new StringBuilder();
        outline(message, 0, outline, walk);
        if (walk.exhausted()) outline.append(" ...cut at about ").append(maxChars).append(" characters");

        List<String> lines = new ArrayList<>();
        for (int from = 0; from < outline.length(); from += MAX_LINE) {
            lines.add(outline.substring(from, Math.min(outline.length(), from + MAX_LINE)));
        }
        return lines;
    }

    /** Every video id, playlist id and the ids of every watch URL in the message, however deep, without repeats. */
    static Set<String> ids(byte[] message) {
        Walk walk = new Walk(-1);
        outline(message, 0, new StringBuilder(), walk);
        return walk.ids;
    }

    /** Appends the fields of [message]; false when the bytes are not a well-formed message. */
    private static boolean outline(byte[] message, int depth, StringBuilder out, Walk walk) {
        StringBuilder fields = new StringBuilder();
        try {
            CodedInputStream input = CodedInputStream.newInstance(message);
            while (!input.isAtEnd() && !walk.exhausted()) {
                int tag = input.readTag();
                int number = WireFormat.getTagFieldNumber(tag);
                if (number == 0) return false;

                if (fields.length() > 0) fields.append(' ');
                int before = fields.length();
                switch (WireFormat.getTagWireType(tag)) {
                    case WireFormat.WIRETYPE_VARINT -> {
                        long value = input.readInt64();
                        fields.append(number).append(":v");
                        if (value >= 0 && value < 1000) fields.append(value);
                    }
                    case WireFormat.WIRETYPE_FIXED64 -> {
                        input.readFixed64();
                        fields.append(number).append(":f64");
                    }
                    case WireFormat.WIRETYPE_FIXED32 -> {
                        input.readFixed32();
                        fields.append(number).append(":f32");
                    }
                    case WireFormat.WIRETYPE_LENGTH_DELIMITED -> {
                        // The value is charged where it is a leaf: a nested message charges its own fields.
                        walk.spend(String.valueOf(number).length() + 1);
                        fields.append(number).append(':').append(describe(input.readByteArray(), depth, walk));
                        continue;
                    }
                    default -> {
                        return false;
                    }
                }
                walk.spend(fields.length() - before);
            }
        } catch (IOException malformed) {
            return false;
        }
        out.append(fields);
        return true;
    }

    private static String describe(byte[] value, int depth, Walk walk) {
        String text = printable(value);
        if (text != null) return leaf(walk, describeString(text, depth, walk));

        if (depth < MAX_DEPTH) {
            StringBuilder nested = new StringBuilder();
            if (value.length > 0 && outline(value, depth + 1, nested, walk)) return "{" + nested + "}";
        }
        return leaf(walk, "b" + value.length);
    }

    /** A nested message was charged for its own fields, so only the other descriptions are charged here. */
    private static String leaf(Walk walk, String description) {
        if (!description.startsWith("{") && !description.startsWith("b64{")) walk.spend(description.length());
        return description;
    }

    private static String describeString(String text, int depth, Walk walk) {
        if (isId(text) || isPlaylistId(text)) {
            walk.ids.add(text);
            return "\"" + text + "\"";
        }

        byte[] decoded = depth < MAX_DEPTH ? decodeBase64(text) : null;
        if (decoded != null) {
            StringBuilder nested = new StringBuilder();
            if (outline(decoded, depth + 1, nested, walk)) return "b64{" + nested + "}";
        }
        if (text.startsWith("http")) return "url" + text.length() + urlIds(text, walk);
        return text.length() <= MAX_STRING ? "\"" + text + "\"" : "s" + text.length();
    }

    /** The video id and playlist id of a watch URL, shown whole, since a URL may be where a row keeps its song. */
    private static String urlIds(String url, Walk walk) {
        StringBuilder found = new StringBuilder();
        for (String name : new String[]{"v=", "list="}) {
            int at = url.indexOf(name);
            if (at < 0) continue;

            int end = at + name.length();
            while (end < url.length() && "&#".indexOf(url.charAt(end)) < 0) end++;
            found.append('[').append(url, at, end).append(']');
            walk.ids.add(url.substring(at + name.length(), end));
        }
        return found.toString();
    }

    @Nullable
    private static byte[] decodeBase64(String text) {
        if (text.length() < MIN_BASE64 || !text.matches("[A-Za-z0-9_=+/%-]+")) return null;
        try {
            return Base64.getUrlDecoder().decode(java.net.URLDecoder.decode(text, "UTF-8"));
        } catch (IllegalArgumentException | java.io.UnsupportedEncodingException notBase64) {
            return null;
        }
    }

    private static boolean isId(String text) {
        return text.length() == VIDEO_ID_LENGTH && text.matches("[A-Za-z0-9_-]+");
    }

    private static boolean isPlaylistId(String text) {
        if (text.length() < MIN_PLAYLIST_ID || text.length() > MAX_STRING || !text.matches("[A-Za-z0-9_-]+")) {
            return false;
        }
        for (String prefix : PLAYLIST_PREFIXES) {
            if (text.startsWith(prefix)) return true;
        }
        return false;
    }

    /** The bytes as text when they are all printable ASCII, else null. */
    @Nullable
    private static String printable(byte[] value) {
        if (value.length == 0) return null;

        for (byte character : value) {
            if (character < 0x20 || character > 0x7E) return null;
        }
        return new String(value, StandardCharsets.US_ASCII);
    }
}
