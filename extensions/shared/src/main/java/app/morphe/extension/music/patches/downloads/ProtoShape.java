/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-09.
 */
package app.morphe.extension.music.patches.downloads;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A one-line outline of an encoded protobuf message for a device log: which fields it has and how big they are,
 * with no field names. Two answers of the same player request (the live one and the stored one) are compared by
 * their outlines, field by field. Debug aid; it reads, it never changes a message.
 *
 * <p>Format: {@code 2{1=0 5=1} 4{1=21540 3:5000Bx2} 11:310B}. A varint field reads {@code number=value} (the last
 * one); any other field reads {@code number:bytes}, with {@code xN} when it repeats N times. The fields listed in
 * {@code expand} are shown with their own fields in braces.
 */
final class ProtoShape {
    private ProtoShape() {}

    /** The outline of {@code message}; the fields in {@code expand} are opened one level. Never throws. */
    static String of(byte[] message, Set<Integer> expand) {
        try {
            return outline(message, expand);
        } catch (IOException ex) {
            return "unreadable";
        }
    }

    private static String outline(byte[] message, Set<Integer> expand) throws IOException {
        Map<Integer, Field> fields = new TreeMap<>();
        CodedInputStream input = CodedInputStream.newInstance(message);
        while (true) {
            int tag = input.readTag();
            if (tag == 0) break;

            int number = WireFormat.getTagFieldNumber(tag);
            Field field = fields.computeIfAbsent(number, key -> new Field());
            field.count++;
            switch (WireFormat.getTagWireType(tag)) {
                case WireFormat.WIRETYPE_VARINT -> {
                    field.varint = true;
                    field.value = input.readInt64();
                }
                case WireFormat.WIRETYPE_LENGTH_DELIMITED -> {
                    byte[] payload = input.readByteArray();
                    field.bytes += payload.length;
                    if (expand.contains(number)) field.inner = of(payload, Set.of());
                }
                default -> {
                    int before = input.getTotalBytesRead();
                    if (!input.skipField(tag)) throw new IOException("bad field");
                    field.bytes += input.getTotalBytesRead() - before;
                }
            }
        }

        StringBuilder out = new StringBuilder();
        for (Map.Entry<Integer, Field> entry : fields.entrySet()) {
            if (out.length() > 0) out.append(' ');
            out.append(entry.getKey()).append(entry.getValue().text());
        }
        return out.toString();
    }

    private static final class Field {
        int count;
        boolean varint;
        long value;
        long bytes;
        String inner;

        String text() {
            if (varint) return "=" + value;

            String size = ":" + bytes + "B" + (count > 1 ? "x" + count : "");
            return inner == null ? size : "{" + inner + "}";
        }
    }
}
