/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27.
 * Written by lavinhoque33, 2026-11-26.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import static app.morphe.extension.music.patches.downloads.ReconnectSurfacesLogic.TitlePresence.AMBIGUOUS_TRACK;
import static app.morphe.extension.music.patches.downloads.ReconnectSurfacesLogic.TitlePresence.MALFORMED;
import static app.morphe.extension.music.patches.downloads.ReconnectSurfacesLogic.TitlePresence.NO_COMMAND;
import static app.morphe.extension.music.patches.downloads.ReconnectSurfacesLogic.TitlePresence.NO_QUEUE;
import static app.morphe.extension.music.patches.downloads.ReconnectSurfacesLogic.TitlePresence.NO_TITLE;
import static app.morphe.extension.music.patches.downloads.ReconnectSurfacesLogic.TitlePresence.NO_TRACK;
import static app.morphe.extension.music.patches.downloads.ReconnectSurfacesLogic.TitlePresence.PRESENT;

import org.junit.Test;

public final class ReconnectSurfacesLogicTest {
    private static final String VIDEO = "abcdefghijk";
    private static final int SHOW_PANEL = 138_681_778;
    private static final int PANEL_ACTION = 369_951_587;
    private static final byte[] ENDPOINT = directPanel("panel-related");
    private static final int WRAPPER = 206_848_145;

    static byte[] directPanel(String key) {
        return new ProtoWriter().message(SHOW_PANEL, new ProtoWriter().string(1, key).toByteArray()).toByteArray();
    }

    static byte[] button(byte[] command) {
        byte[] metadata = new ProtoWriter().message(4, command).toByteArray();
        return new ProtoWriter().message(3, metadata).toByteArray();
    }

    @Test public void directPanelUsesOnlyTheEndpointKeyNotTrackingOrOtherFields() {
        byte[] show = new ProtoWriter().string(1, "panel-lyrics").string(3, "panel-description").toByteArray();
        byte[] command = new ProtoWriter().string(2, "panel-related").message(SHOW_PANEL, show).toByteArray();

        assertEquals(new ReconnectSurfacesLogic.PanelTarget("direct", "panel-lyrics"),
                ReconnectSurfacesLogic.buttonPanel(button(command)));
    }

    @Test public void nestedActionUsesItsCommandAndNativeIdentifierKey() {
        byte[] identifier = new ProtoWriter().int32(1, 1).string(3, "panel-lyrics").toByteArray();
        byte[] endpoint = new ProtoWriter().message(10, identifier).toByteArray();
        byte[] action = new ProtoWriter().string(1, "ignored-inline-key")
                .message(5, new ProtoWriter().message(SHOW_PANEL, endpoint).toByteArray()).toByteArray();
        byte[] command = new ProtoWriter().message(PANEL_ACTION, action).toByteArray();

        assertEquals(new ReconnectSurfacesLogic.PanelTarget("nested", "panel-lyrics"),
                ReconnectSurfacesLogic.buttonPanel(button(command)));
    }

    @Test public void nestedCommandWithoutTheShowPanelExtensionDoesNotBorrowAnInlineKey() {
        byte[] action = new ProtoWriter().string(1, "panel-lyrics")
                .message(5, new ProtoWriter().string(2, "tracking").toByteArray()).toByteArray();
        byte[] command = new ProtoWriter().message(PANEL_ACTION, action).toByteArray();

        assertEquals(new ReconnectSurfacesLogic.PanelTarget("nested", null),
                ReconnectSurfacesLogic.commandPanel(command));
    }

    @Test public void aPanelActionWithoutACommandCanUseItsOwnIdentifier() {
        byte[] identifier = new ProtoWriter().int32(1, 1).string(3, "panel-lyrics").toByteArray();
        byte[] action = new ProtoWriter().message(2, identifier).toByteArray();
        byte[] command = new ProtoWriter().message(PANEL_ACTION, action).toByteArray();

        assertEquals(new ReconnectSurfacesLogic.PanelTarget("action-key", "panel-lyrics"),
                ReconnectSurfacesLogic.commandPanel(command));
    }

    @Test public void theLastIdentifierOneofArmWins() {
        byte[] identifier = new ProtoWriter().int32(1, 1).toByteArray();
        byte[] show = new ProtoWriter().string(1, "old").message(10, identifier).toByteArray();
        byte[] command = new ProtoWriter().message(SHOW_PANEL, show).toByteArray();

        assertEquals(new ReconnectSurfacesLogic.PanelTarget("direct", null),
                ReconnectSurfacesLogic.commandPanel(command));
    }

    @Test public void bytesInElementsInputAreNotARecognizedButtonMetadataCommand() {
        byte[] renderer = new ProtoWriter().message(5, directPanel("panel-lyrics")).toByteArray();

        assertEquals(new ReconnectSurfacesLogic.PanelTarget("elements-input", null),
                ReconnectSurfacesLogic.buttonPanel(renderer));
    }

    // region related command

    private static byte[] title(String text, byte[]... endpoints) {
        ProtoWriter runs = new ProtoWriter();
        ProtoWriter all = new ProtoWriter();
        runs.string(1, text);
        all.message(OfflineWatchNext.TEXT_RUNS_FIELD, runs.toByteArray());
        for (byte[] endpoint : endpoints) {
            all.message(OfflineWatchNext.TEXT_RUNS_FIELD, new ProtoWriter().string(1, "x").message(5, endpoint)
                    .toByteArray());
        }
        return all.toByteArray();
    }

    private static byte[] track(String videoId, byte[] title) {
        return new ProtoWriter().message(OfflineWatchNext.TRACK_TITLE_FIELD, title)
                .string(OfflineWatchNext.TRACK_VIDEO_ID_FIELD, videoId).toByteArray();
    }

    private static byte[] directItem(String videoId, byte[] title) {
        return new ProtoWriter().message(OfflineWatchNext.ITEM_TRACK_FIELD, track(videoId, title)).toByteArray();
    }

    private static byte[] wrapperItem(byte[] primary, byte[] counterpart) {
        ProtoWriter wrapper = new ProtoWriter().message(1,
                new ProtoWriter().message(OfflineWatchNext.ITEM_TRACK_FIELD, primary).toByteArray());
        if (counterpart != null) {
            byte[] renderer = new ProtoWriter().message(OfflineWatchNext.ITEM_TRACK_FIELD, counterpart).toByteArray();
            wrapper.message(2, new ProtoWriter().message(2, renderer).toByteArray());
        }
        return new ProtoWriter().message(WRAPPER, wrapper.toByteArray()).toByteArray();
    }

    private static byte[] queue(Integer currentIndex, byte[]... items) {
        ProtoWriter panel = new ProtoWriter();
        for (byte[] item : items) panel.message(OfflineWatchNext.PANEL_ITEMS_FIELD, item);
        if (currentIndex != null) panel.int32(OfflineWatchNext.PANEL_CURRENT_INDEX_FIELD, currentIndex);
        return panel.toByteArray();
    }

    private static byte[] response(byte[]... items) {
        return tabbedResponse(queue(null, items));
    }

    private static byte[] tabbedResponse(byte[] queue) {
        return watchResponse(new ProtoWriter().message(7, tabbedQueueHolder(queue)));
    }

    private static byte[] tabbedQueueHolder(byte[] queue) {
        byte[] panelHolder = new ProtoWriter().message(OfflineWatchNext.PANEL_RENDERER_FIELD, queue)
                .toByteArray();
        byte[] content = new ProtoWriter().message(OfflineWatchNext.PANEL_HOLDER_RENDERER_FIELD, panelHolder)
                .toByteArray();
        byte[] tabContent = new ProtoWriter().message(OfflineWatchNext.TAB_PANEL_HOLDER_FIELD, content).toByteArray();
        byte[] tab = new ProtoWriter().message(OfflineWatchNext.TAB_CONTENT_FIELD, tabContent).toByteArray();
        byte[] entry = new ProtoWriter().message(OfflineWatchNext.TAB_FIELD, tab).toByteArray();
        byte[] tabs = new ProtoWriter().message(OfflineWatchNext.TABS_LIST_FIELD, entry).toByteArray();
        return new ProtoWriter().message(OfflineWatchNext.TABS_RENDERER_FIELD, tabs).toByteArray();
    }

    private static byte[] watchResponse(ProtoWriter music) {
        byte[] contents = new ProtoWriter().message(OfflineWatchNext.CONTENTS_QUEUE_FIELD, music.toByteArray())
                .toByteArray();
        return new ProtoWriter().message(OfflineWatchNext.RESPONSE_CONTENTS_FIELD, contents).toByteArray();
    }

    @Test public void theTitleCommandOfADirectTrackIsItsFirstRunEndpoint() {
        byte[] bytes = response(directItem(VIDEO, title("Song", ENDPOINT)));

        var related = ReconnectSurfacesLogic.related(bytes, VIDEO);
        assertEquals("tabbed", related.path());
        assertEquals(PRESENT, related.presence());
        assertArrayEquals(ENDPOINT, related.command());
    }

    @Test public void aWrappedPrimaryOrFirstCounterpartUsesItsOwnTitleCommand() {
        byte[] primary = track("zzzzzzzzzzz", title("Video", directPanel("other")));
        byte[] counterpart = track(VIDEO, title("Song", ENDPOINT));
        byte[] bytes = response(wrapperItem(primary, counterpart));

        assertArrayEquals(ENDPOINT, ReconnectSurfacesLogic.related(bytes, VIDEO).command());
        assertArrayEquals(ENDPOINT, ReconnectSurfacesLogic.related(response(wrapperItem(counterpart, null)), VIDEO)
                .command());
    }

    @Test public void legacyQueueUsesTheSameCurrentTrackAndTitleRunShape() {
        byte[] queue = queue(1, directItem("zzzzzzzzzzz", title("Neighbour", directPanel("other"))),
                directItem(VIDEO, title("Song", ENDPOINT)));
        byte[] legacy = new ProtoWriter().message(50_631_000, queue).toByteArray();
        byte[] response = watchResponse(new ProtoWriter().message(2, legacy));

        var related = ReconnectSurfacesLogic.related(response, VIDEO);
        assertEquals("legacy", related.path());
        assertArrayEquals(ENDPOINT, related.command());
    }

    @Test public void aPresentTabbedQueueNeverBorrowsTheLegacyQueuesCommand() {
        byte[] tabbed = tabbedQueueHolder(queue(0, directItem(VIDEO, title("Song"))));
        byte[] legacy = new ProtoWriter().message(50_631_000, queue(0, directItem(VIDEO, title("Song", ENDPOINT))))
                .toByteArray();
        byte[] response = watchResponse(new ProtoWriter().message(2, legacy).message(7, tabbed));

        var related = ReconnectSurfacesLogic.related(response, VIDEO);
        assertEquals("tabbed", related.path());
        assertEquals(NO_COMMAND, related.presence());
    }

    @Test public void currentIndexNeverBorrowsACommandFromADuplicateOccurrence() {
        byte[] first = directItem(VIDEO, title("First", ENDPOINT));
        byte[] second = directItem(VIDEO, title("Second"));

        assertEquals(NO_COMMAND, ReconnectSurfacesLogic.related(tabbedResponse(queue(1, first, second)), VIDEO)
                .presence());
        assertEquals(AMBIGUOUS_TRACK, ReconnectSurfacesLogic.related(response(first, second), VIDEO).presence());
        assertArrayEquals(ENDPOINT, ReconnectSurfacesLogic.related(tabbedResponse(queue(0, first, second)), VIDEO)
                .command());
    }

    @Test public void aNeighbourNeverLendsItsCommandToTheSong() {
        byte[] neighbour = directItem("zzzzzzzzzzz", title("Neighbour", directPanel("other")));
        byte[] bytes = response(neighbour, directItem(VIDEO, title("Song")));

        assertEquals(NO_COMMAND, ReconnectSurfacesLogic.related(bytes, VIDEO).presence());
        assertEquals(NO_TRACK, ReconnectSurfacesLogic.related(response(neighbour), VIDEO).presence());
        assertEquals(NO_TRACK, ReconnectSurfacesLogic.related(
                tabbedResponse(queue(0, neighbour, directItem(VIDEO, title("Song", ENDPOINT)))), VIDEO).presence());
    }

    @Test public void theFirstRunThatHasAnEndpointGivesTheCommand() {
        byte[] bytes = response(directItem(VIDEO, title("Song", ENDPOINT, directPanel("unused"))));

        assertArrayEquals(ENDPOINT, ReconnectSurfacesLogic.related(bytes, VIDEO).command());
    }

    @Test public void distinguishesMissingTitleAndMissingEndpointFromAnUnreadableOrMissingQueue() {
        byte[] noTitle = new ProtoWriter().message(51_779_701, new ProtoWriter().string(10, VIDEO).toByteArray())
                .toByteArray();
        assertEquals(NO_TITLE, ReconnectSurfacesLogic.related(response(noTitle), VIDEO).presence());
        assertEquals(NO_COMMAND, ReconnectSurfacesLogic.related(response(directItem(VIDEO, title("Song"))), VIDEO)
                .presence());
        assertEquals(NO_QUEUE, ReconnectSurfacesLogic.related(new byte[0], VIDEO).presence());
        assertEquals(MALFORMED, ReconnectSurfacesLogic.related(new byte[]{(byte) 0xff, 0x01}, VIDEO).presence());
    }

    @Test public void aCommandIsDescribedByItsTopLevelFieldNumbers() {
        byte[] command = new ProtoWriter().message(1, new byte[]{1}).message(57_703_777, new byte[]{2}).toByteArray();

        assertEquals("1+57703777", ReconnectSurfacesLogic.describeCommand(command));
    }

    // endregion
}
