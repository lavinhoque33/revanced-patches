/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27.
 * Written by lavinhoque33, 2026-11-26.
 */
package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.music.patches.downloads.OfflineLibraryLogic.SourceRow;
import static org.junit.Assert.*;

import org.junit.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

public class LibraryLandingLogicTest {
    private static final int SECTION_CONTENT = 1;
    private static final int ITEM_CONTENT = 50_195_462;
    private static final int MESSAGE_SLOT = 1;
    private static final int METADATA_SLOT = 4;
    private static final int MESSAGE = 58_508_690;
    private static final int SHELF = 91_303_872;
    private static final int OTHER_RENDERER = 51_431_404;
    private static final int BROWSE_ENDPOINT = 48_687_626;
    private static final String LANDING = "FEmusic_library_landing";

    private static final DownloadsPageLogic.Labels LABELS = new DownloadsPageLogic.Labels("Albums and playlists",
            "No Playlist", "Playlist", count -> count + " songs");
    private static final DownloadsPageLogic.Taps TAPS = new DownloadsPageLogic.Taps() {
        @Override public byte[] source(SourceRow row) {
            return ("source:" + row.sourceId()).getBytes(StandardCharsets.UTF_8);
        }
    };
    private static final SourceRow PLAYLIST = new SourceRow("PLa", "Mix", 3, 2, "x", null);
    private static final SourceRow ALBUM = new SourceRow("OLAK5uy_b", "Album", 10, 4, null, "https://img/b");
    private static final SourceRow NO_PLAYLIST = new SourceRow("songs", "No Playlist", 2, 2, "s", null);

    private static byte[] messageContent(String text) {
        byte[] textRenderer = new ProtoWriter().message(MESSAGE, new ProtoWriter().string(1, text).toByteArray())
                .toByteArray();
        return new ProtoWriter().message(MESSAGE_SLOT, textRenderer).message(METADATA_SLOT, new byte[0])
                .toByteArray();
    }

    private static byte[] companionContent() {
        byte[] opaque = new ProtoWriter().int32(1, 1).fixed32(2, 5).string(3, "x").int64(4, 3).toByteArray();
        return new ProtoWriter().message(METADATA_SLOT, opaque).toByteArray();
    }

    private static byte[] wrap(byte[] content) {
        return new ProtoWriter().message(ITEM_CONTENT, content).toByteArray();
    }

    private static byte[] endpoint(byte[] browse) {
        return new ProtoWriter().message(BROWSE_ENDPOINT, browse).toByteArray();
    }

    private static byte[] concat(byte[] first, byte[] second) {
        return new ProtoWriter().raw(first, 0, first.length).raw(second, 0, second.length).toByteArray();
    }

    private static String titleOf(byte[] item) throws Exception {
        byte[] text = ProtoFields.one(ProtoFields.one(item, SHELF), 1);
        return ProtoFields.string(ProtoFields.one(text, 1), 1);
    }

    private static byte[] page(byte[]... items) {
        ProtoWriter page = new ProtoWriter();
        for (byte[] item : items) page.message(SECTION_CONTENT, item);
        return page.toByteArray();
    }

    private static List<byte[]> localRows() {
        return DownloadsPageLogic.sections(DownloadsPageLogic.Layout.ALL, false, List.of(PLAYLIST, ALBUM),
                NO_PLAYLIST, LABELS, TAPS, (id, url) -> url, id -> false).items();
    }

    @Test public void validatedBrowseFailureRecoversOnlyForDeadUpstreamOrTransport() {
        var unrelated = new IOException("bad response");
        var rejected = new ExecutionException(new ConnectException("route refused"));

        assertEquals(LibraryLandingLogic.BrowseFailure.NONE,
                LibraryLandingLogic.browseFailure(true, false, unrelated, false));
        assertEquals(LibraryLandingLogic.BrowseFailure.UPSTREAM_DEAD,
                LibraryLandingLogic.browseFailure(true, true, unrelated, false));
        assertEquals(LibraryLandingLogic.BrowseFailure.TRANSPORT,
                LibraryLandingLogic.browseFailure(true, false, rejected, false));
        assertEquals(LibraryLandingLogic.BrowseFailure.OS_OFFLINE,
                LibraryLandingLogic.browseFailure(false, false, unrelated, false));
    }

    @Test public void successfulOrCancelledBrowseNeverElectsTheNoCacheFallback() {
        assertEquals(LibraryLandingLogic.BrowseFailure.NONE,
                LibraryLandingLogic.browseFailure(true, true, null, false));
        assertEquals(LibraryLandingLogic.BrowseFailure.NONE, LibraryLandingLogic.browseFailure(false, true,
                new ExecutionException(new CancellationException()), false));
        assertEquals(LibraryLandingLogic.BrowseFailure.NONE,
                LibraryLandingLogic.browseFailure(true, true, new ConnectException("interrupted"), true));
    }

    @Test public void diagnosticsDescribeWireTagsWithoutPayloadValues() {
        byte[] command = new ProtoWriter().string(2, "private tracking").message(BROWSE_ENDPOINT,
                new ProtoWriter().string(2, LANDING).toByteArray()).toByteArray();
        assertEquals("[2:2,48687626:2]", LibraryLandingLogic.wireShape(command));
        assertEquals("absent", LibraryLandingLogic.wireShape(null));
    }

    @Test public void reloadDiagnosticSeparatesNestedHeaderAndContinuationShapesWithoutValues() {
        byte[] elements = new ProtoWriter().string(5, "private elements").toByteArray();
        byte[] header = new ProtoWriter().message(153_515_154, elements).toByteArray();
        byte[] continuation = new ProtoWriter().string(1, "opaque token").bool(4, true).toByteArray();
        byte[] group = new ProtoWriter().message(60_487_319, continuation).toByteArray();
        byte[] reload = new ProtoWriter().message(1, group).message(5, header).toByteArray();
        byte[] command = new ProtoWriter().message(120_837_120, reload).toByteArray();

        assertEquals("[1:2,5:2] group=[60487319:2] continuation=[1:2,4:0] embedded-header=[153515154:2]"
                + " embedded-elements=[5:2] embedded-equals-fresh=true",
                LibraryLandingLogic.reloadShape(command, elements));
        assertTrue(LibraryLandingLogic.reloadShape(command, null).endsWith("embedded-equals-fresh=false"));
    }

    @Test public void onlyTheUnparameterizedNativeLibraryBrowseEndpointQualifies() {
        byte[] browse = new ProtoWriter().string(2, LANDING).toByteArray();
        byte[] command = endpoint(browse);
        assertTrue(LibraryLandingLogic.isPlainCommand(command));
        assertTrue(LibraryLandingLogic.isPlainCommand(concat(new ProtoWriter().string(2, "tracking").toByteArray(),
                command)));
        assertFalse(LibraryLandingLogic.isPlainCommand(concat(command,
                new ProtoWriter().message(0x0733d400, new byte[0]).toByteArray())));
        assertFalse(LibraryLandingLogic.isPlainCommand(endpoint(new ProtoWriter().string(1, LANDING).toByteArray())));
        assertFalse(LibraryLandingLogic.isPlainCommand(endpoint(new ProtoWriter()
                .string(2, LANDING).string(3, "filter parameters").toByteArray())));
        assertFalse(LibraryLandingLogic.isPlainCommand(endpoint(new ProtoWriter()
                .string(2, "FEmusic_offline").toByteArray())));
        assertFalse(LibraryLandingLogic.isPlainCommand(endpoint(concat(browse, browse))));
        assertFalse(LibraryLandingLogic.isPlainCommand(concat(command, command)));
        assertFalse(LibraryLandingLogic.isPlainCommand(Arrays.copyOf(command, command.length - 1)));
        assertFalse(LibraryLandingLogic.isPlainCommand(null));
    }

    @Test public void nativeTabTrackingDoesNotMakeTheUnfilteredLandingAChip() {
        int tabContext = 94_476_261;
        byte[] plain = endpoint(new ProtoWriter().string(2, LANDING).toByteArray());
        byte[] context = new ProtoWriter().message(tabContext,
                new ProtoWriter().string(1, "previous page tracking").toByteArray()).toByteArray();
        assertTrue(LibraryLandingLogic.isPlainCommand(concat(plain, context)));
        assertFalse(LibraryLandingLogic.isPlainCommand(concat(plain, concat(context, context))));
        assertFalse(LibraryLandingLogic.isPlainCommand(concat(plain,
                new ProtoWriter().message(tabContext, new byte[0]).toByteArray())));
        assertFalse(LibraryLandingLogic.isPlainCommand(concat(plain, new ProtoWriter().message(tabContext,
                new ProtoWriter().string(1, "tracking").string(2, "not stock tab metadata").toByteArray())
                .toByteArray())));
        assertFalse(LibraryLandingLogic.isPlainCommand(concat(context, endpoint(new ProtoWriter()
                .string(2, LANDING).string(3, "filter").toByteArray()))));
        assertFalse(LibraryLandingLogic.isPlainCommand(concat(context, endpoint(new ProtoWriter()
                .string(2, "FEmusic_offline").toByteArray()))));
    }

    @Test public void observedElementsClearEnvelopeIsPlainOnlyWithoutBrowseParams() {
        byte[] envelopePrefix = new ProtoWriter().string(4, "click tracking").toByteArray();
        byte[] tabTracking = new ProtoWriter().string(1, "previous page tracking").toByteArray();
        byte[] elementsTracking = new ProtoWriter().string(1, "Elements origin tracking").toByteArray();
        byte[] decorations = new ProtoWriter().message(94_476_261, tabTracking)
                .message(144_224_893, elementsTracking).toByteArray();
        byte[] browse = new ProtoWriter().string(2, LANDING).toByteArray();
        byte[] clear = concat(concat(envelopePrefix, endpoint(browse)), decorations);
        assertEquals(LibraryLandingLogic.Target.ELEMENTS_PLAIN, LibraryLandingLogic.commandTarget(clear));

        byte[] playlists = concat(browse, new ProtoWriter().string(3, "filter parameters").toByteArray());
        assertFalse(LibraryLandingLogic.isPlainCommand(
                concat(concat(envelopePrefix, endpoint(playlists)), decorations)));
        assertFalse(LibraryLandingLogic.isPlainCommand(concat(clear,
                new ProtoWriter().message(144_224_893, elementsTracking).toByteArray())));
        assertFalse(LibraryLandingLogic.isPlainCommand(concat(endpoint(browse), new ProtoWriter()
                .message(144_224_893, new ProtoWriter().message(2, new byte[0]).toByteArray()).toByteArray())));
    }

    @Test public void exactRawHolderAndRestoredRendererMatchRegardlessOfText() {
        byte[] companion = companionContent();
        byte[] unmodifiedNativeState = new ProtoWriter().string(6, "header").int32(3, 7).toByteArray();
        for (String text : List.of("Content you save to your library will show here", "Contenido guardado", "")) {
            byte[] message = messageContent(text);
            byte[] contents = page(wrap(message), wrap(companion));
            assertTrue(LibraryLandingLogic.hasPlaceholder(concat(contents, unmodifiedNativeState)));
            assertTrue(LibraryLandingLogic.isEmptyRenderer(message));
            assertTrue(LibraryLandingLogic.isEmptyPair(message, companion));
        }
    }

    @Test public void malformedAndPopulatedHoldersStayStock() {
        byte[] message = wrap(messageContent("x"));
        byte[] companion = wrap(companionContent());
        byte[] shelf = localRows().get(0);
        for (byte[] stock : List.of(page(message), page(companion, message), page(message, message),
                page(message, shelf), page(shelf, message, companion), page(message, companion, shelf))) {
            assertFalse(LibraryLandingLogic.hasPlaceholder(stock));
        }
        byte[] valid = page(message, companion);
        assertFalse(LibraryLandingLogic.hasPlaceholder(concat(valid, new ProtoWriter().int32(1, 2).toByteArray())));
        assertFalse(LibraryLandingLogic.hasPlaceholder(Arrays.copyOf(valid, valid.length - 1)));
        assertFalse(LibraryLandingLogic.hasPlaceholder(new byte[]{0x0a, 0x7f}));
    }

    @Test public void renderedSlotCannotHaveAnotherRendererOrMalformedCompanion() {
        byte[] message = messageContent("x");
        byte[] mixed = new ProtoWriter().message(MESSAGE_SLOT, new ProtoWriter().message(MESSAGE, new byte[0])
                .message(OTHER_RENDERER, new byte[0]).toByteArray()).message(METADATA_SLOT, new byte[0])
                .toByteArray();
        assertFalse(LibraryLandingLogic.isEmptyRenderer(mixed));
        assertFalse(LibraryLandingLogic.isEmptyRenderer(concat(message,
                new ProtoWriter().message(MESSAGE_SLOT, new byte[0]).toByteArray())));
        assertFalse(LibraryLandingLogic.isEmptyRenderer(new ProtoWriter().message(MESSAGE_SLOT,
                new ProtoWriter().message(MESSAGE, new byte[0]).toByteArray()).int32(METADATA_SLOT, 1).toByteArray()));
        assertFalse(LibraryLandingLogic.isEmptyPair(message, null));
        assertFalse(LibraryLandingLogic.isEmptyPair(companionContent(), message));
        byte[] duplicate = new ProtoWriter().int32(1, 1).int32(2, 2).int32(2, 3).int32(3, 3).int32(4, 4)
                .toByteArray();
        assertFalse(LibraryLandingLogic.isEmptyPair(message,
                new ProtoWriter().message(METADATA_SLOT, duplicate).toByteArray()));
    }

    @Test public void onlyTheExistingLocalShelfIsDecodedAsAReplacementItem() throws Exception {
        List<byte[]> rows = localRows();
        byte[] item = LibraryLandingLogic.shelfItem(rows);
        assertSame(rows.get(0), item);
        assertEquals("Albums and playlists", titleOf(item));
        assertTrue(new String(item, StandardCharsets.UTF_8).contains("No Playlist"));
        assertNull(LibraryLandingLogic.shelfItem(List.of()));
        assertNull(LibraryLandingLogic.shelfItem(List.of(rows.get(0), rows.get(0))));
        assertNull(LibraryLandingLogic.shelfItem(List.of(wrap(messageContent("x")))));
    }

    @Test public void onlyALibraryInsertionIsMarkedForDedupe() throws Exception {
        byte[] stock = localRows().get(0);
        byte[] marked = LibraryLandingLogic.markShelf(stock);
        assertFalse(LibraryLandingLogic.isLocalShelf(stock));
        assertTrue(LibraryLandingLogic.isLocalShelf(marked));
        assertEquals(titleOf(stock), titleOf(marked));
        assertFalse(LibraryLandingLogic.isLocalShelf(LibraryLandingLogic.markShelf(messageContent("empty"))));
        assertFalse(LibraryLandingLogic.isLocalShelf(concat(stock, marked)));
    }

    @Test public void rowsAreKeptPerRevisionOfTheCopiesAndBuiltOnce() {
        LibraryLandingLogic.RowsCache cache = new LibraryLandingLogic.RowsCache();

        assertNull(cache.rowsFor(4));
        assertTrue(cache.startBuild(4));
        assertFalse("a bind while the rows are being built starts no second build", cache.startBuild(4));

        byte[] rows = LibraryLandingLogic.shelfItem(localRows());
        assertTrue(cache.publish(4, 4, rows));

        assertSame(rows, cache.rowsFor(4));
        assertFalse("built rows start no build", cache.startBuild(4));
        assertNull("another revision has no rows", cache.rowsFor(5));
        assertTrue(cache.startBuild(5));
    }

    @Test public void rowsBuiltForAnOlderRevisionAreNeverKept() {
        LibraryLandingLogic.RowsCache cache = new LibraryLandingLogic.RowsCache();
        assertTrue(cache.startBuild(1));

        assertFalse("the copies changed while building", cache.publish(1, 2, LibraryLandingLogic.NO_ROWS));

        assertNull(cache.rowsFor(1));
        assertNull(cache.rowsFor(2));
        assertTrue("the next revision is built again", cache.startBuild(2));
    }

    @Test public void aFailedBuildCanBeStartedAgain() {
        LibraryLandingLogic.RowsCache cache = new LibraryLandingLogic.RowsCache();
        assertTrue(cache.startBuild(3));

        cache.failed(3);

        assertNull(cache.rowsFor(3));
        assertTrue(cache.startBuild(3));
    }

    @Test public void noRowsAtAllIsAResultToo() {
        LibraryLandingLogic.RowsCache cache = new LibraryLandingLogic.RowsCache();
        assertTrue(cache.startBuild(0));

        assertTrue(cache.publish(0, 0, LibraryLandingLogic.NO_ROWS));

        assertNotNull(cache.rowsFor(0));
        assertEquals(0, cache.rowsFor(0).length);
        assertFalse("an empty library is not built again at every bind", cache.startBuild(0));
    }

    @Test public void noCacheLandingUsesTheNativeLoadingChipsIfNoPlainResponseWasSeen() {
        var headers = new LibraryLandingLogic.NativeHeaders();
        Object host = new Object();
        Object container = new Object();
        Object stockChips = new Object();
        headers.expectLoading(host, container);

        assertNull(headers.loadingOwner(new Object()));
        assertSame(host, headers.loadingOwner(container));
        headers.rememberLoading(host, stockChips);

        assertSame(stockChips, headers.forFallback(host, true, true).payload());
        assertNull(headers.forFallback(host, false, true));
        assertNull(headers.forFallback(host, true, false));
        assertNull(headers.forFallback(new Object(), true, true));
    }

    @Test public void genuinePlainHeaderWinsEvenIfAnotherNativeLoadingHeaderArrivesLater() {
        var headers = new LibraryLandingLogic.NativeHeaders();
        Object host = new Object();
        Object plainChips = new Object();
        headers.rememberLoading(host, new Object());
        headers.rememberPlainResponse(host, plainChips);
        headers.rememberLoading(host, new Object());

        var fallback = headers.forFallback(host, true, true);
        assertSame(plainChips, fallback.payload());
        assertTrue(fallback.fromPlainResponse());
    }

    private static void acceptRetainedReload(LibraryLandingLogic.NativeHeaders headers, Object adapter,
                                             String header, List<byte[]> nativeRows, byte[] shelf) {
        var host = headers.plainAdapterOwner(adapter);
        if (host == null || !headers.matchesRetainedPlain(host, header, true)) return;
        int n = nativeRows.size();
        byte[] first = n == 0 || n > 2 ? null : nativeRows.get(0);
        byte[] tail = n < 2 ? null : nativeRows.get(n - 1);
        if (!LibraryLandingLogic.alreadyPublished(first, tail)) nativeRows.add(shelf);
    }

    @Test public void clearDownloadsAppendsTheShelfWithoutReplacingTheRetainedNativeHeaderOrRows() {
        var headers = new LibraryLandingLogic.NativeHeaders();
        Object host = new Object();
        Object adapter = new Object();
        String retainedChips = "unselected native chip row";
        headers.rememberPlainResponse(host, retainedChips);
        headers.rememberPlainAdapter(host, adapter);
        byte[] recent = messageContent("Recent activity");
        byte[] shelf = LibraryLandingLogic.markShelf(localRows().get(0));
        var rendered = new ArrayList<>(List.of(recent));

        acceptRetainedReload(headers, adapter, new String(retainedChips), rendered, shelf);

        assertEquals(List.of(recent, shelf), rendered);
        assertNull(headers.forFallback(host, false, false));
        assertFalse(headers.matchesRetainedPlain(host, retainedChips, false));
    }

    @Test public void selectingPlaylistsAfterClearDoesNotAppendThePlainLibraryShelf() {
        var headers = new LibraryLandingLogic.NativeHeaders();
        Object host = new Object();
        Object adapter = new Object();
        headers.rememberPlainResponse(host, "plain");
        headers.rememberPlainAdapter(host, adapter);
        byte[] playlist = messageContent("Native playlist row");
        byte[] shelf = LibraryLandingLogic.markShelf(localRows().get(0));
        var rendered = new ArrayList<>(List.of(playlist));

        acceptRetainedReload(headers, adapter, "selected Playlists", rendered, shelf);

        assertEquals(List.of(playlist), rendered);
        assertNull(headers.plainAdapterOwner(new Object()));
    }

    @Test public void twoPlainReloadCommitsDoNotDuplicateTheSavedShelfAfterSeveralNativeRows() {
        var headers = new LibraryLandingLogic.NativeHeaders();
        Object host = new Object();
        Object adapter = new Object();
        headers.rememberPlainResponse(host, "plain");
        headers.rememberPlainAdapter(host, adapter);
        var rendered = new ArrayList<>(List.of(messageContent("Recent activity"),
                messageContent("First library row"), messageContent("Second library row")));
        byte[] shelf = LibraryLandingLogic.markShelf(localRows().get(0));

        acceptRetainedReload(headers, adapter, "plain", rendered, shelf);
        acceptRetainedReload(headers, adapter, "plain", rendered, shelf);

        assertEquals(4, rendered.size());
        assertSame(shelf, rendered.get(3));
    }

    @Test public void validatedDeadPublishesOnlyForTheNativeFallbackOrDiskCache() {
        assertFalse(LibraryLandingLogic.bindOffline(false, false, true, true));
        assertFalse(LibraryLandingLogic.bindOffline(false, true, true, false));
        assertTrue(LibraryLandingLogic.bindOffline(false, true, true, true));
        assertTrue(LibraryLandingLogic.bindOffline(true, false, true, false));
        assertTrue(LibraryLandingLogic.bindOffline(false, false, false, false));
    }

    @Test public void publicationRequiresTheSameLiveNativeObjects() {
        Object host = new Object();
        Object model = new Object();
        Object adapter = new Object();
        Object renderer = new Object();
        Object companion = new Object();
        var owner = new LibraryLandingLogic.Ownership(
                host, model, adapter, renderer, companion, 2, true, 1, 2, 3, 4, false, false);
        assertTrue(owner.stillOwns(host, model, adapter, renderer, companion, 1, 2, 3, 4));
        assertFalse(owner.stillOwns(new Object(), model, adapter, renderer, companion, 1, 2, 3, 4));
        assertFalse(owner.stillOwns(host, new Object(), adapter, renderer, companion, 1, 2, 3, 4));
        assertFalse(owner.stillOwns(host, model, new Object(), renderer, companion, 1, 2, 3, 4));
        assertFalse(owner.stillOwns(host, model, adapter, new Object(), companion, 1, 2, 3, 4));
        assertFalse(owner.stillOwns(host, model, adapter, renderer, new Object(), 1, 2, 3, 4));
    }

    @Test public void theSameObjectsCannotReviveARevokedLease() {
        Object host = new Object();
        Object model = new Object();
        Object adapter = new Object();
        Object renderer = new Object();
        var owner = new LibraryLandingLogic.Ownership(
                host, model, adapter, renderer, null, 1, true, 1, 2, 3, 4, true, false);
        assertFalse("another commit of the same page",
                owner.stillOwns(host, model, adapter, renderer, null, 2, 2, 3, 4));
        assertFalse("newer chip request", owner.stillOwns(host, model, adapter, renderer, null, 1, 3, 3, 4));
        assertFalse("network returned", owner.stillOwns(host, model, adapter, renderer, null, 1, 2, 4, 4));
        assertFalse("copies changed", owner.stillOwns(host, model, adapter, renderer, null, 1, 2, 3, 5));
    }
}
