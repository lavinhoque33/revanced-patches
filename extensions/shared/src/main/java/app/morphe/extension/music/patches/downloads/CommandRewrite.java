/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Retargets a tapped watch command from the music video of a song to the song itself, on the serialized message so
 * that no obfuscated class is needed. Only two things change: the video id of the watch endpoint (field 1) and, when
 * it is present and says official music video, the music video type of the endpoint's music config. Every other
 * field, known or not, is copied byte for byte, so the playlist, the position, the playlist entry id, the params and
 * the tracking stay as the app built them.
 *
 * <p>Path of the type: command, watch endpoint 48,687,757, supported configs 136,656,028, music config 136,657,325,
 * field 2 (the enum, 1 = song/ATV, 2 = official music video/OMV).
 */
final class CommandRewrite {
    static final int WATCH_ENDPOINT_FIELD = 48_687_757;
    static final int VIDEO_ID_FIELD = 1;
    static final int SUPPORTED_CONFIG_FIELD = 136_656_028;
    static final int MUSIC_CONFIG_FIELD = 136_657_325;
    static final int MUSIC_VIDEO_TYPE_FIELD = 2;
    static final int TYPE_ATV = 1;
    static final int TYPE_OMV = 2;
    private static final int VIDEO_ID_LENGTH = 11;
    private static final int DUMP_DEPTH = 3;
    private static final int DUMP_MAX_FIELDS = 24;
    private static final int DUMP_MAX_CHARS = 900;
    private static final int DUMP_STRING_CHARS = 16;

    private CommandRewrite() {}

    /** The retargeted command and what happened to the type: {@code "2->1"} or {@code "untouched"}. */
    record Result(byte[] command, String type) {}

    /** One field of a serialized message: where its tag, value and end are. */
    private record Field(int number, int wireType, int tagEnd, int valueStart, int end) {
        boolean isMessage() {
            return wireType == 2;
        }
    }

    /**
     * @param fromId The video id the watch endpoint carries now.
     * @param toId   The video id to carry instead.
     * @return The retargeted command, or null when it has no watch endpoint with exactly {@code fromId} or when it
     * cannot be read. A type other than official music video is left as it is.
     */
    @Nullable
    static Result retarget(byte[] command, String fromId, String toId) {
        if (fromId.length() != VIDEO_ID_LENGTH || !isVideoId(toId)) return null;
        try {
            Field watchField = first(parse(command), WATCH_ENDPOINT_FIELD, 2);
            if (watchField == null) return null;
            byte[] watch = payload(command, watchField);

            Field idField = first(parse(watch), VIDEO_ID_FIELD, 2);
            if (idField == null || !new String(payload(watch, idField), StandardCharsets.ISO_8859_1).equals(fromId)) {
                return null;
            }
            byte[] newWatch = replacePayload(watch, idField, toId.getBytes(StandardCharsets.ISO_8859_1));

            String type = "untouched";
            try {
                TypeRewrite typed = retypeSupportedConfig(newWatch);
                if (typed != null) {
                    newWatch = typed.watch;
                    type = TYPE_OMV + "->" + TYPE_ATV;
                }
            } catch (IllegalArgumentException unrecognised) {
                // The config does not have the expected shape: keep it untouched.
            }
            return new Result(replacePayload(command, watchField, newWatch), type);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private record TypeRewrite(byte[] watch) {}

    /** The watch endpoint with its music video type switched to ATV, or null when it has no OMV type. */
    @Nullable
    private static TypeRewrite retypeSupportedConfig(byte[] watch) {
        Field supportedField = first(parse(watch), SUPPORTED_CONFIG_FIELD, 2);
        if (supportedField == null) return null;
        byte[] supported = payload(watch, supportedField);

        Field musicField = first(parse(supported), MUSIC_CONFIG_FIELD, 2);
        if (musicField == null) return null;
        byte[] music = payload(supported, musicField);

        Field typeField = first(parse(music), MUSIC_VIDEO_TYPE_FIELD, 0);
        if (typeField == null) return null;
        if (readVarint(music, new int[]{typeField.valueStart}) != TYPE_OMV) return null;

        byte[] newMusic = replaceVarint(music, typeField, TYPE_ATV);
        byte[] newSupported = replacePayload(supported, musicField, newMusic);
        return new TypeRewrite(replacePayload(watch, supportedField, newSupported));
    }

    /**
     * A compact structure of the watch endpoint: {@code 3:v0,79857908:s1107,136656028:{136657325:{2:v2}}}.
     * Varints show their value, messages recurse, strings are cut to 16 characters.
     */
    static String describeWatch(byte[] command) {
        try {
            Field watchField = first(parse(command), WATCH_ENDPOINT_FIELD, 2);
            if (watchField == null) return "[no watch endpoint]";
            String text = describe(payload(command, watchField), DUMP_DEPTH);
            return "[" + (text.length() <= DUMP_MAX_CHARS ? text : text.substring(0, DUMP_MAX_CHARS) + "...") + "]";
        } catch (IllegalArgumentException malformed) {
            return "[unreadable]";
        }
    }

    private static String describe(byte[] message, int depth) {
        StringBuilder text = new StringBuilder();
        List<Field> fields = parse(message);
        for (int index = 0; index < fields.size(); index++) {
            if (index >= DUMP_MAX_FIELDS) {
                text.append(",...");
                break;
            }
            Field field = fields.get(index);
            if (index > 0) text.append(',');
            text.append(field.number).append(':');
            switch (field.wireType) {
                case 0 -> text.append('v').append(readVarint(message, new int[]{field.valueStart}));
                case 1 -> text.append("f64");
                case 5 -> text.append("f32");
                default -> text.append(describeBytes(payload(message, field), depth));
            }
        }
        return text.toString();
    }

    private static String describeBytes(byte[] bytes, int depth) {
        if (isPrintable(bytes)) {
            String text = new String(bytes, StandardCharsets.ISO_8859_1);
            return text.length() <= DUMP_STRING_CHARS ? "\"" + text + "\""
                    : "\"" + text.substring(0, DUMP_STRING_CHARS) + "\"(" + text.length() + ")";
        }
        if (depth > 0 && bytes.length > 0) {
            try {
                return "{" + describe(bytes, depth - 1) + "}";
            } catch (IllegalArgumentException notAMessage) {
                // Fall through to the size.
            }
        }
        return "s" + bytes.length;
    }

    private static boolean isPrintable(byte[] bytes) {
        if (bytes.length == 0) return false;
        for (byte value : bytes) {
            if (value < 0x20 || value > 0x7E) return false;
        }
        return true;
    }

    private static boolean isVideoId(String id) {
        if (id.length() != VIDEO_ID_LENGTH) return false;
        for (int index = 0; index < id.length(); index++) {
            char c = id.charAt(index);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_')) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private static Field first(List<Field> fields, int number, int wireType) {
        for (Field field : fields) {
            if (field.number == number && field.wireType == wireType) return field;
        }
        return null;
    }

    private static byte[] payload(byte[] message, Field field) {
        byte[] bytes = new byte[field.end - field.valueStart];
        System.arraycopy(message, field.valueStart, bytes, 0, bytes.length);
        return bytes;
    }

    /** The message with the length-delimited field's payload replaced; everything else is copied unchanged. */
    private static byte[] replacePayload(byte[] message, Field field, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(message.length + payload.length);
        out.write(message, 0, field.tagEnd);
        writeVarint(out, payload.length);
        out.write(payload, 0, payload.length);
        out.write(message, field.end, message.length - field.end);
        return out.toByteArray();
    }

    private static byte[] replaceVarint(byte[] message, Field field, long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(message.length);
        out.write(message, 0, field.valueStart);
        writeVarint(out, value);
        out.write(message, field.end, message.length - field.end);
        return out.toByteArray();
    }

    private static List<Field> parse(byte[] message) {
        List<Field> fields = new ArrayList<>();
        int[] position = {0};
        while (position[0] < message.length) {
            long key = readVarint(message, position);
            int number = (int) (key >>> 3);
            int wireType = (int) (key & 7);
            int tagEnd = position[0];
            if (number <= 0) throw new IllegalArgumentException("Field number 0");
            switch (wireType) {
                case 0 -> readVarint(message, position);
                case 1 -> position[0] = checked(message, position[0], 8);
                case 5 -> position[0] = checked(message, position[0], 4);
                case 2 -> {
                    long length = readVarint(message, position);
                    if (length < 0 || length > message.length) throw new IllegalArgumentException("Bad length");
                    int valueStart = position[0];
                    position[0] = checked(message, valueStart, (int) length);
                    fields.add(new Field(number, wireType, tagEnd, valueStart, position[0]));
                    continue;
                }
                default -> throw new IllegalArgumentException("Unsupported wire type " + wireType);
            }
            fields.add(new Field(number, wireType, tagEnd, tagEnd, position[0]));
        }
        return fields;
    }

    private static int checked(byte[] message, int from, int length) {
        if (from + length > message.length) throw new IllegalArgumentException("Truncated field");
        return from + length;
    }

    private static long readVarint(byte[] data, int[] position) {
        long result = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (position[0] >= data.length) throw new IllegalArgumentException("Truncated varint");
            byte b = data[position[0]++];
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
        }
        throw new IllegalArgumentException("Malformed varint");
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }
}
