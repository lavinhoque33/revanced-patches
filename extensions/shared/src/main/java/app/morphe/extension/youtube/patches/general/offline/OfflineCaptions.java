/*
 * Copyright (C) 2026 lavinhoque33
 *
 * This file is part of the revanced-patches project:
 * https://github.com/anddea/revanced-patches
 *
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-10-31.
 */

package app.morphe.extension.youtube.patches.general.offline;

import static app.morphe.extension.shared.utils.StringRef.str;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.CaptionText;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.CaptionTrackList;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;
import app.morphe.extension.youtube.settings.Settings;

/** Caption URLs are ephemeral; only downloaded cues and public file references enter the private index. */
final class OfflineCaptions {
    private static final String PLAYER_URL = "https://youtubei.googleapis.com/youtubei/v1/player?alt=proto";
    private static final String USER_AGENT = "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip";
    private static final String FOLDER = "RVX/Subtitles";

    record Track(String key, String language, String kind, String translation, String name, String url) {
    }

    record Selection(List<Track> tracks, String format) {
        static Selection none() {
            return new Selection(List.of(), "srt");
        }
    }

    static final class Catalog {
        final List<Track> tracks;
        final Set<String> selected = new LinkedHashSet<>();
        boolean include;
        String format;

        Catalog(List<Track> tracks) {
            this.tracks = tracks;
            include = Settings.OFFLINE_SUBTITLES_INCLUDE.get();
            format = "vtt".equals(Settings.OFFLINE_SUBTITLES_FORMAT.get()) ? "vtt" : "srt";
            String[] remembered = Settings.OFFLINE_SUBTITLES_LANGUAGES.get().split(",");
            for (String key : remembered) {
                for (Track track : tracks) {
                    if (track.key.equals(key)) OfflineSubtitle.select(selected, key, true);
                }
            }
            if (selected.isEmpty() && !tracks.isEmpty()) {
                Track preferred = tracks.get(0);
                for (Track track : tracks) {
                    if (track.translation.isEmpty() && (track.language.equals("en")
                            || track.language.startsWith("en-"))) {
                        preferred = track;
                        break;
                    }
                }
                selected.add(preferred.key);
            }
        }

        Selection selection() {
            if (!include) return Selection.none();
            List<Track> result = new ArrayList<>();
            // Selection order is retained, so the default English track is also the player's first track.
            for (String key : selected) {
                for (Track track : tracks) {
                    if (key.equals(track.key)) result.add(track);
                }
            }
            return new Selection(List.copyOf(result), format);
        }

        void remember() {
            Settings.OFFLINE_SUBTITLES_INCLUDE.save(include);
            Settings.OFFLINE_SUBTITLES_FORMAT.save(format);
            Settings.OFFLINE_SUBTITLES_LANGUAGES.save(String.join(",", selected));
        }

        Track currentTrack(Track selected) {
            for (Track track : tracks) {
                if (track.key.equals(selected.key)) return track;
            }
            return selected;
        }
    }

    record Saved(String language, String kind, String format, String name, String uri,
                 List<OfflineSubtitle.Cue> cues) {
        JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("language", language);
            json.put("kind", kind);
            json.put("format", format);
            json.put("name", name);
            json.put("uri", uri);
            JSONArray copy = new JSONArray();
            for (OfflineSubtitle.Cue cue : cues) {
                copy.put(new JSONArray().put(cue.startMs()).put(cue.endMs()).put(cue.text()));
            }
            json.put("cues", copy);
            return json;
        }

        static Saved fromJson(JSONObject json) {
            List<OfflineSubtitle.Cue> cues = new ArrayList<>();
            JSONArray array = json.optJSONArray("cues");
            long previousEnd = 0;
            if (array != null) {
                for (int index = 0; index < array.length(); index++) {
                    JSONArray cue = array.optJSONArray(index);
                    if (cue == null) continue;
                    long start = cue.optLong(0, -1);
                    long end = cue.optLong(1, -1);
                    String text = cue.optString(2, "");
                    if (start >= previousEnd && end > start && !text.isEmpty()) {
                        cues.add(new OfflineSubtitle.Cue(start, end, text));
                        previousEnd = end;
                    }
                }
            }
            return new Saved(json.optString("language"), json.optString("kind"), json.optString("format"),
                    json.optString("name"), json.optString("uri"), List.copyOf(cues));
        }
    }

    private OfflineCaptions() {
    }

    static Catalog resolve(String videoId, PlayerResponse response, boolean fetchMissing) {
        CaptionTrackList list = response.getCaptions().getPlayerCaptionsTracklistRenderer();
        if (fetchMissing && list.getCaptionTracksCount() == 0) {
            try {
                list = fetchPlayer(videoId).getCaptions().getPlayerCaptionsTracklistRenderer();
            } catch (Exception ex) {
                Logger.printInfo(() -> "Offline captions unavailable for " + videoId, ex);
            }
        }
        return new Catalog(tracks(list));
    }

    private static PlayerResponse fetchPlayer(String videoId) throws IOException {
        HttpURLConnection connection = Requester.openConnection(PLAYER_URL);
        try {
            configure(connection);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("X-YouTube-Client-Name", "3");
            connection.setRequestProperty("X-YouTube-Client-Version", "20.10.38");
            connection.setDoOutput(true);
            String body = "{\"context\":{\"client\":{\"clientName\":\"ANDROID\","
                    + "\"clientVersion\":\"20.10.38\"}},\"videoId\":\"" + videoId
                    + "\",\"contentCheckOk\":true,\"racyCheckOk\":true}";
            try (var output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("Caption player HTTP " + status);
            try (InputStream input = connection.getInputStream()) {
                return PlayerResponse.parseFrom(input);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static List<Track> tracks(CaptionTrackList list) {
        List<Track> result = new ArrayList<>();
        for (var track : list.getCaptionTracksList()) {
            if (track.getBaseUrl().isEmpty() || track.getLanguageCode().isEmpty()) continue;
            String key = track.getVssId().isEmpty()
                    ? track.getLanguageCode() + ":" + track.getKind() : track.getVssId();
            result.add(new Track(key, track.getLanguageCode(), track.getKind(), "",
                    name(track.getName(), track.getLanguageCode()), track.getBaseUrl()));
        }
        if (result.isEmpty()) return result;
        for (var language : list.getTranslationLanguagesList()) {
            if (language.getLanguageCode().isEmpty()) continue;
            int sourceIndex = -1;
            if (language.getTranslationSourceTrackIndicesCount() > 0) {
                sourceIndex = language.getTranslationSourceTrackIndices(0);
            } else {
                for (int index = 0; index < list.getCaptionTracksCount(); index++) {
                    if (list.getCaptionTracks(index).getIsTranslatable()) {
                        sourceIndex = index;
                        break;
                    }
                }
            }
            if (sourceIndex < 0 || sourceIndex >= list.getCaptionTracksCount()) continue;
            var source = list.getCaptionTracks(sourceIndex);
            if (!source.getIsTranslatable() || source.getBaseUrl().isEmpty()
                    || source.getLanguageCode().equals(language.getLanguageCode())) continue;
            result.add(new Track("translate:" + language.getLanguageCode(), source.getLanguageCode(), "translated",
                    language.getLanguageCode(), name(language.getLanguageName(), language.getLanguageCode()),
                    source.getBaseUrl()));
        }
        return result;
    }

    private static String name(CaptionText text, String fallback) {
        StringBuilder name = new StringBuilder();
        for (var run : text.getRunsList()) name.append(run.getText());
        return name.length() == 0 ? fallback : name.toString();
    }

    static void download(Context context, OfflineVideo video, Selection selection, Catalog refreshed) {
        boolean failed = false;
        boolean limited = false;
        for (Track selected : selection.tracks) {
            Track track = refreshed.currentTrack(selected);
            OfflineMediaStore.Pending pending = null;
            try {
                String json = fetchTrack(track);
                List<OfflineSubtitle.Cue> cues = OfflineSubtitle.parseJson3(json, "asr".equals(track.kind));
                if (cues.isEmpty()) throw new IOException("Empty caption track");
                String displayName = OfflineSubtitle.fileName(video.title, video.videoId, track.language,
                        track.kind, track.translation, selection.format);
                displayName = uniqueName(context, displayName);
                pending = OfflineMediaStore.create(context, OfflineMediaStore.Collection.SUBTITLE, FOLDER, displayName);
                try (var output = new android.os.ParcelFileDescriptor.AutoCloseOutputStream(
                        android.os.ParcelFileDescriptor.dup(pending.fileDescriptor()))) {
                    output.write(OfflineSubtitle.write(cues, selection.format).getBytes(StandardCharsets.UTF_8));
                }
                String uri = pending.commit().toString();
                pending = null;
                String language = track.translation.isEmpty() ? track.language : track.translation;
                video.subtitles.add(new Saved(language, track.kind, selection.format, track.name, uri, cues));
            } catch (RateLimitException ex) {
                limited = true;
                break;
            } catch (Exception ex) {
                failed = true;
                Logger.printInfo(() -> "Could not save offline subtitles: " + track.name, ex);
            } finally {
                if (pending != null) pending.abort();
            }
        }
        if (limited || failed) {
            Utils.showToastLong(str(limited ? "revanced_offline_subtitles_limited"
                    : "revanced_offline_subtitles_failed"));
        }
    }

    private static String uniqueName(Context context, String name) {
        String candidate = name;
        int dot = name.lastIndexOf('.');
        int number = 2;
        while (OfflineMediaStore.find(context, OfflineMediaStore.Collection.SUBTITLE, FOLDER, candidate) != null) {
            candidate = name.substring(0, dot) + " (" + number++ + ")" + name.substring(dot);
        }
        return candidate;
    }

    private static String fetchTrack(Track track) throws IOException {
        String url = track.url.replaceAll("&fmt=[^&]*", "") + "&fmt=json3";
        if (!track.translation.isEmpty()) url += "&tlang=" + Uri.encode(track.translation);
        HttpURLConnection connection = Requester.openConnection(url);
        try {
            configure(connection);
            int status = connection.getResponseCode();
            if (status == 429) throw new RateLimitException();
            if (status != 200) throw new IOException("Caption HTTP " + status);
            return Requester.parseString(connection);
        } finally {
            connection.disconnect();
        }
    }

    private static void configure(HttpURLConnection connection) {
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
    }

    static void delete(Context context, OfflineVideo video) {
        for (Saved subtitle : video.subtitles) {
            try {
                Uri uri = Uri.parse(subtitle.uri);
                if ("file".equals(uri.getScheme())) {
                    String path = uri.getPath();
                    if (path != null) new File(path).delete();
                } else if ("content".equals(uri.getScheme())) {
                    context.getContentResolver().delete(uri, null, null);
                }
            } catch (Exception ex) {
                Logger.printInfo(() -> "Could not delete offline subtitle " + subtitle.uri, ex);
                Utils.showToastShort(str("revanced_offline_subtitles_delete_failed"));
            }
        }
    }

    private static final class RateLimitException extends IOException {
    }
}
