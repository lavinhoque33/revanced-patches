/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-27.
 */
package app.morphe.extension.music.patches.downloads;

import android.net.Uri;

import androidx.annotation.Nullable;

import com.google.protobuf.CodedOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.Key;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.VideoDetails;
import app.morphe.extension.shared.utils.Logger;

/** The debug pilot's download-time bridge to the 9.45.51 application graph. Not used by playback. */
public final class NativeOfflineDirect {
    private static volatile Object graph;

    private NativeOfflineDirect() {}

    /** Called by the app's component constructor; no offline service or download command is started here. */
    public static void captureGraph(Object component) {
        graph = component;
    }

    enum Policy {
        MISSING_SNAPSHOT("Laaqi.a@0061: no player-facing offline snapshot"),
        MISSING("Laaqi.a@0067: Laanv.i() is null"),
        REJECTED("Laaqi.a@006d: Laanu.g() rejected the policy"),
        EXPIRED("Laaqi.a@006d: Laanu.g() rejected expired policy"),
        ACCEPTED("Laaqi.a@006d: policy accepted; native cache/integrity checks still apply");

        final String reason;

        Policy(String reason) {
            this.reason = reason;
        }
    }

    record Representation(Format format, File file) {}

    private record Slot(Object store, Object metadata, Object cache, String storageId) {}

    private record Stream(Object format, String cacheKey, Representation representation, boolean audio) {
        long length() {
            return representation.format().getContentLength();
        }
    }

    /** Captures the active account and slot before the fork starts the network transfer. */
    static Session begin(String videoId) throws Exception {
        Object component = graph;
        if (!NativeOfflineImport.includes(videoId) || component == null) {
            throw new IOException("NativeOfflineImport: stock application graph is unavailable");
        }
        Object selector = call(component, "geh", "aM", "");
        Object accounts = provided(field(selector, "aaue", "c"));
        Slot slot = slot(accounts);
        rejectExisting(slot, videoId);
        return new Session(videoId, component, selector, accounts, slot);
    }

    static final class Session {
        private final String videoId;
        private final Object component;
        private final Object selector;
        private final Object accounts;
        private final Slot slot;

        private Session(String videoId, Object component, Object selector, Object accounts, Slot slot) {
            this.videoId = videoId;
            this.component = component;
            this.selector = selector;
            this.accounts = accounts;
            this.slot = slot;
        }

        Policy publish(PlayerResponse response, Representation audio, @Nullable Representation video)
                throws Exception {
            verifyInput(videoId, response, audio, video);
            requireCurrentSlot();

            Object player = require(call(null, "wjw", "aj", "[B long", response.toByteArray(), now()),
                    "stock parser rejected the player response");
            Object videoData = parse("aooc", "a", metadataOf(response.getVideoDetails()));
            Object metadata = call(null, "aanq", "b", "aooc", videoData);
            Object factory = transferFactory(selector);
            Object writer = writer(factory);
            Stream audioStream = describe(audio, true);
            List<Stream> streams = video == null ? List.of(audioStream) : List.of(describe(video, false), audioStream);
            for (Stream stream : streams) {
                requireCurrentSlot();
                write(writer, stream);
                Logger.printInfo(() -> "NativeOfflineImport: fork file committed " + stream.cacheKey()
                        + " bytes=" + stream.length());
            }

            // Insert only after all representations are covered by the live SimpleCache. The store owns
            // videosV2, the single-download linkage, stream rows and their offline observers.
            synchronized (slot.metadata()) {
                requireCurrentSlot();
                rejectExisting(slot, videoId);
                for (Stream stream : streams) checkCoverage(stream);
                Object quality = field(null, "aooy", video == null ? "e" : "c"); // AUDIO_ONLY or SD (360p)
                Object nativeQuality = call(provided(field(component, "geh", "iZ")), "aarj", "d", "aooy", quality);
                long now = now();
                requireTrue(call(slot.metadata(), "aafw", "N", "aanq aooy aoko aanp [B aani", metadata, quality,
                        nativeQuality, field(null, "aanp", "a"), new byte[0], field(null, "aani", "c")),
                        "native single-item insert failed");
                try {
                    requireTrue(call(slot.metadata(), "aafw", "B", "String wjt long boolean wjp", videoId,
                            player, now, true, field(factory, "aauq", "c")), "native player response update failed");
                    requireTrue(call(slot.metadata(), "aafw", "v", "String", videoId), "native single-item link failed");
                    for (Stream stream : streams) insertStream(stream, now);
                    call(slot.metadata(), "aafw", "X", "String long", videoId, now);
                    // COMPLETE is the transfer's byte status, independent of the genuine policy in the response.
                    call(slot.metadata(), "aafw", "T", "String aani", videoId, field(null, "aani", "b"));
                } catch (Exception ex) {
                    call(slot.metadata(), "aafw", "T", "String aani", videoId, field(null, "aani", "d"));
                    throw ex;
                }
            }
            NativeOfflinePolicy.remember(videoId, response.toByteArray());
            Policy policy = readPolicy();
            Logger.printInfo(() -> "NativeOfflineImport: fork import published v=" + videoId
                    + " streams=" + streams.size() + " native policy: " + policy.reason);
            return policy;
        }

        private void requireCurrentSlot() throws Exception {
            Slot current = slot(accounts);
            if (component != graph || current.store() != slot.store() || current.metadata() != slot.metadata()
                    || current.cache() != slot.cache() || !current.storageId().equals(slot.storageId())) {
                throw new IOException("NativeOfflineImport: active account or storage slot changed");
            }
        }

        private long now() throws Exception {
            Object instant = call(field(selector, "aaue", "e"), "lyy", "f", "");
            return (long) call(instant, "j$.time.Instant", "toEpochMilli", "");
        }

        private Object writer(Object factory) throws Exception {
            Object config = construct("aaun", "agmh jqf lyy Object ygi tfb j$.util.Optional zjm boolean",
                    field(selector, "aaue", "d"), slot.cache(), field(selector, "aaue", "e"),
                    field(null, "aaue", "b"), provided(field(selector, "aaue", "f")), field(selector, "aaue", "k"),
                    field(selector, "aaue", "h"), field(selector, "aaue", "g"), false);
            Key key = (Key) require(field(factory, "aauq", "e"), "native encryption key is unavailable");
            fieldOf("aaun", "b").set(config, key);
            fieldOf("aaun", "c").set(config, key);
            fieldOf("aaun", "d").set(config, field(factory, "aauq", "f"));
            return call(config, "aaun", "a", "");
        }

        private Stream describe(Representation representation, boolean audio) throws Exception {
            Format source = representation.format();
            Object descriptor = parse("alhj", "b", source.toByteArray());
            Object format = construct("whg", "alhj String boolean zo", descriptor, videoId, false, null);
            int itag = (int) call(format, "whg", "e", "");
            long lastModified = (long) call(format, "whg", "k", "");
            if (itag != source.getItag() || lastModified != source.getLastModified()
                    || (long) call(format, "whg", "j", "") != source.getContentLength()) {
                throw new IOException("NativeOfflineImport: native descriptor changed representation identity");
            }
            Object track = call(format, "whg", "C", "");
            String key = (String) call(null, "zib", "aW", "String int String long boolean",
                    videoId, itag, track, lastModified, false);
            if (!NativeOfflineImport.includesCacheKey(key)) {
                throw new IOException("NativeOfflineImport: stock cache key does not name the pilot");
            }
            return new Stream(format, key, representation, audio);
        }

        private void write(Object writer, Stream stream) throws Exception {
            Uri uri = (Uri) field(stream.format(), "whg", "e");
            Field source = fieldOf("ynz", "rvxDirectTransfer");
            NativeOfflineImport.Transfer transfer = NativeOfflineImport.fromFile(stream.representation().file(), uri,
                    (NativeOfflineImport.Cancellation) writer, stream.cacheKey(), stream.length());
            source.set(writer, transfer);
            try {
                call(writer, "ynz", "b", "whg long long wji String zgo zgo", stream.format(), 0L,
                        stream.length(), null, null, null, null);
                checkCoverage(stream);
            } finally {
                source.set(writer, null);
                transfer.close();
            }
        }

        private void checkCoverage(Stream stream) throws Exception {
            requireTrue(call(slot.cache(), "jqf", "o", "String long long", stream.cacheKey(), 0L, stream.length()),
                    "native cache does not cover " + stream.cacheKey());
        }

        private void insertStream(Stream stream, long now) throws Exception {
            Object builder = call(null, "aann", "e", "");
            call(builder, "aanm", "e", "whg", stream.format());
            call(builder, "aanm", "b", "boolean", stream.audio());
            call(builder, "aanm", "c", "long", stream.length());
            call(builder, "aanm", "i", "long", now);
            fieldOf("aanm", "e").set(builder, slot.storageId());
            Object row = call(builder, "aanm", "a", "");
            requireTrue(call(slot.metadata(), "aafw", "y", "aann", row), "native stream row insert failed");
        }

        private Policy readPolicy() throws Exception {
            requireCurrentSlot();
            Object snapshots = call(slot.store(), "aarr", "l", "");
            Future<?> future = (Future<?>) call(snapshots, "aarx", "e", "String", videoId);
            Object result = future.get((long) field(null, "aaqi", "b"), TimeUnit.MILLISECONDS);
            Object snapshot = call(result, "agll", "f", "");
            if (snapshot == null) return Policy.MISSING_SNAPSHOT;
            Object streams = call(snapshot, "aanv", "g", "");
            Object policy = call(snapshot, "aanv", "i", "");
            Logger.printInfo(() -> "Native offline availability: policy=" + (policy != null)
                    + " streams=" + (streams != null));
            if (policy == null) return Policy.MISSING;
            if ((boolean) call(policy, "aanu", "g", "")) return Policy.ACCEPTED;
            return (boolean) call(policy, "aanu", "f", "") ? Policy.EXPIRED : Policy.REJECTED;
        }
    }

    static boolean needsVideo(PlayerResponse response) {
        VideoDetails details = response.getVideoDetails();
        return SongIdResolver.isMusicVideo(details.hasMusicVideoType(), details.getMusicVideoType());
    }

    static boolean hasPilotVideoType(PlayerResponse response) {
        VideoDetails details = response.getVideoDetails();
        return details.hasMusicVideoType() && (details.getMusicVideoType() == 1 || needsVideo(response));
    }

    static boolean hasIdentity(Format format) {
        return format.getItag() > 0 && format.getLastModified() > 0 && format.getContentLength() > 0
                && !format.getUrl().isEmpty();
    }

    static void verifyInput(String videoId, PlayerResponse response, Representation audio,
                            @Nullable Representation video) throws IOException {
        if (!videoId.equals(response.getVideoDetails().getVideoId()) || !hasPilotVideoType(response)
                || needsVideo(response) != (video != null)) {
            throw new IOException("NativeOfflineImport: recording or adaptive pair does not match the response");
        }
        List<Format> formats = response.getStreamingData().getAdaptiveFormatsList();
        verifyRepresentation(formats, audio, "audio/");
        if (video != null) {
            verifyRepresentation(formats, video, "video/");
            if (audio.format().getItag() == video.format().getItag() || audio.file().equals(video.file())) {
                throw new IOException("NativeOfflineImport: adaptive streams are not distinct");
            }
        }
    }

    private static void verifyRepresentation(List<Format> formats, Representation stream, String mime) throws IOException {
        if (!hasIdentity(stream.format()) || !stream.format().getMimeType().startsWith(mime)
                || !formats.contains(stream.format()) || !stream.file().isFile()
                || stream.file().length() != stream.format().getContentLength()) {
            throw new IOException("NativeOfflineImport: incomplete or unrelated " + mime + " representation");
        }
    }

    // Laooc's id, thumbnails, title and duration seconds. Only fields supplied by the player response are projected.
    private static byte[] metadataOf(VideoDetails details) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        output.writeString(1, details.getVideoId());
        if (details.hasThumbnail()) output.writeByteArray(2, details.getThumbnail().toByteArray());
        if (details.hasTitle()) output.writeString(4, details.getTitle());
        if (details.hasLengthSeconds()) output.writeInt64(6, details.getLengthSeconds());
        output.flush();
        return bytes.toByteArray();
    }

    private static Slot slot(Object accounts) throws Exception {
        if (!(boolean) call(accounts, "aaod", "i", "")) {
            throw new IOException("NativeOfflineImport: stock offline account is unavailable");
        }
        Object store = require(call(accounts, "aaod", "b", ""), "active offline store is unavailable");
        if ("NO_OP_STORE_TAG".equals(call(store, "aarr", "s", ""))) {
            throw new IOException("NativeOfflineImport: active store is a no-op");
        }
        Object supplier = require(call(store, "aarr", "c", ""), "live cache supplier is unavailable");
        Object slot = require(call(supplier, "aaef", "c", ""), "live storage slot is unavailable");
        String storageId = (String) require(field(slot, "aane", "a"), "native storage ID is unavailable");
        Object cache = require(field(slot, "aane", "b"), "native cache is unavailable");
        Object metadata = require(call(store, "aarr", "d", ""), "native metadata store is unavailable");
        return new Slot(store, metadata, cache, storageId);
    }

    private static void rejectExisting(Slot slot, String videoId) throws Exception {
        if (call(slot.metadata(), "aafw", "f", "String", videoId) != null) {
            throw new IOException("NativeOfflineImport: a native row already exists; leaving it unchanged");
        }
    }

    private static Object transferFactory(Object selector) throws Exception {
        Map<?, ?> factories = (Map<?, ?>) field(selector, "aaue", "i");
        for (Object provider : factories.values()) {
            Object candidate = provided(provider);
            if (type("aauq").isInstance(candidate)) return candidate;
        }
        throw new IOException("NativeOfflineImport: stock offline transfer factory is unavailable");
    }

    private static Object provided(Object provider) throws Exception {
        return call(provider, "awug", "is", "");
    }

    private static Object parse(String name, String defaultField, byte[] bytes) throws Exception {
        return require(call(null, "wkf", "E", "[B com.google.protobuf.MessageLite", bytes,
                        field(null, name, defaultField)),
                "stock proto parser rejected " + name);
    }

    // The same parser the fork's publish path uses, so the kept response keeps its stream identities.
    static Object parsePlayerResponse(byte[] bytes) throws Exception {
        return require(call(null, "wjw", "aj", "[B long", bytes, System.currentTimeMillis()),
                "stock parser rejected the kept player response");
    }

    // A free account's response carries no offline state, so the stock writer stores none and the offline
    // subsystem aborts (null state in aaij). Attach a playable state from the refresh response or build one.
    static void ensurePlayableState(Object kept, Object incoming) throws Exception {
        Object state = responseState(kept);
        if (state == null) state = responseState(incoming);
        if (state == null) state = newPlayableState();
        else markPlayable(state);
        attachState(kept, state);
    }

    static Object responseState(Object response) throws Exception {
        return call(response, "wjt", "v", "");
    }

    // Built through the stock proto builder the app itself uses for offline state updates.
    static Object newPlayableState() throws Exception {
        Object builder = construct("ainr", "ainz", field(null, "aonq", "a"));
        call(builder, "ainr", "copyOnWrite", "");
        Object state = field(builder, "ainr", "instance");
        markPlayable(state);
        return call(builder, "ainr", "build", "");
    }

    static void markPlayable(Object state) throws Exception {
        // Never mutate the proto's shared default instance.
        if (state == field(null, "aonq", "a")) return;

        Object playable = field(null, "aonp", "b");
        int bits = (int) fieldOf("aonq", "b").get(state);
        fieldOf("aonq", "h").setInt(state, (int) field(playable, "aonp", "h"));
        fieldOf("aonq", "b").setInt(state, bits | 0x8);
    }

    static void attachState(Object response, Object state) throws Exception {
        Object proto = field(response, "wjw", "a");
        fieldOf("amci", "l").set(proto, state);
        fieldOf("amci", "b").setInt(proto, (int) fieldOf("amci", "b").get(proto) | 0x80);
    }

    private static Object require(@Nullable Object value, String why) throws IOException {
        if (value == null) throw new IOException("NativeOfflineImport: " + why);
        return value;
    }

    private static void requireTrue(Object value, String why) throws IOException {
        if (!(boolean) value) throw new IOException("NativeOfflineImport: " + why);
    }

    // All symbolic lookups here are stock 9.45.51 descriptors; the installer gates the only entry point.
    private static Class<?> type(String name) throws ClassNotFoundException {
        return switch (name) {
            case "boolean" -> boolean.class;
            case "int" -> int.class;
            case "long" -> long.class;
            case "Object" -> Object.class;
            case "String" -> String.class;
            default -> Class.forName(name, false, NativeOfflineDirect.class.getClassLoader());
        };
    }

    private static Class<?>[] signature(String parameters) throws ClassNotFoundException {
        if (parameters.isEmpty()) return new Class<?>[0];
        String[] names = parameters.split(" ");
        Class<?>[] types = new Class<?>[names.length];
        for (int i = 0; i < types.length; i++) types[i] = type(names[i]);
        return types;
    }

    private static Field fieldOf(String owner, String name) throws ReflectiveOperationException {
        Field field = type(owner).getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object field(@Nullable Object instance, String owner, String name) throws ReflectiveOperationException {
        return fieldOf(owner, name).get(instance);
    }

    private static Object call(@Nullable Object instance, String owner, String name, String parameters, Object... args)
            throws Exception {
        Method method = type(owner).getDeclaredMethod(name, signature(parameters));
        method.setAccessible(true);
        try {
            return method.invoke(instance, args);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof Exception cause) throw cause;
            throw ex;
        }
    }

    private static Object construct(String owner, String parameters, Object... args) throws Exception {
        var constructor = type(owner).getDeclaredConstructor(signature(parameters));
        constructor.setAccessible(true);
        try {
            return constructor.newInstance(args);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof Exception cause) throw cause;
            throw ex;
        }
    }
}
