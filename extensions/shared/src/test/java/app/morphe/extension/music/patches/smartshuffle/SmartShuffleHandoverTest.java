/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-27.
 */
package app.morphe.extension.music.patches.smartshuffle;

import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

import android.net.Network;
import android.net.NetworkCapabilities;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import app.morphe.extension.shared.utils.Utils;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class SmartShuffleHandoverTest {
    @Test
    public void replacementKeepsPendingSuggestionsUntilTheCurrentDefaultIsLost() {
        Network wifi = ReflectionHelpers.callConstructor(Network.class, from(int.class, 71));
        Network mobile = ReflectionHelpers.callConstructor(Network.class, from(int.class, 72));
        var observer = new SmartShuffleManager.NetworkObserver(wifi);
        Class<SmartShuffleManager> manager = SmartShuffleManager.class;
        var pending = new SmartShuffleStart("PLsource", 40);
        ReflectionHelpers.setStaticField(manager, "online", true);
        ReflectionHelpers.setStaticField(manager, "revision", 40L);
        ReflectionHelpers.setStaticField(manager, "pending", pending);

        // The replacement's capability event will follow. Neither availability alone nor the superseded
        // Wi-Fi callback is proof that the active session went offline.
        observer.onAvailable(mobile);
        observer.onCapabilitiesChanged(wifi, null);
        observer.onLost(wifi);
        assertSame(pending, ReflectionHelpers.getStaticField(manager, "pending"));

        observer.onLost(mobile);
        assertNull(ReflectionHelpers.getStaticField(manager, "pending"));
    }

    @Test
    public void reconnectKeepsSuggestionWhoseOfflineRemovalHasNotRun() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        var jobs = new ArrayDeque<Runnable>();
        var song = new Row(9, "song");
        var suggestion = new Row(10, "suggestion");
        var rows = new ArrayList<>(List.of(song, suggestion));
        var access = (SmartShuffleNative.Access) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{SmartShuffleNative.Access.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "patch_jamExecutor" -> (Executor) jobs::add;
                    case "patch_jamItems" -> rows.toArray();
                    case "patch_jamVideoId" -> ((Row) args[0]).videoId();
                    case "smartPlaylistId" -> "PLsource";
                    case "patch_jamCurrent" -> 0;
                    case "patch_jamLocal" -> true;
                    case "patch_jamRemove" -> rows.remove((int) args[0]);
                    default -> throw new AssertionError(method);
                });
        ReflectionHelpers.setStaticField(SmartShuffleNative.class, "owner", new WeakReference<>(access));
        var manager = SmartShuffleManager.class;
        var policy = new SmartShufflePolicy(Set.of(song.videoId()));
        policy.committed(song.smartOccurrenceId(),
                new SmartShuffleNative.Item(suggestion.smartOccurrenceId(), suggestion.videoId()), null);
        SmartShuffleNative.snapshot(snapshot -> ReflectionHelpers.setStaticField(manager, "policyQueue",
                snapshot.generation()));
        jobs.remove().run();
        ReflectionHelpers.setStaticField(manager, "policy", policy);
        ReflectionHelpers.setStaticField(manager, "online", true);

        Network wifi = ReflectionHelpers.callConstructor(Network.class, from(int.class, 71));
        var observer = new SmartShuffleManager.NetworkObserver(wifi);
        observer.onLost(wifi);
        jobs.remove().run(); // Snapshot taken offline; the removal is now posted to the native executor.
        observer.onAvailable(wifi);
        NetworkCapabilities validated = ReflectionHelpers.callConstructor(NetworkCapabilities.class);
        ReflectionHelpers.callInstanceMethod(validated, "addCapability", from(int.class, NET_CAPABILITY_INTERNET));
        ReflectionHelpers.callInstanceMethod(validated, "addCapability", from(int.class, NET_CAPABILITY_VALIDATED));
        observer.onCapabilitiesChanged(wifi, validated);

        while (!jobs.isEmpty()) jobs.remove().run();
        assertEquals(List.of(song, suggestion), rows);

        observer.onLost(wifi);
        while (!jobs.isEmpty()) jobs.remove().run();
        assertEquals(List.of(song), rows);
    }

    private record Row(long smartOccurrenceId, String videoId) implements SmartShuffleNative.Occurrence {}
}
