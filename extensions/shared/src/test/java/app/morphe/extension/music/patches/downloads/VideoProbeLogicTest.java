/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-09.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class VideoProbeLogicTest {
    private static VideoProbeLogic.FormatRow row(String list, int itag, String mime, int bitrate, boolean hasUrl) {
        return new VideoProbeLogic.FormatRow(list, itag, mime, "", 0, 0, 0, bitrate, 0, 0, 0, 0, hasUrl, false,
                0, 0, 0, 0);
    }

    // ---- the marker file ----

    @Test public void theLinesOfTheMarkerAreParsed() {
        List<VideoProbeLogic.Line> lines = VideoProbeLogic.parseLines(
                "# a comment\n\nplayer dQw4w9WgXcQ\r\nnext PLabc dQw4w9WgXcQ\n  fetch dQw4w9WgXcQ 18  \n");

        assertEquals(List.of(new VideoProbeLogic.PlayerLine("dQw4w9WgXcQ"),
                new VideoProbeLogic.NextLine("PLabc", "dQw4w9WgXcQ"),
                new VideoProbeLogic.FetchLine("dQw4w9WgXcQ", 18)), lines);
    }

    @Test public void aBadLineIsKeptAsInvalidWithItsReason() {
        assertTrue(VideoProbeLogic.parseLine("player short") instanceof VideoProbeLogic.InvalidLine);
        assertTrue(VideoProbeLogic.parseLine("fetch dQw4w9WgXcQ x") instanceof VideoProbeLogic.InvalidLine);
        assertTrue(VideoProbeLogic.parseLine("next PLabc") instanceof VideoProbeLogic.InvalidLine);
        assertEquals("unknown form", ((VideoProbeLogic.InvalidLine) VideoProbeLogic.parseLine("go")).reason());
    }

    @Test public void atMostTwentyFourLinesAreUsed() {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < 30; index++) text.append("player dQw4w9WgXc").append(index % 10).append('\n');

        assertEquals(VideoProbeLogic.MAX_LINES, VideoProbeLogic.parseLines(text.toString()).size());
    }

    @Test public void aShapeLineKeepsItsFlagsAndAnUnknownFlagIsInvalid() {
        assertEquals(new VideoProbeLogic.NextShapeLine("PLabc", "dQw4w9WgXcQ", "audioOnly,atvConfig"),
                VideoProbeLogic.parseLine("next2 PLabc dQw4w9WgXcQ audioOnly,atvConfig"));
        assertEquals("unknown flag bogus", ((VideoProbeLogic.InvalidLine) VideoProbeLogic.parseLine(
                "next2 PLabc dQw4w9WgXcQ audioOnly,bogus")).reason());
        assertTrue(VideoProbeLogic.parseLine("next2 PLabc dQw4w9WgXcQ") instanceof VideoProbeLogic.InvalidLine);
        assertEquals("index= needs a number", ((VideoProbeLogic.InvalidLine) VideoProbeLogic.parseLine(
                "next2 PLabc dQw4w9WgXcQ index=x")).reason());
    }

    @Test public void aShapeLineWithoutAPlaylistIsAccepted() {
        assertEquals(new VideoProbeLogic.NextShapeLine("-", "h9ba3KgHguc", "none"),
                VideoProbeLogic.parseLine("next2 - h9ba3KgHguc none"));
        assertEquals(new VideoProbeLogic.NextShapeLine("-", "h9ba3KgHguc", "webRemix,audioOnly"),
                VideoProbeLogic.parseLine("next2 - h9ba3KgHguc webRemix,audioOnly"));
    }

    @Test public void theFlagsBuildTheFieldsOfTheRequest() throws Exception {
        JSONObject minimal = VideoProbeLogic.nextShape("PLabc", "dQw4w9WgXcQ", "none");
        assertEquals(2, minimal.length());
        assertEquals("dQw4w9WgXcQ", minimal.getString("videoId"));
        assertEquals("PLabc", minimal.getString("playlistId"));

        JSONObject full = VideoProbeLogic.nextShape("PLabc", "dQw4w9WgXcQ",
                "audioOnly,persistent,tuner,atvConfig,params=wAEB,index=3");
        assertTrue(full.getBoolean("isAudioOnly"));
        assertTrue(full.getBoolean("enablePersistentPlaylistPanel"));
        assertEquals("AUTOMIX_SETTING_NORMAL", full.getString("tunerSettingValue"));
        assertEquals("wAEB", full.getString("params"));
        assertEquals(3, full.getInt("index"));
        assertEquals("MUSIC_VIDEO_TYPE_ATV", full.getJSONObject("watchEndpointMusicSupportedConfigs")
                .getJSONObject("watchEndpointMusicConfig").getString("musicVideoType"));
    }

    @Test public void theMusicConfigFlagsShareOneConfigAndWebRemixReplacesTheClient() throws Exception {
        JSONObject fields = VideoProbeLogic.nextShape("PLabc", "dQw4w9WgXcQ", "omvConfig,hasPersistent,webRemix");

        JSONObject config = fields.getJSONObject("watchEndpointMusicSupportedConfigs")
                .getJSONObject("watchEndpointMusicConfig");
        assertEquals("MUSIC_VIDEO_TYPE_OMV", config.getString("musicVideoType"));
        assertTrue(config.getBoolean("hasPersistentPlaylistPanel"));
        assertEquals("WEB_REMIX", fields.getJSONObject("context").getJSONObject("client").getString("clientName"));
    }

    @Test public void noPlaylistLeavesOnlyTheVideo() throws Exception {
        JSONObject audio = VideoProbeLogic.nextShape("PLabc", "dQw4w9WgXcQ", "audioOnly,noPlaylist");
        assertEquals(2, audio.length());
        assertTrue(audio.getBoolean("isAudioOnly"));
        assertFalse(audio.has("playlistId"));

        JSONObject bare = VideoProbeLogic.nextShape("-", "dQw4w9WgXcQ", "none");
        assertEquals(1, bare.length());
        assertEquals("dQw4w9WgXcQ", bare.getString("videoId"));
    }

    // ---- log lines ----

    @Test public void aLongLineIsCutWithAMark() {
        String clipped = VideoProbeLogic.clip("x".repeat(500));

        assertEquals(VideoProbeLogic.MAX_LOG_LENGTH, clipped.length());
        assertTrue(clipped.endsWith("..."));
        assertEquals("short", VideoProbeLogic.clip("short"));
    }

    @Test public void onlyTheHostOfAUrlIsKept() {
        assertEquals("rr1---sn.googlevideo.com",
                VideoProbeLogic.hostOf("https://rr1---sn.googlevideo.com/videoplayback?sig=secret&n=token"));
        assertEquals("?", VideoProbeLogic.hostOf("not a url"));
    }

    @Test public void aFormatIsDescribedInOneLine() {
        VideoProbeLogic.FormatRow format = new VideoProbeLogic.FormatRow("formats", 18,
                "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"", "360p", 640, 360, 25, 500000, 0, 7000000, 1700000000L,
                213000, true, false, 0, 0, 0, 0);

        assertEquals("formats[] itag=18 video/mp4;avc1.42001E,mp4a.40.2 360p 640x360@25 br=500000 len=7000000 "
                + "lmt=1700000000 dur=213000 url=Y cipher=N", VideoProbeLogic.describe(format));
    }

    @Test public void anAdaptiveFormatShowsItsRangesAndAnEstimateWhenItsLengthIsMissing() {
        VideoProbeLogic.FormatRow format = new VideoProbeLogic.FormatRow("adaptiveFormats", 251,
                "audio/webm; codecs=\"opus\"", "", 0, 0, 0, 130000, 120000, 0, 5, 200000, false, true,
                0, 265, 266, 1000);

        assertEquals("adaptive[] itag=251 audio/webm;opus br=130000 avg=120000 len=0 est=3000000 lmt=5 dur=200000 "
                + "url=N cipher=Y init=0-265 index=266-1000", VideoProbeLogic.describe(format));
    }

    @Test public void theSizeEstimateNeedsABitrateAndADuration() {
        assertEquals(3_000_000, VideoProbeLogic.estimatedBytes(120_000, 200_000));
        assertEquals(0, VideoProbeLogic.estimatedBytes(0, 200_000));
        assertEquals(0, VideoProbeLogic.estimatedBytes(120_000, 0));
    }

    @Test public void theTotalIsTheNumberAfterTheSlashOfAContentRange() {
        assertEquals(3445462, VideoProbeLogic.totalOf("bytes 0-0/3445462"));
        assertEquals(-1, VideoProbeLogic.totalOf("bytes 0-0/*"));
        assertEquals(-1, VideoProbeLogic.totalOf(null));
    }

    @Test public void theThroughputIsInMegabitsPerSecond() {
        assertEquals("8.0", VideoProbeLogic.throughput(1_000_000, 1000));
        assertEquals("0", VideoProbeLogic.throughput(1_000_000, 0));
    }

    // ---- targets ----

    @Test public void theTargetsAreTheBestOfEachKindThatHasAUrl() {
        List<VideoProbeLogic.FormatRow> rows = List.of(
                row("formats", 18, "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"", 500000, true),
                row("adaptiveFormats", 397, "video/mp4; codecs=\"av01.0.04M.08\"", 900000, true),
                row("adaptiveFormats", 134, "video/mp4; codecs=\"avc1.4d401e\"", 600000, true),
                row("adaptiveFormats", 135, "video/mp4; codecs=\"avc1.4d401f\"", 800000, false),
                row("adaptiveFormats", 140, "audio/mp4; codecs=\"mp4a.40.2\"", 130000, true),
                row("adaptiveFormats", 251, "audio/webm; codecs=\"opus\"", 140000, true));

        List<VideoProbeLogic.Target> targets = VideoProbeLogic.targetsOf(rows);

        List<String> described = new ArrayList<>();
        for (VideoProbeLogic.Target target : targets) described.add(target.role().label + ":" + target.row().itag());
        assertEquals(List.of("muxed:18", "video:397", "video-avc:134", "audio251:251", "audio140:140"), described);
    }

    @Test public void aFormatWithNoUrlIsNeverATarget() {
        List<VideoProbeLogic.FormatRow> rows = List.of(row("formats", 18, "video/mp4; codecs=\"avc1\"", 1, false),
                row("adaptiveFormats", 251, "audio/webm; codecs=\"opus\"", 1, false));

        assertTrue(VideoProbeLogic.targetsOf(rows).isEmpty());
    }

    @Test public void theBestVideoThatIsAvcIsNotListedTwice() {
        List<VideoProbeLogic.FormatRow> rows = List.of(
                row("adaptiveFormats", 137, "video/mp4; codecs=\"avc1.640028\"", 4000000, true));

        assertEquals(1, VideoProbeLogic.targetsOf(rows).size());
    }

    // ---- fields of a message with no schema ----

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int index = 0; index < values.length; index++) bytes[index] = (byte) values[index];
        return bytes;
    }

    @Test public void theScalarFieldsOfAMessageAreListed() throws Exception {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        message.write(bytes(0x08, 0xac, 0x02)); // field 1 varint 300
        message.write(bytes(0x12, 0x03)); // field 2 string "abc"
        message.write("abc".getBytes(StandardCharsets.UTF_8));
        message.write(bytes(0x9a, 0x02, 0x14)); // field 35 string of 20 bytes
        message.write("MUSIC_VIDEO_TYPE_OMV".getBytes(StandardCharsets.UTF_8));
        message.write(bytes(0x1a, 0x02, 0x01, 0x02)); // field 3, not printable: left out

        assertEquals("f1=300 f2=abc f35=MUSIC_VIDEO_TYPE_OMV", VideoProbeLogic.scalarFields(message.toByteArray()));
    }

    @Test public void aTruncatedMessageKeepsWhatWasRead() {
        assertEquals("f1=5", VideoProbeLogic.scalarFields(bytes(0x08, 0x05, 0x12, 0x09, 0x61)));
    }

    // ---- containers ----

    private static VideoProbeLogic.Reader reader(byte[] data) {
        return (position, buffer, length) -> {
            if (position >= data.length) return -1;

            int count = (int) Math.min(length, data.length - position);
            System.arraycopy(data, (int) position, buffer, 0, count);
            return count;
        };
    }

    private static byte[] box(String type, int size) {
        byte[] box = new byte[size];
        box[3] = (byte) size;
        System.arraycopy(type.getBytes(StandardCharsets.ISO_8859_1), 0, box, 4, 4);
        return box;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (byte[] part : parts) all.write(part, 0, part.length);
        return all.toByteArray();
    }

    @Test public void theTopLevelBoxesOfAnMp4AreListedInOrder() throws Exception {
        byte[] file = concat(box("ftyp", 24), box("moov", 100), box("mdat", 60));

        List<VideoProbeLogic.Box> boxes = VideoProbeLogic.mp4Boxes(reader(file), file.length);

        assertEquals("ftyp:24 moov:100 mdat:60 (moov before mdat)", VideoProbeLogic.describeBoxes(boxes));
        assertEquals(124, boxes.get(2).offset());
    }

    @Test public void anMp4WithMoovAfterMdatIsSaidSo() throws Exception {
        byte[] file = concat(box("ftyp", 24), box("mdat", 60), box("moov", 100));

        assertEquals("ftyp:24 mdat:60 moov:100 (moov after mdat)",
                VideoProbeLogic.describeBoxes(VideoProbeLogic.mp4Boxes(reader(file), file.length)));
    }

    @Test public void aBoxOfSizeZeroRunsToTheEndOfTheFile() throws Exception {
        byte[] last = box("mdat", 50);
        last[3] = 0;
        byte[] file = concat(box("ftyp", 24), last);

        assertEquals("ftyp:24 mdat:50",
                VideoProbeLogic.describeBoxes(VideoProbeLogic.mp4Boxes(reader(file), file.length)));
    }

    @Test public void aFileThatIsNotMp4HasNoBoxes() throws Exception {
        byte[] file = new byte[40];
        file[3] = 4; // a size below the 8 bytes of a box header

        assertEquals("(no boxes)", VideoProbeLogic.describeBoxes(VideoProbeLogic.mp4Boxes(reader(file), 40)));
    }

    @Test public void aWebmFileShowsItsSegmentAndCues() throws Exception {
        byte[] ebml = bytes(0x1a, 0x45, 0xdf, 0xa3, 0x84, 1, 2, 3, 4); // header with 4 bytes of data
        byte[] segment = bytes(0x18, 0x53, 0x80, 0x67, 0x9e); // segment, size 30
        byte[] info = bytes(0x15, 0x49, 0xa9, 0x66, 0x82, 0, 0); // info, 2 bytes
        byte[] cues = bytes(0x1c, 0x53, 0xbb, 0x6b, 0x81, 0); // cues, 1 byte
        byte[] file = concat(ebml, segment, info, cues);

        assertEquals("ebml=Y segment@9 size=30 children=2 [1549a966,1c53bb6b] cues@" + (9 + 5 + 7),
                VideoProbeLogic.describeWebm(reader(file), file.length));
    }

    @Test public void aFileWithoutAnEbmlHeaderIsNotWebm() throws Exception {
        byte[] file = bytes(0, 0, 0, 0x18, 'f', 't', 'y', 'p');

        assertEquals("ebml=N", VideoProbeLogic.describeWebm(reader(file), file.length));
    }

    @Test public void theFirstBytesAreHex() {
        assertEquals("1a45dfa3", VideoProbeLogic.hex(bytes(0x1a, 0x45, 0xdf, 0xa3, 0x01), 4));
        assertEquals("0a", VideoProbeLogic.hex(bytes(0x0a), 32));
        assertFalse(Arrays.asList(VideoProbeLogic.hex(bytes(0xff), 1)).isEmpty());
    }
}
