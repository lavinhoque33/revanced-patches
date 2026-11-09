/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-08.
 */
package app.morphe.extension.music.patches.downloads;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The decisions of {@link OfflineBrowsePages} that need no Android: which page is worth recording, whether an id is
 * safe as a file name, how big the framework updates in a recorded page are, and the compression of a page.
 */
final class OfflineBrowsePagesLogic {
    /** The field of a browse response that holds the Elements templates, fragments and the rest of the updates. */
    static final int FRAMEWORK_UPDATES_FIELD = 777;

    private static final int MAX_ID_LENGTH = 64;

    private OfflineBrowsePagesLogic() {}

    /** The field of a browse response that holds the next rows of a list instead of its page. */
    static final int CONTINUATION_FIELD = 10;

    /**
     * Whether the page a browse id opens is recorded: the page of a downloaded list. The id of a playlist page has
     * the {@code VL} prefix, which is not part of the list's id.
     */
    static boolean recordable(String browseId, Predicate<String> isSource) {
        return isSource.test(OfflineReplayLogic.sourceOfBrowseId(browseId));
    }

    /**
     * Whether a browse response is the next rows of a list (a continuation) and not its page. The page is replaced
     * by every later online open of it, so only the page itself is recorded.
     */
    static boolean isContinuation(byte[] response) {
        return OfflineReplayLogic.hasField(response, CONTINUATION_FIELD);
    }

    /** The top-level fields of a browse response with their sizes, such as {@code 9:1200,13:80}: for the log. */
    static String fieldSizes(byte[] response) {
        StringBuilder sizes = new StringBuilder();
        try {
            CodedInputStream input = CodedInputStream.newInstance(response);
            while (!input.isAtEnd()) {
                int tag = input.readTag();
                int start = input.getTotalBytesRead();
                if (!input.skipField(tag)) break;

                if (sizes.length() > 0) sizes.append(',');
                sizes.append(WireFormat.getTagFieldNumber(tag)).append(':').append(input.getTotalBytesRead() - start);
            }
        } catch (IOException ex) {
            sizes.append("unreadable");
        }
        return sizes.toString();
    }

    /** Whether the id is made of what a file name can hold, so that it names the recorded page's file. */
    static boolean isFileSafe(String id) {
        if (id.isEmpty() || id.length() > MAX_ID_LENGTH) return false;

        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean safe = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '-';
            if (!safe) return false;
        }
        return true;
    }

    /** The size in bytes of the framework updates of a browse response, 0 when it has none or is unreadable. */
    static int frameworkUpdatesSize(byte[] response) {
        try {
            CodedInputStream input = CodedInputStream.newInstance(response);
            while (!input.isAtEnd()) {
                int tag = input.readTag();
                boolean updates = WireFormat.getTagFieldNumber(tag) == FRAMEWORK_UPDATES_FIELD
                        && WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED;
                if (updates) return input.readByteArray().length;

                if (!input.skipField(tag)) return 0;
            }
        } catch (IOException ex) {
            return 0;
        }
        return 0;
    }

    static byte[] gzip(byte[] bytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, bytes.length / 4));
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(bytes);
        }
        return out.toByteArray();
    }

    static byte[] gunzip(byte[] bytes) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return gzip.readAllBytes();
        }
    }
}
