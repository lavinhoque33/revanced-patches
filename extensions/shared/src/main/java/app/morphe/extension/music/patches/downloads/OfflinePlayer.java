/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.utils.StringRef.str;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.audiofx.AudioEffect;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.KeyEvent;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/**
 * Offline audio player with audio focus, MediaSession and lock-screen controls.
 * <p>
 * modified by lavinhoque33, 2026-10-14: this used to be a manifest-declared {@code Service}, which
 * patched root installs cannot register (only {@code base.apk} is mounted, so the package manager
 * keeps the stock manifest and refuses to start it). The player now lives in the process and only
 * borrows YouTube Music's own mediaPlayback foreground service for the foreground notification,
 * see {@link OfflineServiceHooks}. Everything here runs on the main thread.
 */
public final class OfflinePlayer {
    public interface PlaybackListener {
        void onPlaybackChanged(String path, String title, boolean playing, int position, int duration);
    }

    private static final Set<PlaybackListener> listeners = new CopyOnWriteArraySet<>();
    private static volatile String currentPath = "";
    private static volatile String currentTitle = "";
    private static volatile boolean currentPlaying;
    private static volatile int currentPosition;
    private static volatile int currentDuration;
    private static OfflinePlayer instance;

    public static void addListener(PlaybackListener listener) {
        listeners.add(listener);
        listener.onPlaybackChanged(currentPath, currentTitle, currentPlaying, currentPosition, currentDuration);
    }

    public static void removeListener(PlaybackListener listener) { listeners.remove(listener); }

    private static OfflinePlayer get() {
        if (instance == null) instance = new OfflinePlayer(Utils.getContext());
        return instance;
    }

    /** Starts the track, and the borrowed foreground service that keeps playback alive. */
    public static void play(String path, String title, String artist, String artworkPath,
                            ArrayList<String> queue, int queueIndex) {
        Intent intent = new Intent(ACTION_PLAY_FILE)
                .putExtra(EXTRA_PATH, path)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_ARTIST, artist)
                .putExtra(EXTRA_ARTWORK_PATH, artworkPath)
                .putStringArrayListExtra(EXTRA_QUEUE, queue)
                .putExtra(EXTRA_QUEUE_INDEX, queueIndex);
        OfflineServiceHooks.startPlaybackService(intent);
    }

    public static void toggle() { get().handleToggle(); }

    public static void skipNext() { get().playQueueOffset(1); }

    public static void skipPrevious() { get().playQueueOffset(-1); }

    public static void seekTo(int position) {
        OfflinePlayer player = instance;
        if (player != null && player.playerReady()) {
            player.player.seekTo(Math.max(0, Math.min(position, player.player.getDuration())));
            player.publishState();
        }
    }

    /** @return Whether there is a session whose foreground notification must stay up. */
    static boolean isSessionActive() {
        OfflinePlayer player = instance;
        return player != null && player.session != null;
    }

    /** The notification of the current session. Not null whenever [isSessionActive]. */
    static Notification buildNotification() {
        return get().notification();
    }

    /**
     * Runs a command that arrived through the borrowed service. Notification buttons use the
     * toggle, next, previous and stop actions; the first play action comes from the library.
     */
    static void handleCommand(Intent intent) {
        OfflinePlayer player = get();
        String action = intent.getAction();
        if (ACTION_PLAY_FILE.equals(action)) {
            ArrayList<String> suppliedQueue = intent.getStringArrayListExtra(EXTRA_QUEUE);
            if (suppliedQueue != null) {
                player.queue = suppliedQueue;
                player.queueIndex = intent.getIntExtra(EXTRA_QUEUE_INDEX, -1);
            }
            player.playFile(intent.getStringExtra(EXTRA_PATH), intent.getStringExtra(EXTRA_TITLE),
                    intent.getStringExtra(EXTRA_ARTIST), intent.getStringExtra(EXTRA_ARTWORK_PATH));
        } else if (ACTION_TOGGLE.equals(action)) {
            player.handleToggle();
        } else if (ACTION_STOP.equals(action)) {
            player.stopPlayback();
        } else if (ACTION_NEXT.equals(action)) {
            player.playQueueOffset(1);
        } else if (ACTION_PREVIOUS.equals(action)) {
            player.playQueueOffset(-1);
        }
    }

    static final String ACTION_PREFIX = "app.morphe.music.offline.";
    static final String ACTION_START = ACTION_PREFIX + "START";
    static final String ACTION_PLAY_FILE = ACTION_PREFIX + "PLAY_FILE";
    static final String ACTION_TOGGLE = ACTION_PREFIX + "TOGGLE";
    static final String ACTION_STOP = ACTION_PREFIX + "STOP";
    static final String ACTION_NEXT = ACTION_PREFIX + "NEXT_TRACK";
    static final String ACTION_PREVIOUS = ACTION_PREFIX + "PREVIOUS_TRACK";
    private static final String EXTRA_PATH = "path";
    private static final String EXTRA_QUEUE = "queue";
    private static final String EXTRA_QUEUE_INDEX = "queue_index";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_ARTIST = "artist";
    private static final String EXTRA_ARTWORK_PATH = "artwork_path";

    private static final String CHANNEL_ID = "morphe_offline_playback";
    private static final int NOTIFICATION_ID = 8841;

    /** The progress ticker runs twice a second, so this is about every five seconds. */
    private static final int ENSURE_NOTIFICATION_TICKS = 10;

    private final Context context;
    private MediaPlayer player;
    private boolean prepared;
    private MediaSession session;
    private final AudioManager audioManager;
    private final AudioFocusRequest focusRequest;
    private int audioEffectSessionId;
    private String path = "";
    private String publishedMetadataPath = "";
    private String title = "YouTube Music";
    private String artist = "YouTube Music";
    private Bitmap artwork;
    private ArrayList<String> queue = new ArrayList<>();
    private int queueIndex = -1;
    private int ticks;
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressTicker = new Runnable() {
        @Override public void run() {
            publishState();
            if (++ticks % ENSURE_NOTIFICATION_TICKS == 0) OfflineServiceHooks.ensurePlaybackNotification();
            if (player != null) progressHandler.postDelayed(this, 500);
        }
    };

    private OfflinePlayer(Context context) {
        this.context = context;
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        focusRequest = createFocusRequest();
        createChannel();
    }

    /** Created with the first track and released by [stopPlayback], like the service used to. */
    private MediaSession ensureSession() {
        if (session != null) return session;

        session = new MediaSession(context, "MorpheOfflinePlayback");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { playQueueOffset(1); }
            @Override public void onSkipToPrevious() { playQueueOffset(-1); }
            @Override public void onStop() { stopPlayback(); }
            @Override public void onSeekTo(long pos) {
                if (playerReady()) player.seekTo((int) Math.min(Integer.MAX_VALUE, pos));
                publishState();
            }
        });
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launch != null) session.setSessionActivity(PendingIntent.getActivity(context, 3, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        session.setActive(true);
        return session;
    }

    private void handleToggle() {
        if (playerReady() && player.isPlaying()) pause(); else resume();
    }

    private void playFile(@Nullable String path, @Nullable String requestedTitle,
                          @Nullable String requestedArtist, @Nullable String artworkPath) {
        ensureSession();
        if (path == null || !new File(path).isFile()) {
            stopPlayback();
            return;
        }
        this.path = path;
        title = requestedTitle == null ? new File(path).getName() : requestedTitle;
        artist = requestedArtist == null || requestedArtist.isBlank() ? "YouTube Music" : requestedArtist;
        // A track saved without a cover has no file, and decoding it would log an error a second.
        File artworkFile = artworkPath == null ? null : new File(artworkPath);
        artwork = artworkFile != null && artworkFile.isFile()
                ? BitmapFactory.decodeFile(artworkPath)
                : null;
        releasePlayer();
        try {
            pauseOtherMedia();
            if (audioFocusDenied()) {
                stopPlayback();
                return;
            }
            player = new MediaPlayer();
            player.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK);
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            player.setDataSource(path);
            // Skipping quickly leaves the previous player mid prepare. Its callbacks are
            // cleared on release, and each one still checks that it belongs to the current
            // track, so a discarded player can never tear down the one that replaced it.
            player.setOnPreparedListener(mp -> {
                if (mp != player) return;
                prepared = true;
                openAudioEffectSession(mp);
                mp.start();
                publishState();
                OfflineServiceHooks.updatePlaybackNotification();
                progressHandler.removeCallbacks(progressTicker);
                progressHandler.post(progressTicker);
                // Music's own player pauses shortly after losing audio focus, and may take the
                // notification of a shared service instance with it.
                progressHandler.postDelayed(OfflineServiceHooks::ensurePlaybackNotification, 1_000);
            });
            player.setOnCompletionListener(mp -> {
                if (mp != player) return;
                if (queueIndex >= 0 && queueIndex + 1 < queue.size()) playQueueOffset(1);
                else stopPlayback();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                if (mp != player) return true;
                Logger.printException(() -> "Offline player error: " + what + "/" + extra);
                stopPlayback();
                return true;
            });
            player.prepareAsync();
            OfflineServiceHooks.updatePlaybackNotification();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not start offline playback", ex);
            stopPlayback();
        }
    }

    private void playQueueOffset(int offset) {
        int target = queueIndex + offset;
        if (target < 0 || target >= queue.size()) return;
        queueIndex = target;
        OfflineTrack track = OfflineTrack.load(new File(queue.get(target)));
        playFile(track.audioFile().getAbsolutePath(), track.displayTitle(), track.displayArtist(),
                track.artworkFile().getAbsolutePath());
    }

    private void resume() {
        if (!playerReady()) return;
        if (audioFocusDenied()) return;
        player.start();
        publishState();
        OfflineServiceHooks.updatePlaybackNotification();
        progressHandler.postDelayed(OfflineServiceHooks::ensurePlaybackNotification, 1_000);
    }

    private void pause() {
        if (!playerReady() || !player.isPlaying()) return;
        player.pause();
        publishState();
        OfflineServiceHooks.updatePlaybackNotification();
    }

    private void stopPlayback() {
        stopPlayback(false);
    }

    // modified by lavinhoque33, 2026-10-14
    /**
     * @param focusLost The session ends because another player took audio focus for good. The
     *                  shared service then keeps the foreground notification of that player.
     */
    private void stopPlayback(boolean focusLost) {
        releasePlayer();
        progressHandler.removeCallbacks(progressTicker);
        if (audioManager != null && focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
        publishedMetadataPath = "";
        path = "";
        artwork = null;
        if (session != null) {
            session.setPlaybackState(new PlaybackState.Builder().setState(
                    PlaybackState.STATE_STOPPED, 0, 0).build());
            session.setActive(false);
            session.release();
            session = null;
        }
        // The service that held the session is stopped along with it, so the library bar must
        // not keep showing the last track.
        currentPath = "";
        currentTitle = "";
        currentPlaying = false;
        currentPosition = 0;
        currentDuration = 0;
        for (PlaybackListener listener : listeners) {
            listener.onPlaybackChanged(currentPath, currentTitle, false, 0, 0);
        }
        OfflineServiceHooks.stopPlaybackForeground(focusLost);
    }

    /**
     * Asking a player that is still preparing about its state is an invalid operation, which it
     * answers with an error that stops playback, so nothing may reach it before it is ready.
     */
    private boolean playerReady() {
        return prepared && player != null;
    }

    private void openAudioEffectSession(MediaPlayer mediaPlayer) {
        int sessionId = mediaPlayer.getAudioSessionId();
        if (sessionId <= 0) return;

        audioEffectSessionId = sessionId;
        context.sendBroadcast(new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName())
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC));
    }

    private void closeAudioEffectSession() {
        int sessionId = audioEffectSessionId;
        if (sessionId <= 0) return;

        audioEffectSessionId = 0;
        context.sendBroadcast(new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName()));
    }

    private void releasePlayer() {
        prepared = false;
        progressHandler.removeCallbacks(progressTicker);
        closeAudioEffectSession();

        MediaPlayer released = player;
        if (released == null) return;
        // The active reference is cleared before the released instance is reset, so a late event
        // can never see it again, but release() runs after rather than inside synchronized blocks
        // that would stop whatever is playing by then.
        player = null;
        released.setOnPreparedListener(null);
        released.setOnCompletionListener(null);
        released.setOnErrorListener(null);
        released.reset();
        released.release();
    }

    @Nullable
    private AudioFocusRequest createFocusRequest() {
        if (audioManager == null) return null;
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        return new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(change -> {
                    if (change == AudioManager.AUDIOFOCUS_LOSS) {
                        // Music (or another app) started its own playback. Ending the session,
                        // instead of staying paused, leaves the shared service and its
                        // notification to that player.
                        stopPlayback(true);
                    } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                        pause();
                    }
                })
                .build();
    }

    /**
     * @return Whether the system refused audio focus, so playback must not start.
     */
    private boolean audioFocusDenied() {
        return audioManager == null || focusRequest == null
                || audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    /** Pause the currently routed system player before this session becomes active. */
    private void pauseOtherMedia() {
        if (audioManager == null || !audioManager.isMusicActive()) return;
        KeyEvent down = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE);
        KeyEvent up = new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE);
        audioManager.dispatchMediaKeyEvent(down);
        audioManager.dispatchMediaKeyEvent(up);
    }

    private void publishState() {
        MediaSession currentSession = session;
        if (currentSession == null) return;

        final boolean playing = playerReady() && player.isPlaying();
        final long position = playerReady() ? player.getCurrentPosition() : 0;
        currentSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE |
                        PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP |
                        PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_SKIP_TO_NEXT |
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        position, playing ? 1f : 0f)
                .build());
        currentPath = path == null ? "" : path;
        currentTitle = title;
        currentPlaying = playing;
        currentPosition = (int) position;
        currentDuration = playerReady() ? player.getDuration() : 0;
        for (PlaybackListener listener : listeners) {
            listener.onPlaybackChanged(currentPath, currentTitle, currentPlaying, currentPosition, currentDuration);
        }
        // Rebuilding the metadata copies the artwork, so only do it when the track changed.
        if (!currentPath.equals(publishedMetadataPath)) {
            publishedMetadataPath = currentPath;
            MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, currentDuration);
            if (artwork != null) {
                metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
                        .putBitmap(MediaMetadata.METADATA_KEY_ART, artwork)
                        .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, artwork);
            }
            currentSession.setMetadata(metadata.build());
        }
    }

    @SuppressLint("WrongConstant")
    private Notification notification() {
        final boolean playing = playerReady() && player.isPlaying();
        PendingIntent toggle = serviceIntent(ACTION_TOGGLE, 1);
        PendingIntent stop = serviceIntent(ACTION_STOP, 2);
        PendingIntent previous = serviceIntent(ACTION_PREVIOUS, 4);
        PendingIntent next = serviceIntent(ACTION_NEXT, 5);
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        PendingIntent content = PendingIntent.getActivity(context, 3, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.MediaStyle style = new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2);
        if (session != null) style.setMediaSession(session.getSessionToken());
        return new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_headset)
                .setContentTitle(title)
                .setContentText(artist)
                .setLargeIcon(artwork)
                .setContentIntent(content)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(true)
                .addAction(action(android.R.drawable.ic_media_previous,
                        str("morphe_music_downloads_previous"), previous))
                .addAction(action(
                        playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        str(playing ? "morphe_music_downloads_pause" : "morphe_music_downloads_play"),
                        toggle))
                .addAction(action(android.R.drawable.ic_media_next,
                        str("morphe_music_downloads_next"), next))
                .addAction(action(android.R.drawable.ic_menu_close_clear_cancel,
                        str("morphe_music_downloads_close"), stop))
                .setStyle(style)
                .build();
    }

    private Notification.Action action(int iconResource, String title, PendingIntent intent) {
        return new Notification.Action.Builder(
                Icon.createWithResource(context, iconResource), title, intent).build();
    }

    /** The buttons start the borrowed service, which hands the action back to this player. */
    private PendingIntent serviceIntent(String action, int code) {
        Intent intent = OfflineServiceHooks.serviceIntent(context, action);
        //noinspection WrongConstant
        return PendingIntent.getService(context, code, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void createChannel() {
        //noinspection WrongConstant
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                str("morphe_music_downloads_playback_channel"), NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                .createNotificationChannel(channel);
    }

    static int notificationId() {
        return NOTIFICATION_ID;
    }
}
