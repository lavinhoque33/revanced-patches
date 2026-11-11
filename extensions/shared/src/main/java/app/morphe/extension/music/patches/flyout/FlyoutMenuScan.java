/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-10.
 */
package app.morphe.extension.music.patches.flyout;

import androidx.annotation.Nullable;

import java.util.List;
import java.util.regex.Pattern;

/**
 * What the items of a song menu name: the song, and whether an item already offers Download. Works on the serialized
 * items, with no Android dependency.
 *
 * <p>Add to queue names the song. It is an ordinary service item (66441155) or the conditional one (77258115), and
 * both keep the command in field 3. A menu without it falls back to the navigation items: Start mix (66441108, or the
 * conditional 77262503) names the video, and the credits page names it as {@code MPTC} plus the video id. The queue
 * id wins, and the song playing now is never a fallback: the menu is the one of the row that was opened.
 */
final class FlyoutMenuScan {
    private static final int[] SERVICE_ITEMS = {66441155, 77258115};
    private static final int[] NAVIGATION_ITEMS = {66441108, 77262503};
    private static final int ITEM_COMMAND = 3;
    private static final int OFFLINE_UPSELL_ENDPOINT = 133724106;
    private static final int OFFLINE_VIDEO_ENDPOINT = 73080600;
    private static final int QUEUE_ADD_ENDPOINT = 163162354;
    private static final int WATCH_ENDPOINT = 48687757;
    private static final int BROWSE_ENDPOINT = 48687626;
    private static final String CREDITS_PREFIX = "MPTC";
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    /** @param songId null when the menu is not the menu of one song; the index is of the item to add after. */
    record Result(@Nullable String songId, boolean hasDownload, int insertAfter) {
    }

    private FlyoutMenuScan() {
    }

    /** @param items The serialized menu items, null for an item that is not a message. */
    static Result scan(List<byte[]> items) {
        String queued = null;
        int queuedIndex = -1;
        boolean hasDownload = false;
        String mix = null;
        int mixIndex = -1;
        String credits = null;
        int creditsIndex = -1;

        for (int index = 0; index < items.size(); index++) {
            byte[] item = items.get(index);
            if (item == null) continue;

            for (int renderer : SERVICE_ITEMS) {
                ProtoMessage command = commandOf(item, renderer);
                if (command == null) continue;

                hasDownload |= !command.messageField(OFFLINE_UPSELL_ENDPOINT).isEmpty()
                        || !command.messageField(OFFLINE_VIDEO_ENDPOINT).isEmpty();
                String id = command.messagePath(QUEUE_ADD_ENDPOINT, 1).stringField(1);
                if (!VIDEO_ID.matcher(id).matches()) continue;

                // Two different videos in one menu: it is not the menu of one song.
                if (queued != null && !queued.equals(id)) return new Result(null, hasDownload, -1);
                queued = id;
                queuedIndex = index;
            }

            for (int renderer : NAVIGATION_ITEMS) {
                ProtoMessage command = commandOf(item, renderer);
                if (command == null) continue;

                String id = command.messageField(WATCH_ENDPOINT).stringField(1);
                if (mix == null && VIDEO_ID.matcher(id).matches()) {
                    mix = id;
                    mixIndex = index;
                }
                String browse = command.messageField(BROWSE_ENDPOINT).stringField(2);
                if (credits == null && browse.startsWith(CREDITS_PREFIX)
                        && VIDEO_ID.matcher(browse.substring(CREDITS_PREFIX.length())).matches()) {
                    credits = browse.substring(CREDITS_PREFIX.length());
                    creditsIndex = index;
                }
            }
        }

        if (queued != null) return new Result(queued, hasDownload, queuedIndex);
        if (mix != null) return new Result(mix, hasDownload, mixIndex);
        return new Result(credits, hasDownload, creditsIndex);
    }

    /** The command (field 3) of the item when it is the given renderer, else null. */
    @Nullable
    private static ProtoMessage commandOf(byte[] item, int renderer) {
        try {
            ProtoMessage wrapper = new ProtoMessage(item).messageField(renderer);
            return wrapper.isEmpty() ? null : wrapper.messageField(ITEM_COMMAND);
        } catch (RuntimeException notThisItem) {
            return null;
        }
    }
}
