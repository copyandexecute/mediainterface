package org.endlesssource.mediainterface.api;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration options for {@link SystemMediaInterface} implementations.
 */
public final class SystemMediaOptions {
    public static final Duration DEFAULT_SESSION_POLL_INTERVAL = Duration.ofSeconds(1);
    public static final Duration DEFAULT_SESSION_UPDATE_INTERVAL = Duration.ofMillis(200);
    /**
     * Default longest edge (px) artwork is downscaled to before it leaves the
     * native layer. Album art is only ever shown small, so shrinking it here
     * cuts the base64 payload + decode cost dramatically. {@code 0} = no
     * downscale (return the source thumbnail untouched). Windows-only for now.
     */
    public static final int DEFAULT_ARTWORK_MAX_SIZE = 128;

    private final boolean eventDrivenEnabled;
    private final Duration sessionPollInterval;
    private final Duration sessionUpdateInterval;
    private final boolean positionUpdatesEnabled;
    private final int artworkMaxSize;

    private SystemMediaOptions(boolean eventDrivenEnabled,
                               Duration sessionPollInterval,
                               Duration sessionUpdateInterval,
                               boolean positionUpdatesEnabled,
                               int artworkMaxSize) {
        this.eventDrivenEnabled = eventDrivenEnabled;
        this.sessionPollInterval = requirePositive("sessionPollInterval", sessionPollInterval);
        this.sessionUpdateInterval = requirePositive("sessionUpdateInterval", sessionUpdateInterval);
        this.positionUpdatesEnabled = positionUpdatesEnabled;
        this.artworkMaxSize = Math.max(0, artworkMaxSize);
    }

    public static SystemMediaOptions defaults() {
        return new SystemMediaOptions(true, DEFAULT_SESSION_POLL_INTERVAL, DEFAULT_SESSION_UPDATE_INTERVAL,
                true, DEFAULT_ARTWORK_MAX_SIZE);
    }

    public boolean isEventDrivenEnabled() {
        return eventDrivenEnabled;
    }

    public Duration getSessionPollInterval() {
        return sessionPollInterval;
    }

    public Duration getSessionUpdateInterval() {
        return sessionUpdateInterval;
    }

    public boolean isPositionUpdatesEnabled() {
        return positionUpdatesEnabled;
    }

    /** Longest artwork edge (px) emitted by the native layer; {@code 0} = no downscale. */
    public int getArtworkMaxSize() {
        return artworkMaxSize;
    }

    public SystemMediaOptions withEventDrivenEnabled(boolean enabled) {
        return new SystemMediaOptions(enabled, sessionPollInterval, sessionUpdateInterval, positionUpdatesEnabled, artworkMaxSize);
    }

    public SystemMediaOptions withSessionPollInterval(Duration interval) {
        return new SystemMediaOptions(eventDrivenEnabled, interval, sessionUpdateInterval, positionUpdatesEnabled, artworkMaxSize);
    }

    public SystemMediaOptions withSessionUpdateInterval(Duration interval) {
        return new SystemMediaOptions(eventDrivenEnabled, sessionPollInterval, interval, positionUpdatesEnabled, artworkMaxSize);
    }

    public SystemMediaOptions withPositionUpdatesEnabled(boolean enabled) {
        return new SystemMediaOptions(eventDrivenEnabled, sessionPollInterval, sessionUpdateInterval, enabled, artworkMaxSize);
    }

    /**
     * @param maxSize longest artwork edge in px the native layer should
     *   downscale to (aspect ratio preserved). {@code 0} disables downscaling.
     */
    public SystemMediaOptions withArtworkMaxSize(int maxSize) {
        return new SystemMediaOptions(eventDrivenEnabled, sessionPollInterval, sessionUpdateInterval, positionUpdatesEnabled, maxSize);
    }

    private static Duration requirePositive(String name, Duration value) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
