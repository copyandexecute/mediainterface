package org.endlesssource.mediainterface.windows;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionReconcilerTest {

    private static Set<String> tracked(String... ids) {
        return new LinkedHashSet<>(Arrays.asList(ids));
    }

    private static Set<String> set(String... ids) {
        return new HashSet<>(Arrays.asList(ids));
    }

    @Test
    void constructor_rejectsThresholdBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> new SessionReconciler(0));
        assertThrows(IllegalArgumentException.class, () -> new SessionReconciler(-1));
    }

    @Test
    void nullPoll_keepsEverything_andDoesNotAdvanceMisses() {
        SessionReconciler r = new SessionReconciler(3);
        for (int i = 0; i < 10; i++) {
            SessionReconciler.Decision d = r.reconcile(tracked("spotify"), null);
            assertTrue(d.toAdd().isEmpty());
            assertTrue(d.toRemove().isEmpty());
        }
    }

    @Test
    void emptyPoll_advancesMisses_andRemovesOnlyAtThreshold() {
        SessionReconciler r = new SessionReconciler(3);
        assertTrue(r.reconcile(tracked("spotify"), new String[] {}).toRemove().isEmpty());
        assertTrue(r.reconcile(tracked("spotify"), new String[] {}).toRemove().isEmpty());
        assertEquals(set("spotify"), r.reconcile(tracked("spotify"), new String[] {}).toRemove());
    }

    @Test
    void newId_isAdded() {
        SessionReconciler r = new SessionReconciler(3);
        SessionReconciler.Decision d = r.reconcile(tracked(), new String[] {"spotify"});
        assertEquals(set("spotify"), d.toAdd());
        assertTrue(d.toRemove().isEmpty());
    }

    @Test
    void stableId_producesNoChange() {
        SessionReconciler r = new SessionReconciler(3);
        SessionReconciler.Decision d = r.reconcile(tracked("spotify"), new String[] {"spotify"});
        assertTrue(d.toAdd().isEmpty());
        assertTrue(d.toRemove().isEmpty());
    }

    @Test
    void alreadyTrackedAndPolled_isNotReAdded() {
        SessionReconciler r = new SessionReconciler(3);
        SessionReconciler.Decision d = r.reconcile(tracked("a", "b"), new String[] {"a", "b"});
        assertTrue(d.toAdd().isEmpty());
        assertTrue(d.toRemove().isEmpty());
    }

    @Test
    void reappearBeforeThreshold_resetsMissStreak() {
        SessionReconciler r = new SessionReconciler(3);
        r.reconcile(tracked("spotify"), new String[] {});
        r.reconcile(tracked("spotify"), new String[] {});
        r.reconcile(tracked("spotify"), new String[] {"spotify"});
        assertTrue(r.reconcile(tracked("spotify"), new String[] {}).toRemove().isEmpty());
    }

    @Test
    void sustainedFlapping_neverRemoves() {
        SessionReconciler r = new SessionReconciler(3);
        Set<String> tracked = tracked("spotify");
        for (int i = 0; i < 50; i++) {
            String[] poll = (i % 2 == 0) ? new String[] {"spotify"} : new String[] {};
            assertTrue(r.reconcile(tracked, poll).toRemove().isEmpty());
        }
    }

    @Test
    void missCountersAreIndependentPerSession() {
        SessionReconciler r = new SessionReconciler(2);
        r.reconcile(tracked("a", "b"), new String[] {"b"});
        SessionReconciler.Decision d = r.reconcile(tracked("a", "b"), new String[] {"b"});
        assertEquals(set("a"), d.toRemove());
        assertTrue(d.toAdd().isEmpty());
    }

    @Test
    void addAndRemoveCanHappenInSamePoll() {
        SessionReconciler r = new SessionReconciler(1);
        SessionReconciler.Decision d = r.reconcile(tracked("old"), new String[] {"new"});
        assertEquals(set("new"), d.toAdd());
        assertEquals(set("old"), d.toRemove());
    }

    @Test
    void blankAndNullIdsAreIgnored() {
        SessionReconciler r = new SessionReconciler(3);
        SessionReconciler.Decision d = r.reconcile(tracked(), new String[] {null, "", "   ", "spotify"});
        assertEquals(set("spotify"), d.toAdd());
    }

    @Test
    void blankPolledIds_doNotKeepAbsentSessionAlive() {
        SessionReconciler r = new SessionReconciler(1);
        SessionReconciler.Decision d = r.reconcile(tracked("spotify"), new String[] {"", null});
        assertEquals(set("spotify"), d.toRemove());
    }

    @Test
    void forget_clearsMissStreakSoCounterRestarts() {
        SessionReconciler r = new SessionReconciler(2);
        r.reconcile(tracked("spotify"), new String[] {});
        r.forget("spotify");
        assertTrue(r.reconcile(tracked("spotify"), new String[] {}).toRemove().isEmpty());
    }

    @Test
    void clear_resetsAllStreaks() {
        SessionReconciler r = new SessionReconciler(2);
        r.reconcile(tracked("a"), new String[] {});
        r.clear();
        assertTrue(r.reconcile(tracked("a"), new String[] {}).toRemove().isEmpty());
    }

    @Test
    void thresholdOne_removesOnFirstAbsence() {
        SessionReconciler r = new SessionReconciler(1);
        assertEquals(set("spotify"), r.reconcile(tracked("spotify"), new String[] {}).toRemove());
    }
}
