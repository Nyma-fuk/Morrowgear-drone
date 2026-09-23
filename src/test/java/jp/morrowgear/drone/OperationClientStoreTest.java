package jp.morrowgear.drone;

import static jp.morrowgear.drone.OperationEvent.Kind.*;
import static jp.morrowgear.drone.OperationTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OperationClientStoreTest {
    @Test void longDisplayNamesAreBoundedWithoutSplittingSupplementaryCharacters() {
        String name = "x".repeat(95) + "\uD83D\uDE80";
        assertEquals("x".repeat(95), OperationEvent.bounded(name, 96));
        assertEquals("a b", OperationEvent.bounded("a\nb", 96));
    }

    @Test void historySnapshotNeverReplaysVoiceOrHudOnReconnect() {
        var store = new OperationClientStore();
        var historical = event(POWER_LOST, 0);
        store.snapshot(SESSION, OWNER, List.of(historical));
        assertEquals(List.of(historical), store.history());
        assertTrue(store.request(0, STANDARD).isEmpty());
        assertTrue(store.subtitle(0).isEmpty());
        var live = event(WORK_STARTED, 20);
        store.live(SESSION, OWNER, 40, List.of(live), 0);
        assertEquals(1, store.queuedCount());
        var newSession = UUID.randomUUID();
        store.snapshot(newSession, OWNER, List.of(historical, live));
        assertEquals(2, store.history().size());
        assertEquals(0, store.queuedCount());
        assertTrue(store.authorized(SESSION, live.id(), live, 1, STANDARD).isEmpty());
    }

    @Test void historySurvivesUiReopenButNotCrossServerLeakage() {
        var store = new OperationClientStore();
        var historical = event(OPERATION_COMPLETE, 0);
        store.snapshot(SESSION, OWNER, List.of(historical));
        assertEquals(store.history(), store.history());
        store.disconnect();
        assertTrue(store.history().isEmpty());
        store.snapshot(UUID.randomUUID(), OTHER, List.of(historical));
        assertTrue(store.history().isEmpty());
    }

    @Test void unauthorizedHistoryAndWrongSessionCannotEnterStore() {
        var store = new OperationClientStore();
        store.snapshot(SESSION, OWNER, List.of());
        store.live(SESSION, OTHER, 0, List.of(event(OTHER, POWER_LOST, 0, "WING-2")), 0);
        store.live(UUID.randomUUID(), OWNER, 0, List.of(event(POWER_LOST, 0)), 0);
        assertTrue(store.history().isEmpty());
    }

    @Test void expiredEventRemainsInHistoryButNeverInVoiceQueue() {
        var store = new OperationClientStore();
        store.snapshot(SESSION, OWNER, List.of());
        store.live(SESSION, OWNER, 160, List.of(event(WORK_STARTED, 0)), 0);
        assertEquals(1, store.history().size());
        assertEquals(0, store.queuedCount());
    }

    @Test void historyIsBoundedDeduplicatedAndOwnerLocal() {
        var history = new OperationHistory(OWNER);
        var first = event(WORK_STARTED, 0);
        assertTrue(history.append(first));
        assertFalse(history.append(first));
        assertFalse(history.append(event(OTHER, WORK_STARTED, 0, "WING-2")));
        for (int i = 1; i <= 200; i++) history.append(event(WORK_STARTED, i));
        assertEquals(128, history.chronological().size());
        assertEquals(200, history.newestFirst().getFirst().serverTick());
    }
}
