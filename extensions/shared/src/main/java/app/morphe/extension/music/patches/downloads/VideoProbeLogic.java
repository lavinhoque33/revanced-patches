/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-09.
 */
package app.morphe.extension.music.patches.downloads;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The pure parts of the TEMPORARY video probe ({@link VideoProbe}): the marker file's lines, how a format and a
 * container are described in one log line, which formats are worth a ranged request, and how a {@code /next}
 * response is summarised. Contains no Android code. REMOVE together with the probe before the regular release.
 */
final class VideoProbeLogic {
    /** The most lines of the marker file that are used. */
    static final int MAX_LINES = 12;
    /** The longest log line, in characters. */
    static final int MAX_LOG_LENGTH = 300;

    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern PLAYLIST_ID = Pattern.compile("[A-Za-z0-9_-]{2,64}");
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,6}");

    private VideoProbeLogic() {}

    // ---- the marker file ----

    /** One line of the marker file. */
    interface Line {}

    record PlayerLine(String videoId) implements Line {}

    record NextLine(String playlistId, String videoId) implements Line {}

    record FetchLine(String videoId, int itag) implements Line {}

    /** A line that is none of the forms; {@code reason} says why. */
    record InvalidLine(String text, String reason) implements Line {}

    /** The used lines of the marker file's text: blank and {@code #} lines are skipped, at most {@link #MAX_LINES}. */
    static List<Line> parseLines(String text) {
        List<Line> lines = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (lines.size() >= MAX_LINES) break;

            lines.add(parseLine(line));
        }
        return lines;
    }

    static Line parseLine(String line) {
        String[] words = line.trim().split("\\s+");
        switch (words[0]) {
            case "player" -> {
                if (words.length == 2 && VIDEO_ID.matcher(words[1]).matches()) return new PlayerLine(words[1]);
                return new InvalidLine(line, "expected: player <videoId>");
            }
            case "next" -> {
                if (words.length == 3 && PLAYLIST_ID.matcher(words[1]).matches()
                        && VIDEO_ID.matcher(words[2]).matches()) {
                    return new NextLine(words[1], words[2]);
                }
                return new InvalidLine(line, "expected: next <playlistId> <videoId>");
            }
            case "fetch" -> {
                if (words.length == 3 && VIDEO_ID.matcher(words[1]).matches()
                        && DIGITS.matcher(words[2]).matches()) {
                    return new FetchLine(words[1], Integer.parseInt(words[2]));
                }
                return new InvalidLine(line, "expected: fetch <videoId> <itag>");
            }
            default -> {
                return new InvalidLine(line, "unknown form");
            }
        }
    }

    // ---- log lines ----

    /** {@code text} cut to {@link #MAX_LOG_LENGTH} characters, with a mark when it was cut. */
    static String clip(String text) {
        if (text.length() <= MAX_LOG_LENGTH) return text;
        return text.substring(0, MAX_LOG_LENGTH - 3) + "...";
    }

    /** The host of a url and nothing else: a url carries tokens that must not be logged. */
    static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "?" : host;
        } catch (RuntimeException ex) {
            return "?";
        }
    }

    // ---- formats ----

    /** The facts of one {@code Format} of a player response; a number that is absent is 0. */
    record FormatRow(String list, int itag, String mime, String quality, int width, int height, int fps,
                     int bitrate, int averageBitrate, long contentLength, long lastModified, long durationMs,
                     boolean hasUrl, boolean hasCipher, long initStart, long initEnd,
                     long indexStart, long indexEnd) {
        boolean isAudio() {
            return mime.startsWith("audio/");
        }

        boolean isVideo() {
            return mime.startsWith("video/");
        }

        /** An adaptive video format has no audio; a muxed one (in {@code formats}) carries both. */
        boolean isVideoOnly() {
            return isVideo() && "adaptiveFormats".equals(list);
        }

        boolean isMuxed() {
            return isVideo() && "formats".equals(list);
        }
    }

    /** One format as a log line body: {@code formats[] itag=18 video/mp4;avc1,mp4a 360p 640x360@30 ...}. */
    static String describe(FormatRow row) {
        StringBuilder text = new StringBuilder(row.list().equals("formats") ? "formats[]" : "adaptive[]");
        text.append(" itag=").append(row.itag());
        text.append(' ').append(compactMime(row.mime()));
        if (!row.quality().isEmpty()) text.append(' ').append(row.quality());
        if (row.width() > 0 || row.height() > 0) {
            text.append(' ').append(row.width()).append('x').append(row.height());
            if (row.fps() > 0) text.append('@').append(row.fps());
        }
        text.append(" br=").append(row.bitrate());
        if (row.averageBitrate() > 0) text.append(" avg=").append(row.averageBitrate());
        text.append(" len=").append(row.contentLength());
        if (row.contentLength() <= 0) {
            long estimate = estimatedBytes(row.averageBitrate() > 0 ? row.averageBitrate() : row.bitrate(),
                    row.durationMs());
            if (estimate > 0) text.append(" est=").append(estimate);
        }
        text.append(" lmt=").append(row.lastModified());
        text.append(" dur=").append(row.durationMs());
        text.append(" url=").append(row.hasUrl() ? 'Y' : 'N');
        text.append(" cipher=").append(row.hasCipher() ? 'Y' : 'N');
        if (row.initEnd() > 0 || row.indexEnd() > 0) {
            text.append(" init=").append(row.initStart()).append('-').append(row.initEnd());
            text.append(" index=").append(row.indexStart()).append('-').append(row.indexEnd());
        }
        return text.toString();
    }

    /** {@code video/mp4; codecs="avc1.4d401e, mp4a.40.2"} as {@code video/mp4;avc1.4d401e,mp4a.40.2}. */
    static String compactMime(String mime) {
        return mime.replace("; codecs=\"", ";").replace("\"", "").replace(", ", ",");
    }

    /** The size a stream of this bitrate has over this time, or 0 when either is unknown. */
    static long estimatedBytes(long bitsPerSecond, long durationMs) {
        if (bitsPerSecond <= 0 || durationMs <= 0) return 0;
        return bitsPerSecond * durationMs / 8000;
    }

    /** Why a format is probed with a ranged request. */
    enum Role {
        MUXED("muxed"), VIDEO("video"), VIDEO_AVC("video-avc"), AUDIO_251("audio251"), AUDIO_140("audio140");

        final String label;

        Role(String label) {
            this.label = label;
        }
    }

    record Target(Role role, FormatRow row) {}

    /**
     * The formats a ranged request settles "downloadable with a plain url and its true size?" for: the best muxed
     * format, the best video-only format, the best video-only H.264 (mp4) format when it is another one, and the
     * itag 251 and 140 audio. Only formats with a url count; best is the highest bitrate.
     */
    static List<Target> targetsOf(List<FormatRow> rows) {
        List<Target> targets = new ArrayList<>();
        FormatRow muxed = best(rows, FormatRow::isMuxed);
        if (muxed != null) targets.add(new Target(Role.MUXED, muxed));

        FormatRow video = best(rows, FormatRow::isVideoOnly);
        if (video != null) targets.add(new Target(Role.VIDEO, video));

        FormatRow avc = best(rows, row -> row.isVideoOnly() && row.mime().contains("avc1"));
        if (avc != null && avc != video) targets.add(new Target(Role.VIDEO_AVC, avc));

        FormatRow opus = itagOf(rows, 251);
        if (opus != null) targets.add(new Target(Role.AUDIO_251, opus));

        FormatRow aac = itagOf(rows, 140);
        if (aac != null) targets.add(new Target(Role.AUDIO_140, aac));
        return targets;
    }

    private interface Filter {
        boolean accepts(FormatRow row);
    }

    private static FormatRow best(List<FormatRow> rows, Filter filter) {
        FormatRow best = null;
        for (FormatRow row : rows) {
            if (!row.hasUrl() || !filter.accepts(row)) continue;
            if (best == null || row.bitrate() > best.bitrate()) best = row;
        }
        return best;
    }

    private static FormatRow itagOf(List<FormatRow> rows, int itag) {
        for (FormatRow row : rows) {
            if (row.itag() == itag && row.hasUrl()) return row;
        }
        return null;
    }

    /** The first format with this itag and a url, in the order given (muxed formats first), or null. */
    static FormatRow withItag(List<FormatRow> rows, int itag) {
        return itagOf(rows, itag);
    }

    /** The total size of a {@code Content-Range: bytes 0-0/12345} value, or -1 when it names none. */
    static long totalOf(String contentRange) {
        if (contentRange == null) return -1;

        int slash = contentRange.lastIndexOf('/');
        if (slash < 0) return -1;
        try {
            return Long.parseLong(contentRange.substring(slash + 1).trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /** Bytes per second as megabits of a transfer, one decimal; 0 when no time passed. */
    static String throughput(long bytes, long millis) {
        if (millis <= 0) return "0";
        return String.format(Locale.ROOT, "%.1f", bytes * 8.0 / 1000.0 / millis);
    }

    // ---- the raw fields of a message ----

    /**
     * The scalar fields of a protobuf message that has no schema here, as {@code f39=MUSIC_VIDEO_TYPE_OMV} for a
     * short printable string and {@code f5=300} for a varint; other fields are left out. The video details carry the
     * music video type as a field the schema does not name, and this finds it by its value.
     */
    static String scalarFields(byte[] message) {
        StringBuilder text = new StringBuilder();
        int position = 0;
        try {
            while (position < message.length) {
                long tag = varint(message, position);
                position += varintLength(message, position);
                int field = (int) (tag >>> 3);
                switch ((int) (tag & 7)) {
                    case 0 -> {
                        long value = varint(message, position);
                        position += varintLength(message, position);
                        text.append(" f").append(field).append('=').append(value);
                    }
                    case 1 -> position += 8;
                    case 5 -> position += 4;
                    case 2 -> {
                        int length = (int) varint(message, position);
                        position += varintLength(message, position);
                        if (length < 0 || position + length > message.length) return text.toString().trim();
                        if (length <= 40 && printable(message, position, length)) {
                            text.append(" f").append(field).append('=')
                                    .append(new String(message, position, length, StandardCharsets.UTF_8));
                        }
                        position += length;
                    }
                    default -> {
                        return text.toString().trim();
                    }
                }
            }
        } catch (IndexOutOfBoundsException | IllegalArgumentException ex) {
            // A truncated message ends the listing; what was read stays.
        }
        return text.toString().trim();
    }

    private static boolean printable(byte[] bytes, int start, int length) {
        if (length == 0) return false;
        for (int index = start; index < start + length; index++) {
            if (bytes[index] < 0x20 || bytes[index] > 0x7e) return false;
        }
        return true;
    }

    private static long varint(byte[] bytes, int start) {
        long value = 0;
        for (int shift = 0, index = start; shift < 64; shift += 7, index++) {
            byte next = bytes[index];
            value |= (long) (next & 0x7f) << shift;
            if ((next & 0x80) == 0) return value;
        }
        throw new IllegalArgumentException("varint too long");
    }

    private static int varintLength(byte[] bytes, int start) {
        int length = 1;
        while ((bytes[start + length - 1] & 0x80) != 0) length++;
        return length;
    }

    // ---- containers ----

    /** Reads bytes of a file at a position; returns how many were read, 0 or less at the end. */
    interface Reader {
        int read(long position, byte[] buffer, int length) throws IOException;
    }

    /** A box of an mp4 file: its four-letter type, its size and where it starts. */
    record Box(String type, long size, long offset) {}

    /** The most top-level boxes or elements listed. */
    static final int MAX_ITEMS = 40;

    /** The top-level boxes of an mp4 file in file order (size 0 = to the end of the file, 1 = 64-bit size). */
    static List<Box> mp4Boxes(Reader reader, long fileLength) throws IOException {
        List<Box> boxes = new ArrayList<>();
        long position = 0;
        byte[] head = new byte[16];
        while (position + 8 <= fileLength && boxes.size() < MAX_ITEMS) {
            if (reader.read(position, head, 16) < 8) break;

            long size = u32(head, 0);
            String type = new String(head, 4, 4, StandardCharsets.ISO_8859_1);
            if (size == 1) size = u64(head, 8);
            else if (size == 0) size = fileLength - position;
            if (size < 8) break;

            boxes.add(new Box(type, size, position));
            position += size;
        }
        return boxes;
    }

    /** {@code ftyp:32 moov:1200 mdat:900000 (moov before mdat)} for a list of boxes. */
    static String describeBoxes(List<Box> boxes) {
        StringBuilder text = new StringBuilder();
        int moov = -1;
        int mdat = -1;
        for (int index = 0; index < boxes.size(); index++) {
            Box box = boxes.get(index);
            if (text.length() > 0) text.append(' ');
            text.append(box.type()).append(':').append(box.size());
            if (box.type().equals("moov") && moov < 0) moov = index;
            if (box.type().equals("mdat") && mdat < 0) mdat = index;
        }
        if (moov >= 0 && mdat >= 0) text.append(moov < mdat ? " (moov before mdat)" : " (moov after mdat)");
        else if (boxes.isEmpty()) text.append("(no boxes)");
        return text.toString();
    }

    private static final long EBML_ID = 0x1A45DFA3L;
    private static final long SEGMENT_ID = 0x18538067L;
    private static final long CUES_ID = 0x1C53BB6BL;

    /** What a webm file's top level looks like: {@code ebml=Y segment@40 size=123 cues@3000 children=6}. */
    static String describeWebm(Reader reader, long fileLength) throws IOException {
        byte[] head = new byte[16];
        if (reader.read(0, head, 8) < 4) return "ebml=N (file too short)";

        long firstId = elementId(head, 0);
        if (firstId != EBML_ID) return "ebml=N";

        long position = 0;
        long segmentStart = -1;
        long segmentSize = -1;
        for (int items = 0; items < MAX_ITEMS && position + 2 <= fileLength; items++) {
            if (reader.read(position, head, 16) < 2) break;

            int idLength = vintLength(head[0]);
            if (idLength == 0 || idLength > 4) break;
            long id = elementId(head, 0);
            int sizeLength = vintLength(head[idLength]);
            if (sizeLength == 0 || idLength + sizeLength > head.length) break;
            long size = vintValue(head, idLength, sizeLength);
            long dataStart = position + idLength + sizeLength;
            if (id == SEGMENT_ID) {
                segmentStart = position;
                segmentSize = size;
                break;
            }
            position = dataStart + size;
        }
        if (segmentStart < 0) return "ebml=Y segment=not found";

        return "ebml=Y segment@" + segmentStart + " size=" + segmentSize + " " + segmentChildren(reader, fileLength,
                segmentStart);
    }

    /** The children of the segment at {@code segmentStart}: how many were listed and where the Cues are. */
    private static String segmentChildren(Reader reader, long fileLength, long segmentStart) throws IOException {
        byte[] head = new byte[16];
        reader.read(segmentStart, head, 16);
        int idLength = vintLength(head[0]);
        int sizeLength = vintLength(head[idLength]);
        long position = segmentStart + idLength + sizeLength;
        long cues = -1;
        int children = 0;
        StringBuilder ids = new StringBuilder();
        while (children < MAX_ITEMS && position + 2 <= fileLength) {
            if (reader.read(position, head, 16) < 2) break;

            int childIdLength = vintLength(head[0]);
            if (childIdLength == 0 || childIdLength > 4) break;
            long id = elementId(head, 0);
            int childSizeLength = vintLength(head[childIdLength]);
            if (childSizeLength == 0 || childIdLength + childSizeLength > head.length) break;
            long size = vintValue(head, childIdLength, childSizeLength);
            if (id == CUES_ID && cues < 0) cues = position;
            if (ids.length() < 80) ids.append(ids.length() > 0 ? "," : "").append(Long.toHexString(id));
            children++;
            if (size < 0) break;
            position += childIdLength + childSizeLength + size;
        }
        return "children=" + children + " [" + ids + "] cues" + (cues >= 0 ? "@" + cues : "=not found");
    }

    /** The length of an EBML variable-size integer from its first byte, or 0 when the byte is 0. */
    private static int vintLength(byte first) {
        int value = first & 0xff;
        return value == 0 ? 0 : Integer.numberOfLeadingZeros(value) - 23;
    }

    /** An element id: the bytes with the length marker kept. */
    private static long elementId(byte[] bytes, int start) {
        int length = vintLength(bytes[start]);
        long id = 0;
        for (int index = 0; index < length; index++) id = (id << 8) | (bytes[start + index] & 0xff);
        return id;
    }

    /** An element size: the value with the length marker cleared; -1 for the unknown size. */
    private static long vintValue(byte[] bytes, int start, int length) {
        long value = bytes[start] & (0xffL >> length);
        boolean allOnes = value == (0xffL >> length);
        for (int index = 1; index < length; index++) {
            int next = bytes[start + index] & 0xff;
            allOnes &= next == 0xff;
            value = (value << 8) | next;
        }
        return allOnes ? -1 : value;
    }

    private static long u32(byte[] bytes, int start) {
        return ((long) (bytes[start] & 0xff) << 24) | ((bytes[start + 1] & 0xff) << 16)
                | ((bytes[start + 2] & 0xff) << 8) | (bytes[start + 3] & 0xff);
    }

    private static long u64(byte[] bytes, int start) {
        return (u32(bytes, start) << 32) | u32(bytes, start + 4);
    }

    /** The first {@code count} bytes as lowercase hex. */
    static String hex(byte[] bytes, int count) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < Math.min(count, bytes.length); index++) {
            text.append(String.format(Locale.ROOT, "%02x", bytes[index] & 0xff));
        }
        return text.toString();
    }

    // ---- /next ----

    /** One item of a watch queue: what the primary renderer is and what its counterpart is, if it has one. */
    record NextItem(int index, String kind, String videoId, String type, String counterpartId,
                    String counterpartType, String length) {
        boolean hasCounterpart() {
            return !counterpartId.isEmpty();
        }
    }

    /** The queue a {@code /next} response (or its continuation) lists, and the token of the next page. */
    record NextPage(List<NextItem> items, String continuation, int automix, List<String> otherKinds) {}

    private static final String WRAPPER = "playlistPanelVideoWrapperRenderer";
    private static final String PLAIN = "playlistPanelVideoRenderer";
    private static final String AUTOMIX = "automixPreviewVideoRenderer";

    /** Reads the queue of a {@code /next} response; {@code firstIndex} numbers its first item. */
    static NextPage readNext(JSONObject response, int firstIndex) {
        JSONArray contents = findContents(response);
        List<NextItem> items = new ArrayList<>();
        List<String> otherKinds = new ArrayList<>();
        int automix = 0;
        for (int index = 0; contents != null && index < contents.length(); index++) {
            JSONObject entry = contents.optJSONObject(index);
            if (entry == null) continue;

            if (entry.has(WRAPPER)) {
                items.add(readWrapper(entry.optJSONObject(WRAPPER), firstIndex + items.size()));
            } else if (entry.has(PLAIN)) {
                JSONObject renderer = entry.optJSONObject(PLAIN);
                items.add(new NextItem(firstIndex + items.size(), "plain", videoIdOf(renderer), typeOf(renderer),
                        "", "", lengthOf(renderer)));
            } else if (entry.has(AUTOMIX)) {
                automix++;
            } else {
                String key = entry.keys().hasNext() ? entry.keys().next() : "?";
                if (!otherKinds.contains(key)) otherKinds.add(key);
            }
        }
        return new NextPage(items, continuationOf(response), automix, otherKinds);
    }

    private static NextItem readWrapper(JSONObject wrapper, int index) {
        JSONObject primary = wrapper == null ? null : wrapper.optJSONObject("primaryRenderer");
        JSONObject renderer = primary == null ? null : primary.optJSONObject(PLAIN);
        String counterpartId = "";
        String counterpartType = "";
        JSONArray counterparts = wrapper == null ? null : wrapper.optJSONArray("counterpart");
        for (int position = 0; counterparts != null && position < counterparts.length(); position++) {
            JSONObject counterpart = counterparts.optJSONObject(position);
            JSONObject holder = counterpart == null ? null : counterpart.optJSONObject("counterpartRenderer");
            JSONObject other = holder == null ? null : holder.optJSONObject(PLAIN);
            if (other == null) continue;

            counterpartId = videoIdOf(other);
            counterpartType = typeOf(other);
            break;
        }
        return new NextItem(index, "wrapper", videoIdOf(renderer), typeOf(renderer), counterpartId, counterpartType,
                lengthOf(renderer));
    }

    private static String videoIdOf(JSONObject renderer) {
        return renderer == null ? "" : renderer.optString("videoId");
    }

    private static String typeOf(JSONObject renderer) {
        JSONObject endpoint = renderer == null ? null : renderer.optJSONObject("navigationEndpoint");
        JSONObject watch = endpoint == null ? null : endpoint.optJSONObject("watchEndpoint");
        JSONObject supported = watch == null ? null : watch.optJSONObject("watchEndpointMusicSupportedConfigs");
        JSONObject config = supported == null ? null : supported.optJSONObject("watchEndpointMusicConfig");
        String type = config == null ? "" : config.optString("musicVideoType");
        return type.replace("MUSIC_VIDEO_TYPE_", "");
    }

    private static String lengthOf(JSONObject renderer) {
        JSONObject text = renderer == null ? null : renderer.optJSONObject("lengthText");
        JSONArray runs = text == null ? null : text.optJSONArray("runs");
        JSONObject run = runs == null ? null : runs.optJSONObject(0);
        return run == null ? "" : run.optString("text");
    }

    /** The queue's items: the first list under a {@code playlistPanelRenderer} or {@code playlistPanelContinuation}. */
    private static JSONArray findContents(Object node) {
        if (node instanceof JSONObject object) {
            for (String key : new String[]{"playlistPanelRenderer", "playlistPanelContinuation"}) {
                JSONObject panel = object.optJSONObject(key);
                if (panel != null && panel.optJSONArray("contents") != null) return panel.optJSONArray("contents");
            }
            var keys = object.keys();
            while (keys.hasNext()) {
                JSONArray found = findContents(object.opt(keys.next()));
                if (found != null) return found;
            }
        } else if (node instanceof JSONArray array) {
            for (int index = 0; index < array.length(); index++) {
                JSONArray found = findContents(array.opt(index));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** The token that asks for the next page of the queue, or an empty string when there is none. */
    private static String continuationOf(Object node) {
        if (node instanceof JSONObject object) {
            for (String key : new String[]{"nextRadioContinuationData", "nextContinuationData"}) {
                JSONObject data = object.optJSONObject(key);
                if (data != null && !data.optString("continuation").isEmpty()) return data.optString("continuation");
            }
            var keys = object.keys();
            while (keys.hasNext()) {
                String found = continuationOf(object.opt(keys.next()));
                if (!found.isEmpty()) return found;
            }
        } else if (node instanceof JSONArray array) {
            for (int index = 0; index < array.length(); index++) {
                String found = continuationOf(array.opt(index));
                if (!found.isEmpty()) return found;
            }
        }
        return "";
    }

    /** One item as a log line body. */
    static String describe(NextItem item) {
        StringBuilder text = new StringBuilder("item ").append(item.index());
        text.append(' ').append(item.kind()).append(" id=").append(item.videoId());
        text.append(" type=").append(item.type().isEmpty() ? "-" : item.type());
        if (item.hasCounterpart()) {
            text.append(" counterpart=").append(item.counterpartId());
            text.append(" counterpartType=").append(item.counterpartType().isEmpty() ? "-" : item.counterpartType());
        } else {
            text.append(" counterpart=none");
        }
        if (!item.length().isEmpty()) text.append(" len=").append(item.length());
        return text.toString();
    }

    /** The totals of one page as a log line body. */
    static String describe(NextPage page) {
        int wrappers = 0;
        int counterparts = 0;
        for (NextItem item : page.items()) {
            if (item.kind().equals("wrapper")) wrappers++;
            if (item.hasCounterpart()) counterparts++;
        }
        return "items=" + page.items().size() + " wrappers=" + wrappers + " withCounterpart=" + counterparts
                + " automix=" + page.automix() + " otherKinds=" + page.otherKinds()
                + " continuation=" + (page.continuation().isEmpty() ? "no" : "yes");
    }
}
