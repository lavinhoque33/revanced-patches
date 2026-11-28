/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-26.
 * Written by lavinhoque33, 2026-11-08.
 * Modified by lavinhoque33, 2026-11-09.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class OfflineWatchCommandTest {
    private static final String VIDEO = "abcdefghijk";
    private static final String PLAYLIST = "PPSVrvx:songs";

    private static byte[] endpoint(byte[] command) throws Exception {
        assertEquals(List.of(57_703_777), ProtoFields.numbers(command));
        return ProtoFields.one(command, 57_703_777);
    }

    @Test public void theCommandIsTheOfflineWatchEndpointAlone() throws Exception {
        byte[] command = OfflineWatchCommand.build(VIDEO, PLAYLIST, 2);

        assertEquals(List.of(57_703_777), ProtoFields.numbers(command));
    }

    @Test public void theEndpointTagIsTheOneTheSpecWrites() {
        byte[] command = OfflineWatchCommand.build(VIDEO, PLAYLIST, 2);

        assertArrayEquals(new byte[]{(byte) 0x8a, (byte) 0xd6, (byte) 0x8f, (byte) 0xdc, 0x01},
                Arrays.copyOf(command, 5));
    }

    @Test public void theEndpointCarriesVideoPlaylistIndexAndConfigOnly() throws Exception {
        byte[] endpoint = endpoint(OfflineWatchCommand.build(VIDEO, PLAYLIST, 5));

        assertEquals(List.of(1, 2, 3, 10), ProtoFields.numbers(endpoint));
        assertEquals(VIDEO, ProtoFields.string(endpoint, 1));
        assertEquals(PLAYLIST, ProtoFields.string(endpoint, 2));
        assertEquals(5, ProtoFields.varint(endpoint, 3));
    }

    @Test public void theConfigOnlySaysSongAndNeverTheFlagsOrTheStartTime() throws Exception {
        byte[] endpoint = endpoint(OfflineWatchCommand.build(VIDEO, PLAYLIST, 0));
        byte[] config = ProtoFields.one(endpoint, 10);

        assertEquals(List.of(6), ProtoFields.numbers(config));
        assertEquals(1, ProtoFields.varint(config, 6));
        assertFalse(ProtoFields.has(endpoint, 7));
        assertFalse(ProtoFields.has(config, 5));
        assertFalse(ProtoFields.has(config, 7));
    }

    @Test public void onlyTheSyntheticPlaylistStartCarriesTheNativeShuffleBit() throws Exception {
        String source = "PPSVrvx:PLsaved_header";
        byte[] plain = OfflineWatchCommand.build(VIDEO, source, 0, 2);
        byte[] shuffled = OfflineWatchCommand.build(VIDEO, source, 0, 2, true);
        byte[] config = ProtoFields.one(endpoint(shuffled), 10);

        assertFalse(ProtoFields.has(ProtoFields.one(endpoint(plain), 10), 3));
        assertEquals(1, ProtoFields.varint(config, 3));
        assertEquals(2, ProtoFields.varint(config, 6));
        assertTrue(OfflineReplayLogic.isShuffleStart(shuffled, OfflineReplayLogic.offlineWatchOf(shuffled)));
        assertFalse(OfflineReplayLogic.isShuffleStart(plain, OfflineReplayLogic.offlineWatchOf(plain)));

        for (String other : List.of("PLsaved_header", "PPSVrvx:songs", "PPSVrvx:MPREb_album")) {
            byte[] nativeOrOther = OfflineWatchCommand.build(VIDEO, other, 0, 2, true);
            assertFalse(OfflineReplayLogic.isShuffleStart(nativeOrOther,
                    OfflineReplayLogic.offlineWatchOf(nativeOrOther)));
        }
        byte[] later = OfflineWatchCommand.build(VIDEO, source, 1, 2, true);
        assertFalse(OfflineReplayLogic.isShuffleStart(later, OfflineReplayLogic.offlineWatchOf(later)));
    }

    @Test public void aDownloadedAlbumShuffleStartCarriesTheNativeShuffleBitToo() throws Exception {
        byte[] album = OfflineWatchCommand.build(VIDEO, "PPSVrvx:OLAK5uy_album", 0, 2, true);
        byte[] plain = OfflineWatchCommand.build(VIDEO, "PPSVrvx:OLAK5uy_album", 0, 2);

        assertTrue(OfflineReplayLogic.isShuffleStart(album, OfflineReplayLogic.offlineWatchOf(album)));
        assertFalse(OfflineReplayLogic.isShuffleStart(plain, OfflineReplayLogic.offlineWatchOf(plain)));
    }

    /** What the app's builder does to the command: the endpoint gets a start time, the other fields are kept. */
    private static byte[] withStartTime(byte[] command) throws Exception {
        byte[] endpoint = ProtoFields.one(command, 57_703_777);
        byte[] rebuilt = new ProtoWriter().raw(endpoint, 0, endpoint.length)
                .fixed32(7, Float.floatToIntBits(12.0f)).toByteArray();
        byte[] copy = new ProtoWriter().message(57_703_777, rebuilt).toByteArray();
        long token = OfflineReplayLogic.dispatchTokenOf(command);
        return token == 0 ? copy : OfflineReplayLogic.withDispatchToken(copy, token);
    }

    private static byte[] plannedStart(long token) {
        byte[] start = OfflineWatchCommand.build(VIDEO, "PPSVrvx:PLsaved_header", 0, 2, true);
        return OfflineReplayLogic.withDispatchToken(start, token);
    }

    @Test public void aRebuiltCopyOfThePlannedShuffleStartIsStillTheStart() throws Exception {
        byte[] planned = plannedStart(7);

        assertTrue(OfflineReplayLogic.isPlannedShuffleStart(planned, planned));
        assertTrue(OfflineReplayLogic.isPlannedShuffleStart(withStartTime(planned), planned));
    }

    @Test public void onlyThePlannedShuffleStartMatchesItsPlan() throws Exception {
        String source = "PPSVrvx:PLsaved_header";
        byte[] planned = plannedStart(7);

        byte[] plainStart = OfflineReplayLogic.withDispatchToken(OfflineWatchCommand.build(VIDEO, source, 0, 2), 7);
        byte[] otherPlaylist = OfflineReplayLogic.withDispatchToken(
                OfflineWatchCommand.build(VIDEO, "PPSVrvx:PLother", 0, 2, true), 7);
        byte[] otherSong = OfflineReplayLogic.withDispatchToken(
                OfflineWatchCommand.build("zzzzzzzzzzz", source, 0, 2, true), 7);
        byte[] laterRow = OfflineReplayLogic.withDispatchToken(OfflineWatchCommand.build(VIDEO, source, 1, 2, true), 7);

        assertFalse(OfflineReplayLogic.isPlannedShuffleStart(plainStart, planned));
        assertFalse(OfflineReplayLogic.isPlannedShuffleStart(withStartTime(plainStart), planned));
        assertFalse(OfflineReplayLogic.isPlannedShuffleStart(otherPlaylist, planned));
        assertFalse(OfflineReplayLogic.isPlannedShuffleStart(otherSong, planned));
        assertFalse(OfflineReplayLogic.isPlannedShuffleStart(laterRow, planned));
        assertFalse(OfflineReplayLogic.isPlannedShuffleStart(new byte[0], planned));
    }

    @Test public void aStartOfAnotherPressWithTheSameBytesIsNotThePlannedStart() throws Exception {
        byte[] firstPress = plannedStart(1);
        byte[] secondPress = plannedStart(2);

        assertTrue(OfflineReplayLogic.isPlannedShuffleStart(withStartTime(firstPress), firstPress));
        assertFalse(OfflineReplayLogic.isPlannedShuffleStart(withStartTime(firstPress), secondPress));
        assertFalse(OfflineReplayLogic.isPlannedShuffleStart(withStartTime(secondPress), firstPress));
    }

    @Test public void aStartWithoutTheTokenOfADispatchIsNeverThePlannedStart() throws Exception {
        byte[] planned = plannedStart(7);
        byte[] untokened = OfflineWatchCommand.build(VIDEO, "PPSVrvx:PLsaved_header", 0, 2, true);

        assertFalse(OfflineReplayLogic.isPlannedShuffleStart(untokened, planned));
        assertFalse(OfflineReplayLogic.isPlannedShuffleStart(withStartTime(untokened), planned));
        // A plan that was never dispatched has no token to match.
        assertFalse(OfflineReplayLogic.isPlannedShuffleStart(untokened, untokened));
    }

    @Test public void theDispatchTokenIsAnUnknownFieldTheWatchOfTheCommandIgnores() {
        byte[] start = OfflineWatchCommand.build(VIDEO, "PPSVrvx:PLsaved_header", 0, 2, true);
        byte[] marked = OfflineReplayLogic.withDispatchToken(start, 123_456_789_012L);

        assertEquals(0, OfflineReplayLogic.dispatchTokenOf(start));
        assertEquals(123_456_789_012L, OfflineReplayLogic.dispatchTokenOf(marked));
        assertEquals(OfflineReplayLogic.offlineWatchOf(start), OfflineReplayLogic.offlineWatchOf(marked));
        assertTrue(OfflineReplayLogic.isShuffleStart(marked, OfflineReplayLogic.offlineWatchOf(marked)));
        assertEquals(0, OfflineReplayLogic.dispatchTokenOf(new byte[]{(byte) 0xff}));
    }

    @Test(expected = IllegalArgumentException.class)
    public void aZeroTokenIsRejectedBecauseItMeansNone() {
        OfflineReplayLogic.withDispatchToken(OfflineWatchCommand.build(VIDEO, PLAYLIST, 0), 0);
    }

    private record Registered(int generation, Object dispatched, byte[] commandBytes)
            implements OfflineReplayLogic.PlannedStart {}

    @Test public void aPlanWhoseDispatchedCommandIsGoneIsStillFoundByTheRebuiltRequest() throws Exception {
        byte[] planned = plannedStart(3);
        Registered gone = new Registered(3, null, planned);

        Registered found = OfflineReplayLogic.plannedStartOf(withStartTime(planned), 3, List.of(gone));

        assertSame(gone, found);
        // The shuffled reply is built from the bytes of the plan, so the plan alone has to be a shuffled start.
        var target = OfflineReplayLogic.offlineWatchOf(found.commandBytes());
        assertNotNull(target);
        assertTrue(OfflineReplayLogic.isShuffleStart(found.commandBytes(), target));
    }

    @Test public void aRequestOfAnEarlierPressNeverTakesThePlanOfALaterOne() throws Exception {
        Registered first = new Registered(1, null, plannedStart(1));
        Registered second = new Registered(2, null, plannedStart(2));
        List<Registered> both = List.of(first, second);

        // The same song, playlist and place, so only the token tells the presses apart.
        assertNull(OfflineReplayLogic.plannedStartOf(withStartTime(first.commandBytes()), 2, both));
        assertSame(second, OfflineReplayLogic.plannedStartOf(withStartTime(second.commandBytes()), 2, both));
        // The plan of an earlier selection is not the answer even for its own request.
        assertNull(OfflineReplayLogic.plannedStartOf(withStartTime(first.commandBytes()), 2, List.of(first)));
    }

    @Test public void theTokenIsWrittenAtEveryLevelOfTheOfflineWatchEndpoint() throws Exception {
        byte[] marked = plannedStart(7);
        byte[] endpoint = ProtoFields.one(marked, 57_703_777);

        assertEquals(7, ProtoFields.varint(marked, 536_870_000));
        assertEquals(7, ProtoFields.varint(endpoint, 536_870_000));
        assertEquals(7, ProtoFields.varint(ProtoFields.one(endpoint, 10), 536_870_000));
        assertEquals(2, ProtoFields.varint(ProtoFields.one(endpoint, 10), 6));
        assertTrue(OfflineReplayLogic.isShuffleStart(marked, OfflineReplayLogic.offlineWatchOf(marked)));
    }

    /** What the app passes on when it builds the request from the endpoint alone: the top level is a new message. */
    private static byte[] rebuiltFromTheEndpoint(byte[] command) throws Exception {
        byte[] endpoint = ProtoFields.one(command, 57_703_777);
        return new ProtoWriter().message(57_703_777, endpoint).toByteArray();
    }

    /** What the app passes on when it builds the request from the values it reads: nothing unknown is kept. */
    private static byte[] rebuiltFromTheValues(byte[] command) {
        var target = OfflineReplayLogic.offlineWatchOf(command);
        return OfflineWatchCommand.build(target.videoId(), target.playlistId(), target.index(), 2, true);
    }

    @Test public void aRequestThatKeptTheTokenOnlyInTheEndpointStillTakesItsOwnPlan() throws Exception {
        Registered first = new Registered(1, null, plannedStart(1));
        Registered second = new Registered(2, null, plannedStart(2));
        byte[] ofTheSecondPress = rebuiltFromTheEndpoint(second.commandBytes());

        assertEquals(2, OfflineReplayLogic.dispatchTokenOf(ofTheSecondPress));
        assertSame(second, OfflineReplayLogic.plannedStartOf(ofTheSecondPress, 2, List.of(first, second)));
        assertNull(OfflineReplayLogic.plannedStartOf(rebuiltFromTheEndpoint(first.commandBytes()), 2,
                List.of(first, second)));
    }

    /**
     * The bytes the 9.45.51 watch controller hands its request: `Liem.s` puts the dispatched command object into the
     * request builder (`Labpk.a`), and `Labpk.a()` keeps it and only serializes it into the playback descriptor
     * (`Labpn.b`), which `Labpl.h` parses back from those very bytes. Either way the request's command is the
     * dispatched bytes, token included.
     */
    private static byte[] handedToTheRequest(byte[] dispatched) {
        return dispatched.clone();
    }

    @Test public void theRequestTheAppBuildsFromADispatchTakesThatDispatchsPlanOnly() {
        // Two presses that both started on the same random song: only the token differs.
        Registered first = new Registered(1, null, plannedStart(1));
        Registered second = new Registered(2, null, plannedStart(2));
        List<Registered> both = List.of(first, second);

        assertSame(second, OfflineReplayLogic.plannedStartOf(handedToTheRequest(second.commandBytes()), 2, both));
        assertNull(OfflineReplayLogic.plannedStartOf(handedToTheRequest(first.commandBytes()), 2, both));
        // The older press's own request is superseded: it fails closed rather than taking anything.
        assertTrue(OfflineReplayLogic.isSupersededShuffleStart(handedToTheRequest(first.commandBytes()), 2));
        assertFalse(OfflineReplayLogic.isSupersededShuffleStart(handedToTheRequest(second.commandBytes()), 2));
    }

    @Test public void aRequestThatLostEveryTokenTakesNoPlanAndIsNotCancelled() {
        byte[] planned = plannedStart(4);
        byte[] request = rebuiltFromTheValues(planned);

        assertEquals(0, OfflineReplayLogic.dispatchTokenOf(request));
        assertNull(OfflineReplayLogic.plannedStartOf(request, 4, List.of(new Registered(4, null, planned))));
        assertTrue(OfflineReplayLogic.isShufflePressStart(request));
        // Nothing proves which press it is, so nothing proves it superseded: the ordinary path answers it.
        assertFalse(OfflineReplayLogic.isSupersededShuffleStart(request, 4));
    }

    @Test public void twoPressesOfTheSameStartAreNeverToldApartByTheirContent() {
        Registered first = new Registered(1, null, plannedStart(1));
        Registered second = new Registered(2, null, plannedStart(2));
        byte[] olderRebuilt = rebuiltFromTheValues(first.commandBytes());

        // Without its token the older press's request looks exactly like the newer one: it takes nothing.
        assertArrayEquals(rebuiltFromTheValues(second.commandBytes()), olderRebuilt);
        assertNull(OfflineReplayLogic.plannedStartOf(olderRebuilt, 2, List.of(first, second)));
    }

    @Test public void aRequestOfAnotherDispatchNeverTakesAPlanWithoutItsToken() throws Exception {
        byte[] planned = plannedStart(9);
        byte[] ofAnotherPress = withStartTime(plannedStart(8));

        assertNull(OfflineReplayLogic.plannedStartOf(ofAnotherPress, 9, List.of(new Registered(9, null, planned))));
        assertTrue(OfflineReplayLogic.isShufflePressStart(ofAnotherPress));
        assertTrue(OfflineReplayLogic.isSupersededShuffleStart(ofAnotherPress, 9));
    }

    @Test public void anOrderedStartOrAnOnlineWatchIsNoShufflePress() {
        assertFalse(OfflineReplayLogic.isShufflePressStart(
                OfflineWatchCommand.build(VIDEO, "PPSVrvx:PLsaved_header", 0, 2)));
        assertFalse(OfflineReplayLogic.isShufflePressStart(OfflineWatchCommand.build(VIDEO, PLAYLIST, 0, 2, true)));
        assertFalse(OfflineReplayLogic.isShufflePressStart(new byte[0]));
        assertFalse(OfflineReplayLogic.isSupersededShuffleStart(
                OfflineWatchCommand.build(VIDEO, "PPSVrvx:PLsaved_header", 0, 2), 3));
    }

    @Test public void theShapeOfACommandNamesEveryLevelOfItsEndpoint() {
        String shape = OfflineReplayLogic.shapeOf(plannedStart(5));

        assertTrue(shape, shape.startsWith("[57703777:s"));
        assertTrue(shape, shape.contains(",536870000:v] endpoint=[1:s11,2:s22,3:v,10:s"));
        assertTrue(shape, shape.endsWith(" config=[6:v,3:v,536870000:v]"));
    }

    @Test public void theDispatchedObjectIsFoundByIdentityNotByContent() {
        Object command = new Object();
        Registered held = new Registered(1, command, plannedStart(1));
        Registered other = new Registered(1, new Object(), plannedStart(1));

        assertSame(held, OfflineReplayLogic.dispatchedPlanOf(command, List.of(other, held)));
        assertNull(OfflineReplayLogic.dispatchedPlanOf(new Object(), List.of(other, held)));
        assertNull(OfflineReplayLogic.dispatchedPlanOf(null, List.of(other, held)));
    }

    @Test public void indexZeroIsWrittenExplicitly() throws Exception {
        byte[] endpoint = endpoint(OfflineWatchCommand.build(VIDEO, PLAYLIST, 0));

        assertEquals(0, ProtoFields.varint(endpoint, 3));
    }

    @Test public void aLargeIndexSurvives() throws Exception {
        byte[] endpoint = endpoint(OfflineWatchCommand.build(VIDEO, PLAYLIST, 70_000));

        assertEquals(70_000, ProtoFields.varint(endpoint, 3));
    }

    @Test public void theExactBytesAreStable() {
        byte[] command = OfflineWatchCommand.build("v", "p", 1);

        // 8a d6 8f dc 01 | len 12 | 0a 01 'v' | 12 01 'p' | 18 01 | 52 02 30 01
        assertArrayEquals(new byte[]{(byte) 0x8a, (byte) 0xd6, (byte) 0x8f, (byte) 0xdc, 0x01, 0x0c,
                0x0a, 0x01, 'v', 0x12, 0x01, 'p', 0x18, 0x01, 0x52, 0x02, 0x30, 0x01}, command);
    }

    @Test(expected = IllegalArgumentException.class)
    public void anEmptyVideoIdIsRejected() {
        OfflineWatchCommand.build("", PLAYLIST, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aMissingPlaylistIsRejected() {
        OfflineWatchCommand.build(VIDEO, null, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aNegativeIndexIsRejected() {
        OfflineWatchCommand.build(VIDEO, PLAYLIST, -1);
    }

    @Test public void theMusicVideoTypeOfAVideoCommandIsTheVideosOnly() throws Exception {
        byte[] endpoint = endpoint(OfflineWatchCommand.build(VIDEO, PLAYLIST, 3, 2));
        byte[] config = ProtoFields.one(endpoint, 10);

        assertEquals(List.of(1, 2, 3, 10), ProtoFields.numbers(endpoint));
        assertEquals(2, ProtoFields.varint(config, 6));
        assertEquals(VIDEO, ProtoFields.string(endpoint, 1));
    }
}
