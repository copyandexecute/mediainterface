package org.endlesssource.mediainterface.windows;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Decides which sessions to add/remove for one poll, with hysteresis.
 *
 * <p>{@code polledIds == null} = transient native failure → keep everything, miss
 * streaks untouched. {@code polledIds != null} = authoritative: new ids are added,
 * present ids reset their streak, absent ids are removed only after {@code missThreshold}
 * consecutive absences. This is the logic that previously churned sessions on every
 * transient WinRT enumeration gap, so it is isolated here and unit-tested.
 *
 * <p>Not thread-safe: drive from a single poll thread.
 */
final class SessionReconciler {

    static final class Decision {
        private final Set<String> toAdd;
        private final Set<String> toRemove;

        Decision(Set<String> toAdd, Set<String> toRemove) {
            this.toAdd = toAdd;
            this.toRemove = toRemove;
        }

        Set<String> toAdd() {
            return toAdd;
        }

        Set<String> toRemove() {
            return toRemove;
        }
    }

    private static final Decision NO_CHANGE =
            new Decision(Collections.<String>emptySet(), Collections.<String>emptySet());

    private final int missThreshold;
    private final Map<String, Integer> consecutiveMisses = new HashMap<>();

    SessionReconciler(int missThreshold) {
        if (missThreshold < 1) {
            throw new IllegalArgumentException("missThreshold must be >= 1, was " + missThreshold);
        }
        this.missThreshold = missThreshold;
    }

    Decision reconcile(Set<String> trackedIds, String[] polledIds) {
        if (polledIds == null) {
            return NO_CHANGE;
        }

        Set<String> present = new HashSet<>();
        Set<String> toAdd = new LinkedHashSet<>();
        for (String id : polledIds) {
            if (id == null || id.trim().isEmpty()) {
                continue;
            }
            present.add(id);
            consecutiveMisses.remove(id);
            if (!trackedIds.contains(id)) {
                toAdd.add(id);
            }
        }

        Set<String> toRemove = new LinkedHashSet<>();
        for (String id : trackedIds) {
            if (present.contains(id)) {
                continue;
            }
            if (consecutiveMisses.merge(id, 1, Integer::sum) >= missThreshold) {
                consecutiveMisses.remove(id);
                toRemove.add(id);
            }
        }
        return new Decision(toAdd, toRemove);
    }

    void forget(String id) {
        consecutiveMisses.remove(id);
    }

    void clear() {
        consecutiveMisses.clear();
    }
}
