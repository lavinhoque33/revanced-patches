/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27.
 * Written by lavinhoque33, 2026-11-26.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What the redesigned player needs of a song's served watch response, with no Android dependency: which engagement
 * panels a button refers to, and the command behind the song title (the Related page). Both are read at the wire
 * level out of the bytes of the response, the same way the offline response is built (see {@link OfflineWatchNext}).
 */
final class ReconnectSurfacesLogic {
    private static final int MAX_DEPTH = 10;
    private static final int MAX_LOG_FIELDS = 8;

    // Lakyb.m is the command in the button metadata; Lakya itself is an Elements renderer, not a command.
    private static final int BUTTON_METADATA = 3;
    private static final int BUTTON_COMMAND = 4;
    private static final int ELEMENTS_INPUT = 5;
    private static final int ELEMENTS_MODEL_EXTENSION = 172_660_663;

    // Lvoy.f resolves the direct endpoint, or the endpoint inside Laquw.g. Lsza.by reads its key.
    private static final int SHOW_PANEL = 138_681_778;
    private static final int PANEL_ACTION = 369_951_587;
    private static final int PANEL_ACTION_COMMAND = 5;
    private static final int PANEL_ACTION_IDENTIFIER = 2;
    private static final int SHOW_PANEL_IDENTIFIER = 10;
    private static final int IDENTIFIER_KEY = 3;

    // Lhko.bk prefers the tabbed queue (bj), falling back to Lamgi.c (bl) only if that queue is absent.
    private static final int LEGACY_QUEUE_HOLDER = 2;
    private static final int ITEM_WRAPPER_FIELD = 206_848_145;
    private static final int WRAPPER_PRIMARY = 1;
    private static final int WRAPPER_COUNTERPARTS = 2;
    private static final int COUNTERPART_RENDERER = 2;
    private static final int RUN_ENDPOINT_FIELD = 5;

    enum TitlePresence {
        NO_QUEUE, NO_TRACK, AMBIGUOUS_TRACK, NO_TITLE, NO_COMMAND, PRESENT, MALFORMED
    }

    record Related(String path, TitlePresence presence, @Nullable byte[] command) { }

    record PanelTarget(String path, @Nullable String key) { }

    private ReconnectSurfacesLogic() {}

    /**
     * The ids that occur as a complete string value inside {@code message}, at any depth. An id is the whole value of
     * a field, never a part of one: a longer text that contains an id does not refer to the panel, nor does a prefix.
     * Every length-delimited field is both a string and, when it parses, a message, so both are looked at.
     */
    static Set<String> referencedPanels(Collection<String> ids, @Nullable byte[] message) {
        Set<String> found = new LinkedHashSet<>();
        if (message == null || message.length == 0) return found;

        List<byte[]> wanted = new ArrayList<>(ids.size());
        List<String> names = new ArrayList<>(ids.size());
        for (String id : ids) {
            if (id == null || id.isEmpty() || names.contains(id)) continue;

            names.add(id);
            wanted.add(id.getBytes(StandardCharsets.UTF_8));
        }
        if (!names.isEmpty()) collect(message, 0, names, wanted, found);

        return found;
    }

    private static void collect(byte[] message, int depth, List<String> names, List<byte[]> wanted, Set<String> found) {
        try {
            CodedInputStream input = CodedInputStream.newInstance(message);
            while (!input.isAtEnd()) {
                int tag = input.readTag();
                if (tag == 0 || WireFormat.getTagFieldNumber(tag) == 0) return;

                if (WireFormat.getTagWireType(tag) != WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                    if (!input.skipField(tag)) return;
                    continue;
                }
                byte[] value = input.readByteArray();
                for (int index = 0; index < wanted.size(); index++) {
                    if (Arrays.equals(value, wanted.get(index))) found.add(names.get(index));
                }
                if (depth < MAX_DEPTH && value.length > 1) collect(value, depth + 1, names, wanted, found);
            }
        } catch (IOException notAMessage) {
            // A string that is not a message: its own bytes were compared above, there is nothing nested in it.
        }
    }

    /** Reads only the native command in the metadata, never a string in the opaque Elements input. */
    static PanelTarget buttonPanel(@Nullable byte[] renderer) {
        if (renderer == null) return new PanelTarget("no-renderer", null);

        try {
            byte[] metadata = field(renderer, BUTTON_METADATA);
            byte[] command = metadata == null ? null : field(metadata, BUTTON_COMMAND);
            boolean elements = hasField(renderer, ELEMENTS_INPUT) || hasField(renderer, ELEMENTS_MODEL_EXTENSION);
            PanelTarget target = command == null ? null : commandPanel(command);
            if (target != null && target.key() != null) return target;
            return elements ? new PanelTarget("elements-input", null)
                    : target == null ? new PanelTarget("no-metadata-command", null) : target;
        } catch (IOException malformed) {
            return new PanelTarget("malformed-renderer", null);
        }
    }

    static PanelTarget commandPanel(@Nullable byte[] command) {
        if (command == null) return new PanelTarget("no-command", null);

        try {
            byte[] show = field(command, SHOW_PANEL);
            if (show != null) return new PanelTarget("direct", panelKey(show, SHOW_PANEL_IDENTIFIER));

            byte[] action = field(command, PANEL_ACTION);
            if (action != null) {
                byte[] nested = field(action, PANEL_ACTION_COMMAND);
                return nested == null
                        ? new PanelTarget("action-key", panelKey(action, PANEL_ACTION_IDENTIFIER))
                        : new PanelTarget("nested", panelKey(field(nested, SHOW_PANEL), SHOW_PANEL_IDENTIFIER));
            }
            return new PanelTarget("other-command", null);
        } catch (IOException malformed) {
            return new PanelTarget("malformed-command", null);
        }
    }

    @Nullable
    private static String panelKey(@Nullable byte[] endpoint, int identifierNumber) throws IOException {
        if (endpoint == null) return null;

        // Both the field-1 string and the typed identifier are in the same oneof: the last set arm wins.
        String key = null;
        CodedInputStream input = CodedInputStream.newInstance(endpoint);
        while (!input.isAtEnd()) {
            int tag = input.readTag();
            if (tag == 0) break;
            int number = WireFormat.getTagFieldNumber(tag);
            if (WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED && number == 1) {
                key = input.readStringRequireUtf8();
            } else if (WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED
                    && number == identifierNumber) {
                key = stringField(input.readByteArray(), IDENTIFIER_KEY);
            } else if (!input.skipField(tag)) {
                break;
            }
        }
        return key == null || key.isEmpty() ? null : key;
    }

    /** Reads the exact track's first title-run endpoint as the native queue/title readers do. */
    static Related related(byte[] response, String videoId) {
        String path = "none";
        try {
            byte[] contents = field(response, OfflineWatchNext.RESPONSE_CONTENTS_FIELD);
            byte[] music = contents == null ? null : field(contents, OfflineWatchNext.CONTENTS_QUEUE_FIELD);
            if (music == null) return new Related(path, TitlePresence.NO_QUEUE, null);

            byte[] queue = tabbedQueue(music);
            if (queue == null) {
                byte[] legacy = field(music, LEGACY_QUEUE_HOLDER);
                queue = legacy == null ? null : field(legacy, OfflineWatchNext.PANEL_RENDERER_FIELD);
                if (queue == null) return new Related(path, TitlePresence.NO_QUEUE, null);
                path = "legacy";
            } else {
                path = "tabbed";
            }
            return relatedInQueue(queue, path, videoId);
        } catch (IOException malformed) {
            return new Related(path, TitlePresence.MALFORMED, null);
        }
    }

    @Nullable
    private static byte[] tabbedQueue(byte[] music) throws IOException {
        byte[] holder = field(music, OfflineWatchNext.QUEUE_TABS_HOLDER_FIELD);
        if (holder == null) return null;
        byte[] tabs = field(holder, OfflineWatchNext.TABS_RENDERER_FIELD);
        if (tabs == null) return null;

        for (byte[] entry : fields(tabs, OfflineWatchNext.TABS_LIST_FIELD)) {
            byte[] panel = fieldPath(entry, OfflineWatchNext.TAB_FIELD, OfflineWatchNext.TAB_CONTENT_FIELD,
                    OfflineWatchNext.TAB_PANEL_HOLDER_FIELD, OfflineWatchNext.PANEL_HOLDER_RENDERER_FIELD,
                    OfflineWatchNext.PANEL_RENDERER_FIELD);
            if (panel != null) return panel;
        }
        return null;
    }

    private static Related relatedInQueue(byte[] queue, String path, String videoId) throws IOException {
        List<byte[]> items = fields(queue, OfflineWatchNext.PANEL_ITEMS_FIELD);
        Integer index = intField(queue, OfflineWatchNext.PANEL_CURRENT_INDEX_FIELD);
        byte[] track = null;
        if (index != null) {
            if (index >= 0 && index < items.size()) track = matchingTrack(items.get(index), videoId);
        } else {
            for (byte[] item : items) {
                byte[] match = matchingTrack(item, videoId);
                if (match == null) continue;
                if (track != null) return new Related(path, TitlePresence.AMBIGUOUS_TRACK, null);
                track = match;
            }
        }
        if (track == null) return new Related(path, TitlePresence.NO_TRACK, null);

        byte[] title = field(track, OfflineWatchNext.TRACK_TITLE_FIELD);
        if (title == null) return new Related(path, TitlePresence.NO_TITLE, null);
        for (byte[] run : fields(title, OfflineWatchNext.TEXT_RUNS_FIELD)) {
            byte[] command = field(run, RUN_ENDPOINT_FIELD);
            if (command != null) return new Related(path, TitlePresence.PRESENT, command);
        }
        return new Related(path, TitlePresence.NO_COMMAND, null);
    }

    /** Lapbv primary and the first music-video counterpart are the only rows Lhwu.z reads. */
    @Nullable
    private static byte[] matchingTrack(byte[] item, String videoId) throws IOException {
        byte[] wrapper = field(item, ITEM_WRAPPER_FIELD);
        if (wrapper != null) {
            byte[] primary = fieldPath(wrapper, WRAPPER_PRIMARY, OfflineWatchNext.ITEM_TRACK_FIELD);
            if (matchesTrack(primary, videoId)) return primary;
            byte[] counterpart = field(wrapper, WRAPPER_COUNTERPARTS);
            byte[] track = counterpart == null ? null
                    : fieldPath(counterpart, COUNTERPART_RENDERER, OfflineWatchNext.ITEM_TRACK_FIELD);
            return matchesTrack(track, videoId) ? track : null;
        }
        byte[] track = field(item, OfflineWatchNext.ITEM_TRACK_FIELD);
        return matchesTrack(track, videoId) ? track : null;
    }

    private static boolean matchesTrack(@Nullable byte[] track, String videoId) throws IOException {
        return track != null && videoId.equals(stringField(track, OfflineWatchNext.TRACK_VIDEO_ID_FIELD));
    }

    /** The field numbers of a command at its top level, for the log: it says what kind of endpoint it is. */
    static String describeCommand(byte[] command) {
        StringBuilder numbers = new StringBuilder();
        int count = 0;
        try {
            CodedInputStream input = CodedInputStream.newInstance(command);
            while (!input.isAtEnd()) {
                int tag = input.readTag();
                int field = WireFormat.getTagFieldNumber(tag);
                if (field == 0) break;
                if (++count > MAX_LOG_FIELDS) {
                    numbers.append("+…");
                    break;
                }
                if (numbers.length() > 0) numbers.append('+');
                numbers.append(field);
                if (!input.skipField(tag)) break;
            }
        } catch (IOException unreadable) {
            numbers.append('?');
        }
        return numbers.toString();
    }

    /** Every length-delimited field {@code number} of the message, in wire order. */
    private static List<byte[]> fields(byte[] message, int number) throws IOException {
        List<byte[]> found = new ArrayList<>();
        CodedInputStream input = CodedInputStream.newInstance(message);
        while (!input.isAtEnd()) {
            int tag = input.readTag();
            if (tag == 0) break;

            boolean wanted = WireFormat.getTagFieldNumber(tag) == number
                    && WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED;
            if (wanted) found.add(input.readByteArray());
            else if (!input.skipField(tag)) break;
        }
        return found;
    }

    @Nullable
    private static byte[] field(byte[] message, int number) throws IOException {
        CodedInputStream input = CodedInputStream.newInstance(message);
        int wanted = (number << 3) | WireFormat.WIRETYPE_LENGTH_DELIMITED;
        while (!input.isAtEnd()) {
            int tag = input.readTag();
            if (tag == wanted) return input.readByteArray();
            if (tag == 0 || !input.skipField(tag)) break;
        }
        return null;
    }

    @Nullable
    private static byte[] fieldPath(byte[] message, int... path) throws IOException {
        for (int number : path) {
            message = field(message, number);
            if (message == null) return null;
        }
        return message;
    }

    private static boolean hasField(byte[] message, int number) throws IOException {
        CodedInputStream input = CodedInputStream.newInstance(message);
        int wanted = (number << 3) | WireFormat.WIRETYPE_LENGTH_DELIMITED;
        while (!input.isAtEnd()) {
            int tag = input.readTag();
            if (tag == wanted) return true;
            if (tag == 0 || !input.skipField(tag)) break;
        }
        return false;
    }

    @Nullable
    private static String stringField(byte[] message, int number) throws IOException {
        byte[] value = field(message, number);
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    @Nullable
    private static Integer intField(byte[] message, int number) throws IOException {
        CodedInputStream input = CodedInputStream.newInstance(message);
        int wanted = number << 3;
        while (!input.isAtEnd()) {
            int tag = input.readTag();
            if (tag == wanted) return input.readInt32();
            if (tag == 0 || !input.skipField(tag)) break;
        }
        return null;
    }
}
