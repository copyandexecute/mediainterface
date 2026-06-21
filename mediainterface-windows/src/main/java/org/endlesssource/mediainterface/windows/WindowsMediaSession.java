package org.endlesssource.mediainterface.windows;

import org.endlesssource.mediainterface.api.MediaSession;
import org.endlesssource.mediainterface.api.MediaSessionListener;
import org.endlesssource.mediainterface.api.MediaTransportControls;
import org.endlesssource.mediainterface.api.NowPlaying;
import org.endlesssource.mediainterface.api.PlaybackState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class WindowsMediaSession implements MediaSession {
    private static final Logger logger = LoggerFactory.getLogger(WindowsMediaSession.class);
    private static final long POSITION_EVENT_TICK_MS = 200L;

    private final String sessionId;
    private final boolean eventDrivenEnabled;
    private final boolean positionUpdatesEnabled;
    private final int artworkMaxSize;
    private final long updateIntervalMs;
    private final WindowsMediaTransportControls controls;
    private final List<MediaSessionListener> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService executor;
    private volatile boolean closed;
    private volatile Optional<NowPlaying> cachedNowPlaying = Optional.empty();
    private volatile boolean cachedActive;
    private volatile String cachedAppName;

    private volatile PlaybackState lastPlaybackState = PlaybackState.UNKNOWN;
    /** Last title/artist/album we fetched artwork for; null = no track. */
    private volatile String lastArtworkIdentity;
    /** Base64 artwork for {@link #lastArtworkIdentity}, reused across polls. */
    private volatile String cachedArtwork;
    private volatile Snapshot lastSnapshot;
    private volatile Boolean lastActive;
    private volatile double lastPlaybackRate = 1.0d;
    private volatile long lastSnapshotMonotonicNanos = System.nanoTime();

    WindowsMediaSession(String sessionId, boolean eventDrivenEnabled, Duration updateInterval,
                        boolean positionUpdatesEnabled, int artworkMaxSize) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.eventDrivenEnabled = eventDrivenEnabled;
        this.positionUpdatesEnabled = positionUpdatesEnabled;
        this.artworkMaxSize = artworkMaxSize;
        this.updateIntervalMs = Objects.requireNonNull(updateInterval, "updateInterval").toMillis();
        this.controls = new WindowsMediaTransportControls(sessionId);
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "mediainterface-session"); t.setDaemon(true); return t; });
        this.cachedAppName = sessionId;
        // Warm cache immediately so first reads/listener registration see current state.
        checkForChanges();
        executor.scheduleWithFixedDelay(this::checkForChanges, updateIntervalMs, updateIntervalMs, TimeUnit.MILLISECONDS);
        if (eventDrivenEnabled) {
            executor.scheduleWithFixedDelay(this::emitProjectedPositionChanges,
                    POSITION_EVENT_TICK_MS, POSITION_EVENT_TICK_MS, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public Optional<NowPlaying> getNowPlaying() {
        return cachedNowPlaying;
    }

    private Optional<NowPlaying> queryNowPlayingFromNative() {
        String[] payload = WinRtBridge.nativeGetNowPlaying(sessionId);
        if (payload == null || payload.length == 0) {
            return Optional.empty();
        }
        // nativeGetNowPlaying no longer returns artwork (payload[3] is empty) so
        // we don't re-decode + base64 the thumbnail 5x/sec. Fetch it only when the
        // track identity changes, then reuse the cached value on subsequent polls.
        String identity = trackIdentity(payload);
        if (!Objects.equals(identity, lastArtworkIdentity)) {
            lastArtworkIdentity = identity;
            cachedArtwork = identity == null ? null : WinRtBridge.nativeGetArtwork(sessionId, artworkMaxSize);
        }
        if (payload.length > 3) {
            payload[3] = cachedArtwork;
        }
        if (!positionUpdatesEnabled && payload.length > 5) {
            payload[5] = null;
        }
        Snapshot snapshot = Snapshot.fromPayload(payload);
        if (snapshot.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(snapshot.toNowPlaying());
    }

    /** title/artist/album joined by a separator, or null when no track is present. */
    private static String trackIdentity(String[] payload) {
        String title = payload.length > 0 ? payload[0] : null;
        String artist = payload.length > 1 ? payload[1] : null;
        String album = payload.length > 2 ? payload[2] : null;
        if (isBlank(title) && isBlank(artist) && isBlank(album)) {
            return null;
        }
        return title + "\u0001" + artist + "\u0001" + album;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @Override
    public MediaTransportControls getControls() {
        return controls;
    }

    @Override
    public String getApplicationName() {
        return cachedAppName;
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }

    @Override
    public boolean isActive() {
        return cachedActive;
    }

    @Override
    public void addListener(MediaSessionListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(MediaSessionListener listener) {
        listeners.remove(listener);
    }

    void close() {
        closed = true;
        listeners.clear();
        executor.shutdownNow();
    }

    private void checkForChanges() {
        if (closed) {
            return;
        }
        try {
            PlaybackState currentState = controls.refreshPlaybackState();
            controls.refreshCapabilities();

            // appName == SourceAppUserModelId == sessionId (set in the constructor), so the
            // native fetch returned the same value every poll — dropped. isActive is the same
            // PlaybackStatus we just read in refreshPlaybackState(), so derive it instead of
            // paying a second native GetPlaybackInfo() round-trip per poll.
            boolean active = currentState == PlaybackState.PLAYING || currentState == PlaybackState.PAUSED;
            cachedActive = active;

            Optional<NowPlaying> currentNowPlaying = queryNowPlayingFromNative();
            cachedNowPlaying = currentNowPlaying;
            Snapshot snapshot = currentNowPlaying.map(Snapshot::fromNowPlaying).orElseGet(() -> Snapshot.fromPayload(null));
            lastPlaybackRate = snapshot.playbackRate();
            lastSnapshotMonotonicNanos = System.nanoTime();

            if (eventDrivenEnabled) {
                if (currentState != lastPlaybackState) {
                    lastPlaybackState = currentState;
                    listeners.forEach(listener -> listener.onPlaybackStateChanged(this, currentState));
                }

                if (lastActive == null || active != lastActive) {
                    lastActive = active;
                    listeners.forEach(listener -> listener.onSessionActiveChanged(this, active));
                }

                boolean includePositionChanges = currentState == PlaybackState.PLAYING;
                if (!snapshot.sameMedia(lastSnapshot, includePositionChanges)) {
                    lastSnapshot = snapshot;
                    listeners.forEach(listener -> listener.onNowPlayingChanged(this, currentNowPlaying));
                }
            }
        } catch (Exception e) {
            logger.debug("Error checking session changes for {}: {}", sessionId, e.getMessage());
        }
    }

    private void emitProjectedPositionChanges() {
        if (closed || !eventDrivenEnabled || !positionUpdatesEnabled || lastPlaybackState != PlaybackState.PLAYING) {
            return;
        }
        Snapshot base = lastSnapshot;
        if (base == null || !base.positionMs().isPresent()) {
            return;
        }

        long nowNanos = System.nanoTime();
        Snapshot projected = base.projectedTo(nowNanos, lastSnapshotMonotonicNanos, lastPlaybackRate);
        if (projected.sameMedia(base, true)) {
            return;
        }

        lastSnapshot = projected;
        lastSnapshotMonotonicNanos = nowNanos;
        cachedNowPlaying = Optional.of(projected.toNowPlaying());
        listeners.forEach(listener -> listener.onNowPlayingChanged(this, cachedNowPlaying));
    }

    private static final class Snapshot {
        private final Optional<String> title;
        private final Optional<String> artist;
        private final Optional<String> album;
        private final Optional<String> artwork;
        private final Optional<Long> durationMs;
        private final Optional<Long> positionMs;
        private final boolean live;
        private final String metadataPairs;

        Snapshot(Optional<String> title,
                 Optional<String> artist,
                 Optional<String> album,
                 Optional<String> artwork,
                 Optional<Long> durationMs,
                 Optional<Long> positionMs,
                 boolean live,
                 String metadataPairs) {
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.artwork = artwork;
            this.durationMs = durationMs;
            this.positionMs = positionMs;
            this.live = live;
            this.metadataPairs = metadataPairs;
        }

        Optional<String> title() { return title; }
        Optional<String> artist() { return artist; }
        Optional<String> album() { return album; }
        Optional<String> artwork() { return artwork; }
        Optional<Long> durationMs() { return durationMs; }
        Optional<Long> positionMs() { return positionMs; }
        boolean live() { return live; }
        String metadataPairs() { return metadataPairs; }

        static Snapshot fromPayload(String[] payload) {
            if (payload == null || payload.length == 0) {
                return new Snapshot(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), false, "");
            }
            return new Snapshot(
                    optional(payload, 0),
                    optional(payload, 1),
                    optional(payload, 2),
                    optional(payload, 3),
                    parseLong(payload, 4),
                    parseLong(payload, 5),
                    optional(payload, 6).map(Boolean::parseBoolean).orElse(false),
                    optional(payload, 7).orElse("")
            );
        }

        static Snapshot fromNowPlaying(NowPlaying nowPlaying) {
            return new Snapshot(
                    nowPlaying.getTitle(),
                    nowPlaying.getArtist(),
                    nowPlaying.getAlbum(),
                    nowPlaying.getArtwork(),
                    nowPlaying.getDuration().map(Duration::toMillis),
                    nowPlaying.getPosition().map(Duration::toMillis),
                    nowPlaying.isLiveStream(),
                    encodeMetadata(nowPlaying.getAdditionalMetadata())
            );
        }

        boolean isEmpty() {
            return !title.isPresent() && !artist.isPresent() && !album.isPresent() && !artwork.isPresent() && !durationMs.isPresent();
        }

        boolean sameMedia(Snapshot other, boolean includePositionChanges) {
            if (other == null) {
                return false;
            }
            boolean samePosition = !includePositionChanges
                    || positionBucket(positionMs) == positionBucket(other.positionMs);
            String normalizedMetadata = normalizeMetadataForComparison(metadataPairs);
            String otherNormalizedMetadata = normalizeMetadataForComparison(other.metadataPairs);
            return title.equals(other.title)
                    && artist.equals(other.artist)
                    && album.equals(other.album)
                    && artwork.equals(other.artwork)
                    && durationMs.equals(other.durationMs)
                    && samePosition
                    && live == other.live
                    && normalizedMetadata.equals(otherNormalizedMetadata);
        }

        double playbackRate() {
            if (metadataPairs == null || metadataPairs.trim().isEmpty()) {
                return 1.0d;
            }
            for (String line : metadataPairs.split("\\R")) {
                if (line == null || line.trim().isEmpty() || !line.startsWith("playbackRate=")) {
                    continue;
                }
                String value = line.substring("playbackRate=".length()).trim();
                if (value.isEmpty() || "null".equalsIgnoreCase(value)) {
                    return 1.0d;
                }
                try {
                    return Double.parseDouble(value);
                } catch (NumberFormatException ignored) {
                    return 1.0d;
                }
            }
            return 1.0d;
        }

        Snapshot projectedTo(long nowMonotonicNanos, long anchorMonotonicNanos, double playbackRate) {
            if (!positionMs.isPresent()) {
                return this;
            }
            if (playbackRate <= 0.0d) {
                return this;
            }
            long elapsedNanos = Math.max(0L, nowMonotonicNanos - anchorMonotonicNanos);
            long deltaMs = Math.max(0L, Math.round((elapsedNanos / 1_000_000.0d) * playbackRate));
            if (deltaMs <= 0L) {
                return this;
            }

            long current = positionMs.get();
            long next = current + deltaMs;
            if (durationMs.isPresent()) {
                next = Math.min(next, durationMs.get());
            }
            if (next == current) {
                return this;
            }
            return new Snapshot(title, artist, album, artwork, durationMs, Optional.of(next), live, metadataPairs);
        }

        WindowsNowPlaying toNowPlaying() {
            String[] payload = new String[] {
                    title.orElse(null),
                    artist.orElse(null),
                    album.orElse(null),
                    artwork.orElse(null),
                    durationMs.map(String::valueOf).orElse(null),
                    positionMs.map(String::valueOf).orElse(null),
                    String.valueOf(live),
                    metadataPairs.trim().isEmpty() ? null : metadataPairs
            };
            return new WindowsNowPlaying(payload);
        }

        private static Optional<String> optional(String[] payload, int index) {
            if (payload.length <= index || payload[index] == null || payload[index].trim().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(payload[index]);
        }

        private static Optional<Long> parseLong(String[] payload, int index) {
            Optional<String> value = optional(payload, index);
            if (!value.isPresent()) {
                return Optional.empty();
            }
            try {
                return Optional.of(Long.parseLong(value.get()));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }

        private static String encodeMetadata(Map<String, String> metadata) {
            if (metadata == null || metadata.isEmpty()) {
                return "";
            }
            StringBuilder out = new StringBuilder();
            metadata.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> out.append(entry.getKey()).append("=").append(entry.getValue()).append("\n"));
            return out.toString();
        }

        private static long positionBucket(Optional<Long> positionMs) {
            return positionMs.map(ms -> ms / 1000L).orElse(-1L);
        }

        private static String normalizeMetadataForComparison(String metadata) {
            if (metadata == null || metadata.trim().isEmpty()) {
                return "";
            }
            StringBuilder out = new StringBuilder();
            for (String line : metadata.split("\\R")) {
                if (line == null || line.trim().isEmpty()) {
                    continue;
                }
                int sep = line.indexOf('=');
                if (sep <= 0) {
                    out.append(line).append('\n');
                    continue;
                }
                String key = line.substring(0, sep).trim();
                if (isVolatileMetadataKey(key)) {
                    continue;
                }
                out.append(line).append('\n');
            }
            return out.toString();
        }

        private static boolean isVolatileMetadataKey(String key) {
            return "timelineRawPositionMs".equals(key)
                    || "timelineLastUpdatedTicks".equals(key)
                    || "timelineNowTicks".equals(key)
                    || "playbackRate".equals(key)
                    || "playbackStatus".equals(key);
        }
    }

}
