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
import java.lang.ref.WeakReference;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;

/**
 * Native Library and shelf wire shapes. The exact native message/companion pair can be replaced without
 * discarding a populated cached section list; an inserted shelf carries its own stable provenance.
 */
final class LibraryLandingLogic {
    private static final int SECTION_CONTENT_FIELD = 1;
    private static final int ITEM_CONTENT = 50_195_462;
    private static final int MESSAGE_SLOT = 1;
    private static final int METADATA_SLOT = 4;
    private static final int MESSAGE_RENDERER = 58_508_690;
    private static final int MUSIC_SHELF = 91_303_872;
    private static final int BROWSE_ENDPOINT = 48_687_626;
    private static final int BROWSE_ID = 2;
    // Native BrowseCommand decorators, not part of BrowseEndpoint:
    // 9.45.51 hkq.W sets the previous page's tracking ID; a.N sets the Elements origin's tracking ID.
    private static final int TAB_CONTEXT = 94_476_261;
    private static final int ELEMENTS_CONTEXT = 144_224_893;
    // Like the Downloads card marker, Lite preserves this unknown field through native parcel restore.
    private static final int LOCAL_SHELF = 500_000_002;
    private static final byte[] SHELF_TAIL = new ProtoWriter().bool(LOCAL_SHELF, true).toByteArray();

    static final byte[] NO_ROWS = new byte[0];

    private static final int BYTES = WireFormat.WIRETYPE_LENGTH_DELIMITED;

    private LibraryLandingLogic() {}

    enum Target { OTHER, PLAIN, ELEMENTS_PLAIN }

    enum BrowseFailure { NONE, OS_OFFLINE, UPSTREAM_DEAD, TRANSPORT }

    static boolean bindOffline(boolean nativeNoCache, boolean diskCache, boolean osOnline, boolean upstreamDead) {
        return nativeNoCache || !osOnline || diskCache && upstreamDead;
    }

    static BrowseFailure browseFailure(boolean osOnline, boolean upstreamDead, @Nullable Throwable failure,
                                       boolean interrupted) {
        if (failure == null || interrupted) return BrowseFailure.NONE;
        boolean transport = false;
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof CancellationException || cause instanceof InterruptedException) {
                return BrowseFailure.NONE;
            }
            transport |= cause instanceof ConnectException || cause instanceof NoRouteToHostException
                    || cause instanceof SocketTimeoutException || cause instanceof UnknownHostException;
        }
        // Do not treat a transport error from another stage as proof that the OS is offline.
        if (!osOnline) return BrowseFailure.OS_OFFLINE;
        if (upstreamDead) return BrowseFailure.UPSTREAM_DEAD;
        return transport || PlayerFallbackLogic.isCronetNetworkFailure(failure, false)
                ? BrowseFailure.TRANSPORT : BrowseFailure.NONE;
    }

    /** Debug-only: tags and wire types, not URLs, parameters, session data or titles. */
    static String wireShape(@Nullable byte[] message) {
        if (message == null) return "absent";
        try {
            var input = CodedInputStream.newInstance(message);
            var shape = new StringBuilder("[");
            for (int count = 0; !input.isAtEnd(); count++) {
                if (count == 16) return shape.append(",…]").toString();
                if (count != 0) shape.append(',');
                int tag = input.readTag();
                shape.append(WireFormat.getTagFieldNumber(tag)).append(':').append(WireFormat.getTagWireType(tag));
                if (!skip(input, tag)) return "invalid";
            }
            return shape.append(']').toString();
        } catch (IOException ex) {
            return "invalid";
        }
    }

    private static final int RELOAD_ENDPOINT = 120_837_120;
    private static final int CONTINUATION_ENDPOINT = 60_487_319;
    private static final int ELEMENTS_HEADER = 153_515_154;

    /** Debug-only slots of BrowseSectionListReloadEndpoint, as read by the native resolver. */
    static String reloadShape(@Nullable byte[] command, @Nullable byte[] plainElements) {
        if (command == null || command.length > 65_536) return command == null ? "absent" : "oversize";
        try {
            byte[] reload = NativeDownloads.fieldBytes(command, RELOAD_ENDPOINT);
            if (reload == null) return "absent";
            byte[] group = NativeDownloads.fieldBytes(reload, 1);
            byte[] continuation = group == null ? null : NativeDownloads.fieldBytes(group, CONTINUATION_ENDPOINT);
            byte[] header = NativeDownloads.fieldBytes(reload, 5);
            byte[] elements = header == null ? null : NativeDownloads.fieldBytes(header, ELEMENTS_HEADER);
            return wireShape(reload) + " group=" + wireShape(group) + " continuation=" + wireShape(continuation)
                    + " embedded-header=" + wireShape(header) + " embedded-elements=" + wireShape(elements)
                    + " embedded-equals-fresh=" + (elements != null && Arrays.equals(elements, plainElements));
        } catch (IOException ex) {
            return "invalid";
        }
    }

    static boolean isPlainCommand(@Nullable byte[] command) {
        return commandTarget(command) != Target.OTHER;
    }

    /** Confirm one unparameterized browse endpoint, allowing only the native command envelope metadata. */
    static Target commandTarget(@Nullable byte[] command) {
        if (command == null) return Target.OTHER;
        try {
            boolean plain = false;
            int contexts = 0;
            boolean hasClickTracking = false;
            CodedInputStream input = CodedInputStream.newInstance(command);
            while (!input.isAtEnd()) {
                int tag = input.readTag();
                int field = WireFormat.getTagFieldNumber(tag);
                if (field == BROWSE_ENDPOINT) {
                    if (plain || tag != tag(BROWSE_ENDPOINT)) return Target.OTHER;
                    int limit = enter(input);
                    if (input.readTag() != tag(BROWSE_ID)
                            || !LibraryRestoreLogic.isLanding(input.readStringRequireUtf8()) || !input.isAtEnd()) {
                        return Target.OTHER;
                    }
                    input.popLimit(limit);
                    plain = true;
                } else if (field == TAB_CONTEXT || field == ELEMENTS_CONTEXT) {
                    int flag = field == TAB_CONTEXT ? 1 : 2;
                    if ((contexts & flag) != 0 || tag != tag(field)) return Target.OTHER;
                    int limit = enter(input);
                    if (!onlyField(input, 1)) return Target.OTHER;
                    input.popLimit(limit);
                    contexts |= flag;
                } else if (field < 2 || field > 4 || tag != tag(field) || !input.skipField(tag)) {
                    return Target.OTHER;
                } else if (field == 4) {
                    hasClickTracking = true;
                }
            }
            if (!plain) return Target.OTHER;
            return hasClickTracking && contexts == 3 ? Target.ELEMENTS_PLAIN : Target.PLAIN;
        } catch (IOException ex) {
            return Target.OTHER;
        }
    }

    /**
     * Only exactly two entries qualify. Other section-list fields are not changed, but a malformed occurrence
     * of the entries field or an extra entry disqualifies the whole list.
     */
    static boolean hasPlaceholder(byte[] page) {
        try {
            int item = 0;
            CodedInputStream input = CodedInputStream.newInstance(page);
            while (!input.isAtEnd()) {
                int tag = input.readTag();
                if (WireFormat.getTagFieldNumber(tag) != SECTION_CONTENT_FIELD) {
                    if (!skip(input, tag)) return false;
                    continue;
                }
                if (tag != tag(SECTION_CONTENT_FIELD) || item == 2) return false;

                int entryLimit = enter(input);
                if (input.readTag() != tag(ITEM_CONTENT)) return false;
                int contentLimit = enter(input);
                if (!(item == 0 ? isMessageContent(input) : isCompanionContent(input))) return false;
                input.popLimit(contentLimit);
                if (!input.isAtEnd()) return false;
                input.popLimit(entryLimit);
                item++;
            }
            return item == 2;
        } catch (IOException ex) {
            return false;
        }
    }

    /** Validate the native renderer objects restored by the adapter, not an unrelated tab holder. */
    static boolean isEmptyPair(byte[] message, @Nullable byte[] companion) {
        try {
            return companion != null && isMessageContent(CodedInputStream.newInstance(message))
                    && isCompanionContent(CodedInputStream.newInstance(companion));
        } catch (IOException ex) {
            return false;
        }
    }

    static boolean isEmptyRenderer(byte[] message) {
        try {
            return isMessageContent(CodedInputStream.newInstance(message));
        } catch (IOException ex) {
            return false;
        }
    }

    /** Only an unambiguous shelf item can be decoded by the stock item's generated parser. */
    @Nullable
    static byte[] shelfItem(List<byte[]> rows) {
        if (rows.size() != 1) return null;
        try {
            byte[] row = rows.get(0);
            return onlyField(CodedInputStream.newInstance(row), MUSIC_SHELF) ? row : null;
        } catch (IOException ex) {
            return null;
        }
    }

    static byte[] markShelf(byte[] shelf) {
        byte[] marked = Arrays.copyOf(shelf, shelf.length + SHELF_TAIL.length);
        System.arraycopy(SHELF_TAIL, 0, marked, shelf.length, SHELF_TAIL.length);
        return marked;
    }

    static boolean isLocalShelf(@Nullable byte[] item) {
        if (item == null || item.length <= SHELF_TAIL.length) return false;
        int offset = item.length - SHELF_TAIL.length;
        for (int i = 0; i < SHELF_TAIL.length; i++) {
            if (item[offset + i] != SHELF_TAIL[i]) return false;
        }
        try {
            CodedInputStream input = CodedInputStream.newInstance(item, 0, offset);
            return onlyField(input, MUSIC_SHELF);
        } catch (IOException ex) {
            return false;
        }
    }

    static boolean alreadyPublished(@Nullable byte[] first, @Nullable byte[] tail) {
        return isLocalShelf(first) || isLocalShelf(tail);
    }

    /** Every identity and epoch is compared, even when a chip round trip restores the same objects. */
    record Ownership(Object host, Object model, Object adapter, Object content, Object companion,
                     int count, boolean placeholder, long pageEpoch, long contentEpoch, long reconnectEpoch,
                     int revision, boolean nativeNoCache, boolean diskCache) {
        boolean stillOwns(Object liveHost, Object liveModel, Object liveAdapter, Object liveContent,
                          Object liveCompanion, long currentPageEpoch, long currentContentEpoch,
                          long currentReconnectEpoch, int currentRevision) {
            return host == liveHost && model == liveModel && adapter == liveAdapter
                    && content == liveContent && companion == liveCompanion
                    && pageEpoch == currentPageEpoch && contentEpoch == currentContentEpoch
                    && reconnectEpoch == currentReconnectEpoch && revision == currentRevision;
        }
    }

    /** Main-thread only. Retain native payloads, not the view or its binding to an old adapter. */
    static final class NativeHeaders {
        record Header(Object payload, boolean fromPlainResponse) {}

        private final WeakHashMap<Object, WeakReference<Object>> loadingOwners = new WeakHashMap<>();
        private final WeakHashMap<Object, Header> headers = new WeakHashMap<>();
        private final WeakHashMap<Object, WeakReference<Object>> plainAdapters = new WeakHashMap<>();

        void expectLoading(Object owner, Object container) {
            loadingOwners.put(container, new WeakReference<>(owner));
        }

        @Nullable
        Object loadingOwner(Object container) {
            var owner = loadingOwners.get(container);
            return owner == null ? null : owner.get();
        }

        void rememberLoading(Object owner, Object payload) {
            var current = headers.get(owner);
            if (current == null || !current.fromPlainResponse()) headers.put(owner, new Header(payload, false));
        }

        void rememberPlainResponse(Object owner, Object payload) {
            headers.put(owner, new Header(payload, true));
        }

        void rememberPlainAdapter(Object owner, Object adapter) {
            plainAdapters.put(adapter, new WeakReference<>(owner));
        }

        @Nullable
        Object plainAdapterOwner(Object adapter) {
            var owner = plainAdapters.get(adapter);
            return owner == null ? null : owner.get();
        }

        @Nullable
        Object plainPayload(Object owner) {
            var plain = headers.get(owner);
            return plain != null && plain.fromPlainResponse() ? plain.payload() : null;
        }

        boolean matchesRetainedPlain(Object owner, @Nullable Object payload, boolean hasNativeChips) {
            if (!hasNativeChips || payload == null) return false;
            var plain = plainPayload(owner);
            return plain != null && plain.equals(payload);
        }

        @Nullable
        Header forFallback(Object owner, boolean nativeNoCache, boolean emptyContainer) {
            return nativeNoCache && emptyContainer ? headers.get(owner) : null;
        }
    }

    /**
     * The shelf items of the stream copies, kept for one revision of the copies. A build is started once per
     * revision however many times the page binds meanwhile, and the rows of an older revision are never kept.
     */
    static final class RowsCache {
        private static final int NONE = -1;

        private int builtFor = NONE;
        private byte[] rows = NO_ROWS;
        private int buildingFor = NONE;

        /** The encoded shelf for the revision, null for a cache miss and empty for a built result with no rows. */
        @Nullable
        synchronized byte[] rowsFor(int generation) {
            return builtFor == generation ? rows : null;
        }

        /** Whether the caller is to build the rows of the revision: not built and not being built already. */
        synchronized boolean startBuild(int generation) {
            if (builtFor == generation || buildingFor == generation) return false;

            buildingFor = generation;
            return true;
        }

        /**
         * Keeps the rows built for {@code builtGeneration}, if the copies are still at that revision.
         *
         * @return whether they were kept; a result of an older revision is dropped.
         */
        synchronized boolean publish(int builtGeneration, int currentGeneration, byte[] built) {
            if (buildingFor == builtGeneration) buildingFor = NONE;
            if (builtGeneration != currentGeneration) return false;

            builtFor = builtGeneration;
            rows = built;
            return true;
        }

        /** The build of the revision ended without rows; the next bind may start one again. */
        synchronized void failed(int generation) {
            if (buildingFor == generation) buildingFor = NONE;
        }
    }

    private static boolean isMessageContent(CodedInputStream input) throws IOException {
        if (input.readTag() != tag(MESSAGE_SLOT)) return false;
        int limit = enter(input);
        if (!onlyField(input, MESSAGE_RENDERER)) return false;
        input.popLimit(limit);
        return onlyField(input, METADATA_SLOT);
    }

    private static boolean isCompanionContent(CodedInputStream input) throws IOException {
        if (input.readTag() != tag(METADATA_SLOT)) return false;
        int limit = enter(input);
        // This client metadata is opaque to the generated renderer. Corroborate its four distinct
        // fields without assuming that a length-delimited value is another renderable message.
        int fields = 0;
        while (!input.isAtEnd()) {
            int tag = input.readTag();
            int field = WireFormat.getTagFieldNumber(tag);
            if (field < 1 || field > 4 || (fields & (1 << field)) != 0 || !skip(input, tag)) return false;
            fields |= 1 << field;
        }
        input.popLimit(limit);
        return fields == 0b11110 && input.isAtEnd();
    }

    private static int enter(CodedInputStream input) throws IOException {
        return input.pushLimit(input.readRawVarint32());
    }

    private static boolean onlyField(CodedInputStream input, int field) throws IOException {
        int tag = tag(field);
        return input.readTag() == tag && input.skipField(tag) && input.isAtEnd();
    }

    private static int tag(int number) {
        return (number << 3) | BYTES;
    }

    private static boolean skip(CodedInputStream input, int tag) throws IOException {
        int type = WireFormat.getTagWireType(tag);
        return type != WireFormat.WIRETYPE_START_GROUP && type != WireFormat.WIRETYPE_END_GROUP
                && input.skipField(tag);
    }
}
