/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27.
 * Written by lavinhoque33, 2026-11-26.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.utils.Logger;

/**
 * The surfaces of the redesigned player that a song played from its local copy has no content for: the Lyrics pill
 * beside Like and Dislike, and the command behind the song title (the Related page). When the metadata read of
 * {@link OfflineWatchMetadata} has the song's own served response, this hands them what the app itself does when it
 * is served that response: the pill is bound by the player's own binder, the engagement panel of the pill is
 * registered in the player's own panel controller, and the title answers with its endpoint.
 *
 * <p>Nothing here changes the stream, the queue, Up Next or the position, and no panel is opened: panels are only
 * registered, so the pill opens its own when it is tapped. Everything runs on the main thread inside the guarded
 * publish of {@link OfflineWatchMetadata}, which has already established that the response is for the exact song that
 * is playing now.
 */
@SuppressWarnings("unused")
public final class ReconnectSurfaces {
    /** The player's presenter of the actions beside the song title (the Lyrics pill is bound by it). */
    public interface Presenter {
        void rvxBindLyrics(String videoId, Object reply);
    }

    /** The player's controller of engagement panels; registering the panels of a response is what it does for any. */
    public interface Controller {
        void rvxRegisterPanels(Object reply);
    }

    /** The player's title, which draws the Related chevron by what its command getter answers. */
    public interface Title {
        void rvxRefreshRelated();
    }

    /** A protobuf message of the app, serialized to its wire bytes. */
    public interface Message {
        byte[] rvxMessageBytes();
    }

    /** The served response of a song, as the bytes of its watch response. */
    public interface Source {
        byte[] rvxMetadataBytes();
    }

    /** The metadata host, which parses the bytes of a command into the app's own command message. */
    public interface CommandParser {
        Object rvxParseCommand(byte[] command);
    }

    /** What the worker took out of the served response of the song, to apply on the main thread. */
    record Prepared(Object reply, ReconnectSurfacesLogic.Related title) { }

    /** A successful renderer bind is remembered only for the corresponding presenter and response. */
    static final class PlayerBinding {
        final long epoch;
        final Prepared prepared;
        private @Nullable WeakReference<Presenter> bound;

        PlayerBinding(long epoch, Prepared prepared) {
            this.epoch = epoch;
            this.prepared = prepared;
        }

        boolean needsLyrics(Presenter actions, long currentEpoch, @Nullable Object local, Object stockReply) {
            return epoch == currentEpoch && stockReply == local && (bound == null || bound.get() != actions);
        }

        void lyricsBound(@Nullable Presenter actions) {
            if (actions != null) bound = new WeakReference<>(actions);
        }
    }

    record Published(@Nullable Object command, boolean lyricsBound, String lyrics, String related,
                     PanelTrace trace) { }

    private record PanelTrace(@Nullable byte[] action, ReconnectSurfacesLogic.PanelTarget button,
                              ReconnectSurfacesLogic.PanelTarget title, Set<String> available, int unkeyed,
                              Set<String> registered, boolean elementsMatch) {
        String describe() {
            return "renderer=" + (action == null ? "none" : ReconnectSurfacesLogic.describeCommand(action))
                    + " button=" + button.path() + "/" + keyToken(button.key())
                    + " title=" + title.path() + "/" + keyToken(title.key())
                    + " available=" + keyTokens(available) + " unkeyed=" + unkeyed
                    + " accepted=" + keyTokens(registered) + " elementsMatch=" + elementsMatch;
        }
    }

    private static final class KeyTrace {
        static final byte[] SALT = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
    }

    private static final class PillCapture {
        final Thread owner = Thread.currentThread();
        final Presenter presenter;
        @Nullable byte[] renderer;

        PillCapture(Presenter presenter) {
            this.presenter = presenter;
        }
    }

    /** Which panels may register while a response is replayed through the panel controller, main thread only. */
    private static final class PanelFilter {
        final Thread owner = Thread.currentThread();
        @Nullable final Set<String> allowed;
        final Set<String> seen = new LinkedHashSet<>();
        int unkeyed;

        PanelFilter(@Nullable Set<String> allowed) {
            this.allowed = allowed;
        }
    }

    private static volatile WeakReference<Presenter> presenter = new WeakReference<>(null);
    private static volatile WeakReference<Controller> controller = new WeakReference<>(null);
    private static volatile WeakReference<Title> title = new WeakReference<>(null);
    private static volatile PanelFilter filter;
    @Nullable private static volatile PillCapture capture;

    private ReconnectSurfaces() { }

    public static void presenter(Object instance, String videoId, Object reply) {
        if (!(instance instanceof Presenter live) || capture != null) return;

        if (presenter.get() != live) presenter = new WeakReference<>(live);
        OfflineWatchMetadata.presenterBound(live, videoId, reply);
    }

    @Nullable
    static Presenter currentPresenter() {
        return presenter.get();
    }

    public static void controller(Object instance) {
        if (instance instanceof Controller live && controller.get() != live) {
            controller = new WeakReference<>(live);
        }
    }

    /** The bound object is the native Elements renderer, not necessarily the command dispatched when tapped. */
    public static void pillAction(Object instance, Object action) {
        PillCapture active = capture;
        if (active == null || active.owner != Thread.currentThread() || active.presenter != instance
                || !(action instanceof Message message)) return;

        try {
            active.renderer = message.rvxMessageBytes();
        } catch (Exception | LinkageError failure) {
            Logger.printException(() -> "ReconnectSurfaces: could not read the Lyrics renderer", failure);
        }
    }

    /**
     * Whether the panel controller is to register the panel {@code id}. It always is, except while a response is
     * replayed through the controller by {@link #publish}: then only the panels asked for are.
     */
    public static boolean allowsPanel(@Nullable String id) {
        PanelFilter active = filter;
        if (active == null || active.owner != Thread.currentThread()) return true;

        if (id == null || id.isEmpty()) {
            active.unkeyed++;
            return false;
        }

        boolean allowed = active.allowed != null && active.allowed.contains(id);
        if (active.allowed == null || allowed) active.seen.add(id);
        return allowed;
    }

    /**
     * The title's command getter. Stock answers first; the song's own command is given only when the stock title has
     * none, which is the song played from its local copy, and only while that song is the one playing.
     */
    public static Object related(Object titleView) {
        if (titleView instanceof Title live && title.get() != live) title = new WeakReference<>(live);

        return OfflineWatchMetadata.relatedCommand();
    }

    /** Makes the title draw the Related chevron for the command it was just given, if it is on screen. */
    static void refreshTitle() {
        Title live = title.get();
        if (live == null) return;

        try {
            live.rvxRefreshRelated();
        } catch (Exception | LinkageError failure) {
            Logger.printException(() -> "ReconnectSurfaces: could not refresh the title", failure);
        }
    }

    /** Worker side: the title command of the song in its served response. Null when the host cannot serve any. */
    @Nullable
    static Prepared prepare(Object remote, String videoId) {
        if (!(remote instanceof Source source)) return null;

        byte[] response = source.rvxMetadataBytes();
        return new Prepared(remote, ReconnectSurfacesLogic.related(response, videoId));
    }

    /**
     * Main thread, for the song the caller has shown to be the one playing: binds the Lyrics pill, registers the
     * engagement panel that the pill (or the title command) refers to, and parses the title command.
     */
    static Published publish(OfflineWatchMetadata.Host host, String videoId, Prepared prepared,
                             @Nullable Presenter actions) {
        Controller panels = controller.get();
        PanelFilter offered = panelsOf(panels, prepared.reply());
        Set<String> available = offered.seen;
        byte[] action = actions == null ? null : bindLyrics(actions, videoId, prepared.reply());
        var button = ReconnectSurfacesLogic.buttonPanel(action);
        var title = ReconnectSurfacesLogic.commandPanel(prepared.title().command());

        Set<String> wanted = new LinkedHashSet<>();
        if (button.key() != null && available.contains(button.key())) wanted.add(button.key());
        // Preserve an existing Elements renderer's unambiguous match. Its actual tap command can differ.
        boolean elementsMatch = false;
        if (button.path().equals("elements-input")) {
            Set<String> candidates = ReconnectSurfacesLogic.referencedPanels(available, action);
            elementsMatch = candidates.size() == 1;
            if (elementsMatch) wanted.addAll(candidates);
        }
        int lyricsTargets = wanted.size();

        Object command = parse(host, prepared.title().command());
        if (command != null && title.key() != null && available.contains(title.key())) wanted.add(title.key());
        Set<String> registered = panels == null || wanted.isEmpty()
                ? Set.of() : replayPanels(panels, prepared.reply(), wanted).seen;

        String lyrics = actions == null ? "no presenter" : action == null ? "the player bound no renderer"
                : "panels=" + lyricsTargets + "/" + available.size() + (panels == null ? " no controller" : "");
        String related = "queue=" + prepared.title().path() + " title=" + prepared.title().presence()
                + (prepared.title().command() == null ? "" : command == null ? " command-unreadable"
                : " kind=" + ReconnectSurfacesLogic.describeCommand(prepared.title().command()));
        return new Published(command, action != null, lyrics, related,
                new PanelTrace(action, button, title, available, offered.unkeyed, registered, elementsMatch));
    }

    /** The ids of the response's panels, learned while filtering out all registrations. */
    private static PanelFilter panelsOf(@Nullable Controller panels, Object reply) {
        return panels == null ? new PanelFilter(null) : replayPanels(panels, reply, null);
    }

    private static PanelFilter replayPanels(Controller panels, Object reply, @Nullable Set<String> wanted) {
        PanelFilter replay = new PanelFilter(wanted);
        try {
            filter = replay;
            panels.rvxRegisterPanels(reply);
        } catch (Exception | LinkageError failure) {
            Logger.printException(() -> "ReconnectSurfaces: panel traversal failed", failure);
        } finally {
            filter = null;
        }
        return replay;
    }

    /** Hands the response to the presenter and returns only the renderer bound on this call. */
    @Nullable
    private static byte[] bindLyrics(Presenter actions, String videoId, Object reply) {
        PillCapture active = new PillCapture(actions);
        try {
            capture = active;
            actions.rvxBindLyrics(videoId, reply);
            return active.renderer;
        } catch (Exception | LinkageError failure) {
            Logger.printException(() -> "ReconnectSurfaces: could not bind the Lyrics pill", failure);
            return null;
        } finally {
            capture = null;
        }
    }

    @Nullable
    private static Object parse(OfflineWatchMetadata.Host host, @Nullable byte[] command) {
        if (command == null || !(host instanceof CommandParser parser)) return null;

        try {
            return parser.rvxParseCommand(command);
        } catch (Exception | LinkageError failure) {
            Logger.printException(() -> "ReconnectSurfaces: could not read the title command", failure);
            return null;
        }
    }

    /** Report the publication decision, not whether the user opened or rendered a panel. */
    static void log(String videoId, Published published) {
        String lyrics = published.lyricsBound() ? "renderer-bound" : "not bound";
        Logger.printDebug(() -> "ReconnectSurfaces: lyrics " + lyrics + " v=" + videoId + " " + published.lyrics());
        String related = published.command() != null ? "published" : "not published";
        Logger.printDebug(() -> "ReconnectSurfaces: related " + related + " v=" + videoId + " " + published.related());
        Logger.printDebug(() -> "ReconnectSurfaces: panel-shape " + published.trace().describe());
    }

    /** Stock resolver only. Observe the actual key lookup without registering or changing its result. */
    public static void panelLookup(Object instance, @Nullable String key, @Nullable Object panel, boolean nested) {
        if (controller.get() != instance || !BaseSettings.DEBUG.get()) return;

        Logger.printDebug(() -> "ReconnectSurfaces: native-panel-lookup path="
                + (nested ? "nested-or-key" : "direct")
                + " key=" + keyToken(key) + " present=" + (panel != null));
    }

    private static String keyTokens(Set<String> keys) {
        List<String> tokens = new ArrayList<>(Math.min(9, keys.size()));
        for (String key : keys) {
            if (tokens.size() == 8) {
                tokens.add("…");
                break;
            }
            tokens.add(keyToken(key));
        }
        return tokens.toString();
    }

    /** Only a process-local fingerprint of a panel key, never raw key strings or any response payload. */
    private static String keyToken(@Nullable String key) {
        if (key == null || key.isEmpty()) return "none";

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(KeyTrace.SALT);
            byte[] hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            int prefix = ((hash[0] & 0xff) << 24) | ((hash[1] & 0xff) << 16)
                    | ((hash[2] & 0xff) << 8) | (hash[3] & 0xff);
            return Integer.toUnsignedString(prefix, 16);
        } catch (NoSuchAlgorithmException impossible) {
            return "redacted";
        }
    }
}
