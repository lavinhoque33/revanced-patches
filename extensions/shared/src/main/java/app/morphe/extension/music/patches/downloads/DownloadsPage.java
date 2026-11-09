/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-08.
 */
package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.utils.StringRef.str;

import androidx.annotation.Nullable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.music.patches.downloads.DownloadsPageLogic.Layout;
import app.morphe.extension.music.patches.downloads.OfflineLibraryLogic.SongRow;
import app.morphe.extension.music.patches.downloads.OfflineLibraryLogic.SourceRow;
import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/**
 * The stream copies inside the Library's Downloads chip view. The app builds that page itself, from its own
 * downloads, and hands it to a callback. The callback is replaced by one that adds the albums, playlists and songs of
 * the stream copies to the page before the app's own callback gets it, and takes out the app's empty state when
 * there is nothing else; the chips, Back and the Library page around it are the app's own. Nothing is written to the
 * app's downloads, so the stream copies stay apart from them.
 *
 * <p>Any failure hands the app's own page on, untouched, and the main thread never waits for the stores.
 */
public final class DownloadsPage {
    /** Added by the Downloads patch to the request of the page: its message, serialized. */
    public interface PageRequest {
        byte[] rvxRequestBytes();
    }

    /** Added by the Downloads patch to the page response: the section list it holds, and a response with another. */
    public interface PageResponse {
        byte[] rvxPageBytes();

        Object rvxWithPageBytes(byte[] page);
    }

    private static final DownloadsPageLogic.Taps TAPS = new DownloadsPageLogic.Taps() {
        @Override public byte[] source(SourceRow row) {
            return OfflineLibraryLogic.browseCommand("VL" + row.sourceId());
        }

        @Override public byte[] song(SongRow row) {
            return OnlineWatchCommand.build(row.videoId(), null, 0);
        }
    };

    private DownloadsPage() {}

    /**
     * Wraps the callback of a page request. Hook entry, on the thread that asks for the page.
     *
     * @return the callback to ask with: the app's own for a page this does not touch, whatever goes wrong.
     */
    static Object wrap(Object request, Object callback) {
        try {
            if (!OfflineReplay.enabled() || !(request instanceof PageRequest page)) return callback;

            String token = DownloadsPageLogic.tokenOf(page.rvxRequestBytes());
            Layout layout = DownloadsPageLogic.layoutOf(token);
            Logger.printDebug(() -> "DownloadsPage: page token=" + token + " layout=" + layout);
            if (layout == Layout.NONE) return callback;

            OfflineCopyStore.warmUp();
            Class<?>[] interfaces = interfacesOf(callback.getClass()).toArray(new Class<?>[0]);
            return Proxy.newProxyInstance(callback.getClass().getClassLoader(), interfaces,
                    new Delivery(callback, layout));
        } catch (Exception ex) {
            Logger.printException(() -> "DownloadsPage: could not wrap the page callback", ex);
            return callback;
        }
    }

    /** Every interface a class implements, the ones of its superclasses and the inherited ones included. */
    private static Set<Class<?>> interfacesOf(Class<?> type) {
        Set<Class<?>> found = new LinkedHashSet<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Class<?> implemented : current.getInterfaces()) addWithParents(found, implemented);
        }
        return found;
    }

    private static void addWithParents(Set<Class<?>> found, Class<?> type) {
        if (!found.add(type)) return;

        for (Class<?> parent : type.getInterfaces()) addWithParents(found, parent);
    }

    /** Passes every call on to the app's callback; the page it is given is the one with the stream copies. */
    private static final class Delivery implements InvocationHandler {
        private final Object callback;
        private final Layout layout;

        Delivery(Object callback, Layout layout) {
            this.callback = callback;
            this.layout = layout;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);

            boolean page = args != null && args.length == 1 && method.getParameterTypes()[0] == Object.class
                    && args[0] instanceof PageResponse;
            if (!page) return call(method, args);

            PageResponse response = (PageResponse) args[0];
            if (Utils.isCurrentlyOnMainThread()) {
                Utils.runOnBackgroundThread(() -> {
                    Object answer = withCopies(response);
                    Utils.runOnMainThread(() -> deliver(method, answer));
                });
            } else {
                deliver(method, withCopies(response));
            }
            return null;
        }

        private Object objectMethod(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> callback.toString();
            };
        }

        private Object call(Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(callback, args);
            } catch (InvocationTargetException ex) {
                throw ex.getCause() != null ? ex.getCause() : ex;
            }
        }

        private void deliver(Method method, Object response) {
            try {
                call(method, new Object[]{response});
            } catch (Throwable failure) {
                Logger.printException(() -> "DownloadsPage: the page callback failed", failure);
            }
        }

        /** The response with the stream copies in it, or the app's own when they cannot be added. */
        private Object withCopies(PageResponse response) {
            try {
                byte[] merged = DownloadsPageLogic.merge(response.rvxPageBytes(), copySections());
                Logger.printDebug(() -> "DownloadsPage: layout=" + layout + " merged=" + (merged != null));
                return merged == null ? response : response.rvxWithPageBytes(merged);
            } catch (Exception ex) {
                Logger.printException(() -> "DownloadsPage: could not add the stream copies", ex);
                return response;
            }
        }

        /** The sections of the stream copies for the layout, by the network state when the page is built. */
        private List<byte[]> copySections() {
            boolean online = OfflineReplay.networkAvailable();
            List<byte[]> items = DownloadsPageLogic.sections(layout, online, sourceRows(), songRows(), labels(),
                    TAPS, OfflineCopyStore::coverUrl);
            Logger.printDebug(() -> "DownloadsPage: online=" + online + " sections=" + items.size());
            return items;
        }
    }

    private static List<SourceRow> sourceRows() {
        return OfflineLibraryLogic.sourceRows(OfflineCopyStore.sources(), OfflineCopyStore::tracksOf);
    }

    private static List<SongRow> songRows() {
        List<String> queue = new ArrayList<>();
        for (OfflineWatchNext.Item item : OfflineReplay.queueOf(OfflineReplay.SONGS)) queue.add(item.videoId());
        return OfflineLibraryLogic.songRows(OfflineCopyStore.copiesWithData(), RawAudioStore.copyIds(), queue);
    }

    private static DownloadsPageLogic.Labels labels() {
        return new DownloadsPageLogic.Labels(str("morphe_music_offline_library_albums"),
                str("morphe_music_offline_library_songs"), str("morphe_music_offline_library_untitled"),
                str("morphe_music_offline_library_unavailable_title"),
                str("morphe_music_offline_library_unavailable"),
                count -> count == 1
                        ? str("morphe_music_offline_library_song_one")
                        : str("morphe_music_offline_library_songs_count", count));
    }
}
