package org.endlesssource.mediainterface.windows;

import org.endlesssource.mediainterface.api.MediaSession;
import org.endlesssource.mediainterface.api.MediaSessionListener;
import org.endlesssource.mediainterface.api.PlaybackState;
import org.endlesssource.mediainterface.api.SystemMediaInterface;
import org.endlesssource.mediainterface.api.SystemMediaOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class WindowsSystemMediaInterface implements SystemMediaInterface {
    private static final Logger logger = LoggerFactory.getLogger(WindowsSystemMediaInterface.class);

    /** Consecutive absent polls before a session is removed — rides out transient WinRT gaps. */
    private static final int MISS_THRESHOLD_BEFORE_REMOVE = 3;

    private final SystemMediaOptions options;
    private final Map<String, WindowsMediaSession> sessions = new ConcurrentHashMap<>();
    /** Single poll thread only. */
    private final SessionReconciler reconciler = new SessionReconciler(MISS_THRESHOLD_BEFORE_REMOVE);
    private final List<MediaSessionListener> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService executor;
    private volatile boolean closed;

    public WindowsSystemMediaInterface(SystemMediaOptions options) {
        this.options = options;
        WinRtBridge.load();
        WinRtBridge.nativeInit(options.isEventDrivenEnabled());
        this.executor = options.isEventDrivenEnabled() ? Executors.newScheduledThreadPool(2, r -> { Thread t = new Thread(r, "mediainterface-sessions"); t.setDaemon(true); return t; }) : null;
        logger.debug("Initializing Windows media interface (eventDriven={})", options.isEventDrivenEnabled());
        discoverSessions();
        if (options.isEventDrivenEnabled()) {
            long intervalMs = options.getSessionPollInterval().toMillis();
            executor.scheduleWithFixedDelay(this::updateSessions, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public Optional<MediaSession> getActiveSession() {
        List<WindowsMediaSession> snapshot = new ArrayList<>(sessions.values());
        Optional<MediaSession> playing = snapshot.stream()
                .filter(session -> session.getControls().getPlaybackState() == PlaybackState.PLAYING)
                .findFirst()
                .map(session -> (MediaSession) session);
        if (playing.isPresent()) {
            return playing;
        }
        return snapshot.stream()
                .findFirst()
                .map(session -> (MediaSession) session);
    }

    @Override
    public List<MediaSession> getAllSessions() {
        return new ArrayList<>(sessions.values());
    }

    @Override
    public Optional<MediaSession> getSessionByApp(String appName) {
        String query = appName == null ? "" : appName.toLowerCase();
        return sessions.values().stream()
                .filter(session -> session.getApplicationName().toLowerCase().contains(query))
                .findFirst()
                .map(session -> (MediaSession) session);
    }

    @Override
    public boolean hasActiveSessions() {
        return !sessions.isEmpty();
    }

    @Override
    public void addSessionListener(MediaSessionListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeSessionListener(MediaSessionListener listener) {
        listeners.remove(listener);
    }

    @Override
    public boolean isEventDrivenEnabled() {
        return options.isEventDrivenEnabled();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (executor != null) {
            executor.shutdownNow();
        }
        sessions.values().forEach(WindowsMediaSession::close);
        sessions.clear();
        reconciler.clear();
        listeners.clear();
        WinRtBridge.nativeShutdown();
        logger.debug("Windows media interface closed");
    }

    private void discoverSessions() {
        String[] ids = WinRtBridge.nativeGetSessionIds();
        if (ids == null) {
            logger.debug("No sessions returned during initial discovery");
            return;
        }
        logger.debug("Discovered {} Windows sessions during initialization", ids.length);
        for (String id : ids) {
            addSession(id);
        }
    }

    private void updateSessions() {
        if (closed) {
            return;
        }
        String[] ids = WinRtBridge.nativeGetSessionIds();
        SessionReconciler.Decision decision = reconciler.reconcile(sessions.keySet(), ids);
        for (String id : decision.toAdd()) {
            addSession(id);
        }
        for (String id : decision.toRemove()) {
            removeSession(id);
        }
    }

    private void addSession(String id) {
        if (id == null || id.trim().isEmpty() || sessions.containsKey(id)) {
            return;
        }
        WindowsMediaSession session = new WindowsMediaSession(
                id,
                options.isEventDrivenEnabled(),
                options.getSessionUpdateInterval(),
                options.isPositionUpdatesEnabled(),
                options.getArtworkMaxSize()
        );
        sessions.put(id, session);
        logger.debug("Added Windows media session {}", id);
        listeners.forEach(listener -> listener.onSessionAdded(session));
    }

    private void removeSession(String id) {
        reconciler.forget(id);
        WindowsMediaSession removed = sessions.remove(id);
        if (removed != null) {
            removed.close();
            logger.debug("Removed Windows media session {}", id);
            listeners.forEach(listener -> listener.onSessionRemoved(id));
        }
    }
}
