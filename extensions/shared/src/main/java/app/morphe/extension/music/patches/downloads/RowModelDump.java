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
import java.util.List;

/**
 * A readable outline of a serialized row model for the debug log, to see where a row keeps its song: field numbers
 * with the length of every string, nested messages in braces, and base64 strings that hold a message opened as
 * {@code b64{...}}. Strings that are a video id or a playlist id are shown whole (they are public ids and are what
 * the lookup needs); every other string shows only its length, so no text of a user is logged.
 */
final class RowModelDump {
    private static final int MAX_DEPTH = 24;
    private static final int MAX_NODES = 900;
    private static final int MAX_LINE = 3000;
    private static final int MIN_BASE64 = 16;
    private static final int MIN_PLAYLIST_ID = 13;
    private static final int MAX_PLAYLIST_ID = 64;
    private static final int VIDEO_ID_LENGTH = 11;
    private static final String[] PLAYLIST_PREFIXES = {"PL", "OLAK5uy_", "RD", "VL", "MPRE", "UU", "LM"};

    private RowModelDump() {}

    /** The outline, cut into pieces that each fit one log line. */
    static List<String> lines(byte[] message) {
        StringBuilder outline = new StringBuilder();
        int[] nodes = {MAX_NODES};
        outline(message, 0, outline, nodes);
        if (nodes[0] <= 0) outline.append(" ...cut");

        List<String> lines = new ArrayList<>();
        for (int from = 0; from < outline.length(); from += MAX_LINE) {
            lines.add(outline.substring(from, Math.min(outline.length(), from + MAX_LINE)));
        }
        return lines;
    }

    /** Appends the fields of [message]; false when the bytes are not a well-formed message. */
    private static boolean outline(byte[] message, int depth, StringBuilder out, int[] nodes) {
        StringBuilder fields = new StringBuilder();
        try {
            CodedInputStream input = CodedInputStream.newInstance(message);
            while (!input.isAtEnd()) {
                int tag = input.readTag();
                int number = WireFormat.getTagFieldNumber(tag);
                if (number == 0) return false;
                if (nodes[0]-- <= 0) break;

                if (fields.length() > 0) fields.append(' ');
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
                    case WireFormat.WIRETYPE_LENGTH_DELIMITED ->
                            fields.append(number).append(':').append(describe(input.readByteArray(), depth, nodes));
                    default -> {
                        return false;
                    }
                }
            }
        } catch (IOException malformed) {
            return false;
        }
        out.append(fields);
        return true;
    }

    private static String describe(byte[] value, int depth, int[] nodes) {
        String text = printable(value);
        if (text != null) return describeString(text, depth, nodes);

        if (depth < MAX_DEPTH) {
            StringBuilder nested = new StringBuilder();
            if (value.length > 0 && outline(value, depth + 1, nested, nodes)) return "{" + nested + "}";
        }
        return "b" + value.length;
    }

    private static String describeString(String text, int depth, int[] nodes) {
        if (isId(text)) return "\"" + text + "\"";

        if (isPlaylistId(text)) return "\"" + text + "\"(" + text.length() + ")";

        byte[] decoded = depth < MAX_DEPTH ? decodeBase64(text) : null;
        if (decoded != null) {
            StringBuilder nested = new StringBuilder();
            if (outline(decoded, depth + 1, nested, nodes)) return "b64{" + nested + "}";
        }
        if (text.startsWith("http")) return "url" + text.length() + urlIds(text);
        return "s" + text.length();
    }

    /** The video id and playlist id of a watch URL, shown whole, since a URL may be where a row keeps its song. */
    private static String urlIds(String url) {
        StringBuilder found = new StringBuilder();
        for (String name : new String[]{"v=", "list="}) {
            int at = url.indexOf(name);
            if (at < 0) continue;

            int end = at + name.length();
            while (end < url.length() && "&#".indexOf(url.charAt(end)) < 0) end++;
            found.append('[').append(url, at, end).append(']');
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
        if (text.length() < MIN_PLAYLIST_ID || text.length() > MAX_PLAYLIST_ID || !text.matches("[A-Za-z0-9_-]+")) {
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
