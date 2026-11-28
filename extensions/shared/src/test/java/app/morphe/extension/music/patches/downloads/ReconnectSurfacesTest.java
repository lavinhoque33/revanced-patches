/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27.
 * Written by lavinhoque33, 2026-11-26.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import static app.morphe.extension.music.patches.downloads.ReconnectSurfacesLogicTest.button;
import static app.morphe.extension.music.patches.downloads.ReconnectSurfacesLogicTest.directPanel;

import androidx.annotation.Nullable;
import com.google.common.util.concurrent.ListenableFuture;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class ReconnectSurfacesTest {
    private static final String VIDEO = "abcdefghijk";

    private final List<String> events = new ArrayList<>();
    private final List<String> registered = new ArrayList<>();

    /** The player's controller: it walks the panels of a response and asks the hook before each one. */
    private final class StubController implements ReconnectSurfaces.Controller {
        @Override public void rvxRegisterPanels(Object reply) {
            events.add("panels");
            for (String id : List.of("panel-lyrics", "panel-about", "panel-related")) {
                if (ReconnectSurfaces.allowsPanel(id)) registered.add(id);
            }
        }
    }

    private static byte[] action(String id) {
        return button(directPanel(id));
    }

    /** The action as the player's own binder hands it to its pill binder. */
    private static final class StubAction implements ReconnectSurfaces.Message {
        final byte[] bytes;
        int reads;

        StubAction(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override public byte[] rvxMessageBytes() {
            reads++;
            return bytes;
        }
    }

    /** The player's presenter: its direct binder binds the pill, which hands the action to the pill binder hook. */
    private final class StubPresenter implements ReconnectSurfaces.Presenter {
        @Nullable StubAction action;

        @Override public void rvxBindLyrics(String videoId, Object reply) {
            events.add("bind " + videoId);
            if (action != null) ReconnectSurfaces.pillAction(this, action);
        }
    }

    // The surfaces keep the player's objects weakly, so the test holds the ones it hands over.
    private final StubController controller = new StubController();
    private final StubPresenter presenter = new StubPresenter();
    private final ReconnectSurfaces.Controller failing = reply -> {
        throw new IllegalStateException("the player failed");
    };

    private static final class StubHost implements OfflineWatchMetadata.Host, ReconnectSurfaces.CommandParser {
        final Object parsed = new Object();
        byte[] seen;

        @Override public ListenableFuture<?> rvxFetchWatchMetadata(byte[] command) {
            throw new AssertionError("the surfaces never fetch");
        }

        @Override public OfflineWatchMetadata.Reply rvxDecodeWatchMetadata(Object response) {
            throw new AssertionError("the surfaces never decode");
        }

        @Override public Object rvxParseCommand(byte[] command) {
            seen = command;
            return parsed;
        }
    }

    private static ReconnectSurfaces.Prepared prepared(byte[] titleCommand) {
        var presence = titleCommand == null ? ReconnectSurfacesLogic.TitlePresence.NO_COMMAND
                : ReconnectSurfacesLogic.TitlePresence.PRESENT;
        var related = new ReconnectSurfacesLogic.Related("tabbed", presence, titleCommand);
        return new ReconnectSurfaces.Prepared(new Object(), related);
    }

    private ReconnectSurfaces.Published publish(StubHost host, byte[] titleCommand) {
        return ReconnectSurfaces.publish(host, VIDEO, prepared(titleCommand), presenter);
    }

    @Test public void thePillIsBoundAfterTheListingAndOnlyItsPanelIsRegistered() {
        presenter.action = new StubAction(action("panel-lyrics"));
        ReconnectSurfaces.controller(controller);

        ReconnectSurfaces.Published published = publish(new StubHost(), null);

        assertEquals(List.of("panels", "bind " + VIDEO, "panels"), events);
        assertEquals(List.of("panel-lyrics"), registered);
        assertTrue(published.lyricsBound());
        assertNull(published.command());
        assertEquals(1, presenter.action.reads);
    }

    @Test public void thePanelOfTheTitleCommandIsRegisteredToo() {
        presenter.action = new StubAction(action("panel-lyrics"));
        ReconnectSurfaces.controller(controller);
        StubHost host = new StubHost();
        byte[] title = directPanel("panel-related");

        ReconnectSurfaces.Published published = publish(host, title);

        assertEquals(List.of("panel-lyrics", "panel-related"), registered);
        assertSame(host.parsed, published.command());
        assertEquals("panels=1/3", published.lyrics());
    }

    @Test public void aPlayerThatBindsNoActionRegistersNoPanelAndReportsNoPill() {
        ReconnectSurfaces.controller(controller);

        ReconnectSurfaces.Published published = publish(new StubHost(), null);

        assertEquals(List.of(), registered);
        assertFalse(published.lyricsBound());
    }

    @Test public void anUnknownRequestedKeyDoesNotRegisterAnyOtherPanel() {
        presenter.action = new StubAction(action("panel-unserved"));
        ReconnectSurfaces.controller(controller);

        ReconnectSurfaces.Published published = publish(new StubHost(), null);

        assertEquals(List.of(), registered);
        assertTrue(published.lyricsBound());
        assertEquals("panels=0/3", published.lyrics());
    }

    @Test public void actionFromAnotherPresenterIsNotCapturedByTheScopedBind() {
        StubAction action = new StubAction(action("panel-lyrics"));
        ReconnectSurfaces.Presenter owner = (videoId, reply) -> ReconnectSurfaces.pillAction(presenter, action);
        ReconnectSurfaces.controller(controller);

        var published = ReconnectSurfaces.publish(new StubHost(), VIDEO, prepared(null), owner);

        assertEquals(0, action.reads);
        assertEquals(List.of(), registered);
        assertFalse(published.lyricsBound());
    }

    @Test public void anActionIsOnlyReadWhileAPublishBindsThePill() {
        StubAction action = new StubAction(action("panel-lyrics"));

        ReconnectSurfaces.pillAction(presenter, action);
        ReconnectSurfaces.pillAction(presenter, new Object());

        assertEquals(0, action.reads);
    }

    @Test public void everyPanelRegistersWhenNoPublishIsReplayingAResponse() {
        assertTrue(ReconnectSurfaces.allowsPanel("anything"));
        assertTrue(ReconnectSurfaces.allowsPanel(""));
    }

    @Test public void theFilterIsGoneAfterAPublishEvenWhenThePlayerThrows() {
        ReconnectSurfaces.controller(failing);

        ReconnectSurfaces.Published published = publish(new StubHost(), null);

        assertFalse(published.lyricsBound());
        assertTrue(ReconnectSurfaces.allowsPanel("panel-lyrics"));
    }

    @Test public void reopeningThePlayerBindsTheSavedResponseOnceOnlyForTheExactReplyAndEpoch() {
        Object local = new Object();
        var binding = new ReconnectSurfaces.PlayerBinding(7, prepared(directPanel("panel-related")));
        var host = new StubHost();
        ReconnectSurfaces.controller(controller);

        var hidden = ReconnectSurfaces.publish(host, VIDEO, binding.prepared, presenter);
        assertFalse(hidden.lyricsBound());
        assertSame(host.parsed, hidden.command());
        assertFalse(binding.needsLyrics(presenter, 7, local, new Object()));
        assertFalse(binding.needsLyrics(presenter, 8, local, local));
        assertTrue(binding.needsLyrics(presenter, 7, local, local));

        presenter.action = new StubAction(action("panel-lyrics"));
        var opened = ReconnectSurfaces.publish(host, VIDEO, binding.prepared, presenter);
        assertTrue(opened.lyricsBound());
        binding.lyricsBound(presenter);
        assertFalse(binding.needsLyrics(presenter, 7, local, local));
        assertTrue(binding.needsLyrics(new StubPresenter(), 7, local, local));
        assertEquals(1, presenter.action.reads);
        assertTrue(registered.contains("panel-lyrics"));
    }

    @Test public void aReplyThatIsNoSourceHasNothingToPrepare() {
        assertNull(ReconnectSurfaces.prepare(new Object(), VIDEO));
    }

    @Test public void aSourcePreparesItsOwnReplyAndNoCommandWhenItsResponseHasNone() {
        Object reply = new SourceReply(new byte[]{(byte) 0xff});

        ReconnectSurfaces.Prepared prepared = ReconnectSurfaces.prepare(reply, VIDEO);

        assertSame(reply, prepared.reply());
        assertNull(prepared.title().command());
    }

    private record SourceReply(byte[] bytes) implements ReconnectSurfaces.Source {
        @Override public byte[] rvxMetadataBytes() {
            return bytes;
        }
    }

    @Test public void theTitleThatAskedLastIsTheOneRefreshed() {
        int[] refreshed = {0};
        ReconnectSurfaces.Title title = () -> refreshed[0]++;

        ReconnectSurfaces.related(title);
        ReconnectSurfaces.refreshTitle();

        assertEquals(1, refreshed[0]);
    }
}
