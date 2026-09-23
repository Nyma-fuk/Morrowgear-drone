package jp.morrowgear.drone;

import static jp.morrowgear.drone.OperationEvent.Kind.*;
import static jp.morrowgear.drone.OperationTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OperationEventObserverTest {
    @Test void joiningWithActiveStatesProducesNoHistoricalEvents() {
        var observer = new OperationEventObserver(OWNER);
        var active = sample("route", FOLLOW_STARTED, POWER_LOST, LASER_STARTED);
        assertTrue(observer.observe(List.of(active), 0, 0).isEmpty());
        assertTrue(observer.observe(List.of(active), 100, 0).isEmpty());
    }

    @Test void hundredUnitsProduceOneWingReportAfterOneSecond() {
        var observer = new OperationEventObserver(OWNER);
        List<OperationObservation> idle = new ArrayList<>(), working = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            UUID unit = new UUID(1, i);
            idle.add(sample(OWNER, unit, "idle", "WING-2", null, false, Set.of()));
            working.add(sample(OWNER, unit, "route", "WING-2", null, false, Set.of(FOLLOW_STARTED)));
        }
        observer.observe(idle, 0, 0);
        assertTrue(observer.observe(working, 5, 250).isEmpty());
        assertTrue(observer.observe(working, 24, 1200).isEmpty());
        var events = observer.observe(working, 25, 1250);
        assertEquals(1, events.size());
        assertEquals(100, events.getFirst().count());
        assertEquals(100, observer.trackedCount());
        assertEquals(100, observer.authorize(events.getFirst().id(), working, 25).orElseThrow().count());
    }

    @Test void ownersAreSeparatedEvenWithMatchingWingAndMission() {
        var observer = new OperationEventObserver(OWNER);
        observer.observe(List.of(sample("idle")), 0, 0);
        var mine = sample("run", WORK_STARTED);
        var theirs = sample(OTHER, new UUID(1, 1), "run", "WING-2", TARGET, true, Set.of(WORK_STARTED));
        observer.observe(List.of(mine, theirs), 5, 0);
        var event = observer.observe(List.of(mine, theirs), 25, 0).getFirst();
        assertEquals(OWNER, event.owner());
        assertEquals(1, event.count());
        assertTrue(observer.authorize(event.id(), List.of(theirs), 30).isEmpty());
        assertTrue(new OperationEventObserver(OTHER).authorize(event.id(), List.of(theirs), 30).isEmpty());
    }

    @Test void pendingPlaybackChecksFreshMissionEvenBeforeNextPollingTick() {
        var observer = new OperationEventObserver(OWNER);
        OperationEvent event = emit(observer, sample("A", WORK_STARTED));
        assertTrue(observer.authorize(event.id(), List.of(sample("B", WORK_STARTED)), 26).isEmpty());
    }

    @Test void observedMissionGenerationRejectsAtoBtoA() {
        var observer = new OperationEventObserver(OWNER);
        OperationEvent event = emit(observer, sample("A", WORK_STARTED));
        observer.observe(List.of(sample("B", WORK_STARTED)), 30, 0);
        observer.observe(List.of(sample("A", WORK_STARTED)), 35, 0);
        assertTrue(observer.authorize(event.id(), List.of(sample("A", WORK_STARTED)), 35).isEmpty());
    }

    @Test void targetDeathRemovalAndReplacementInvalidateIntercept() {
        var observer = new OperationEventObserver(OWNER);
        OperationEvent event = emit(observer, sample("A", INTERCEPTING));
        assertTrue(observer.authorize(event.id(), List.of(sample(OWNER, UNIT, "A", "WING-2", TARGET,
                false, Set.of(INTERCEPTING))), 26).isEmpty());
        assertTrue(observer.authorize(event.id(), List.of(sample(OWNER, UNIT, "A", "WING-2", new UUID(3, 3),
                true, Set.of(INTERCEPTING))), 26).isEmpty());
        assertTrue(observer.authorize(event.id(), List.of(), 26).isEmpty());
    }

    @Test void changedStateAndDimensionInvalidatePlayback() {
        var observer = new OperationEventObserver(OWNER);
        OperationEvent event = emit(observer, sample("A", WORK_STARTED));
        assertTrue(observer.authorize(event.id(), List.of(sample("A", OPERATION_COMPLETE)), 26).isEmpty());
        var now = sample("A", WORK_STARTED);
        var moved = new OperationObservation(now.owner(), now.unit(), now.missionKey(), now.wing(), "minecraft:the_nether",
                now.sourceName(), now.voiceNumber(), 1, 64, 3, TARGET, true, now.states(), false, false, false, false, true, 0);
        assertTrue(observer.authorize(event.id(), List.of(moved), 26).isEmpty());
    }

    @Test void repeatedWeaponPassesAndWorkOscillationsStayQuiet() {
        var observer = new OperationEventObserver(OWNER);
        emit(observer, sample("A", LASER_STARTED));
        observer.observe(List.of(sample("A", INTERCEPTING)), 40, 0);
        observer.observe(List.of(sample("A", LASER_STARTED)), 45, 0);
        var events = observer.observe(List.of(sample("A", LASER_STARTED)), 80, 0);
        assertTrue(events.stream().noneMatch(event -> event.kind() == LASER_STARTED));
    }

    @Test void changingTargetSeparatesBattlefronts() {
        var observer = new OperationEventObserver(OWNER);
        observer.observe(List.of(sample("idle"), sample(OWNER, new UUID(0, 11), "idle", "WING-2", TARGET, true, Set.of())), 0, 0);
        var samples = List.of(sample("A", INTERCEPTING), sample(OWNER, new UUID(0, 11), "A", "WING-2", new UUID(3, 1), true, Set.of(INTERCEPTING)));
        observer.observe(samples, 5, 0);
        var events = observer.observe(samples, 25, 0);
        assertEquals(2, events.size());
        assertNotEquals(events.get(0).semanticKey(), events.get(1).semanticKey());
    }

    @Test void reconnectionAndUnitReloadStartQuiet() {
        var observer = new OperationEventObserver(OWNER);
        emit(observer, sample("A", WORK_STARTED));
        observer.observe(List.of(), 30, 0);
        assertTrue(observer.observe(List.of(sample("A", WORK_STARTED)), 35, 0).isEmpty());
        assertTrue(observer.observe(List.of(sample("A", WORK_STARTED)), 55, 0).isEmpty());
        assertTrue(new OperationEventObserver(OWNER).observe(List.of(sample("A", WORK_STARTED)), 60, 0).isEmpty());
    }

    @Test void rejoinAttemptIsNotReportedAsMissionResume() {
        var observer = new OperationEventObserver(OWNER);
        var working = sample("A", WORK_STARTED);
        observer.observe(List.of(working), 0, 0);
        var rejoin = flags(sample("A"), true, false, false, false, false);
        observer.observe(List.of(rejoin), 5, 0);
        assertTrue(observer.observe(List.of(rejoin), 25, 0).isEmpty());
        observer.observe(List.of(working), 30, 0);
        assertEquals(MISSION_RESUMED, observer.observe(List.of(working), 50, 0).getFirst().kind());
    }

    @Test void dockingAndResourceLimitedDepartureNeverClaimServiceComplete() {
        var observer = new OperationEventObserver(OWNER);
        var docked = flags(sample("A", DOCKED), false, true, true, false, false);
        observer.observe(List.of(docked), 0, 0);
        assertTrue(observer.observe(List.of(docked), 40, 0).isEmpty());
        var limited = flags(sample("A"), false, false, false, false, true);
        observer.observe(List.of(limited), 45, 0);
        assertTrue(observer.observe(List.of(limited), 65, 0).isEmpty());
    }

    @Test void confirmedReadyServiceDepartureCanReportComplete() {
        var observer = new OperationEventObserver(OWNER);
        observer.observe(List.of(flags(sample("A", DOCKED), false, true, true, false, false)), 0, 0);
        var ready = flags(sample("A"), false, false, false, true, true);
        observer.observe(List.of(ready), 5, 0);
        assertEquals(SERVICE_COMPLETE, observer.observe(List.of(ready), 25, 0).getFirst().kind());
    }

    @Test void cargoWaitRequiresThirtySecondsAndOnlyReportsOnce() {
        var observer = new OperationEventObserver(OWNER);
        var waiting = sample("cargo", CARGO_STALLED);
        observer.observe(List.of(waiting), 0, 0);
        assertTrue(observer.observe(List.of(waiting), 599, 0).isEmpty());
        observer.observe(List.of(waiting), 600, 0);
        assertEquals(CARGO_STALLED, observer.observe(List.of(waiting), 620, 0).getFirst().kind());
        assertTrue(observer.observe(List.of(waiting), 700, 0).isEmpty());
    }

    @Test void eventExpiresFromOriginalTransitionNotDelayedGroupingTime() {
        var observer = new OperationEventObserver(OWNER);
        var current = sample("A", POWER_LOST);
        var event = emit(observer, current);
        assertTrue(observer.authorize(event.id(), List.of(current), 164).isPresent());
        assertTrue(observer.authorize(event.id(), List.of(current), 165).isEmpty());
    }

    @Test void oversizedFleetCannotCreateUnboundedObserverWork() {
        var observer = new OperationEventObserver(OWNER);
        assertThrows(IllegalArgumentException.class, () -> observer.observe(
                java.util.Collections.nCopies(513, sample("A")), 0, 0));
    }

    private static OperationEvent emit(OperationEventObserver observer, OperationObservation current) {
        observer.observe(List.of(sample("idle")), 0, 0);
        observer.observe(List.of(current), 5, 250);
        return observer.observe(List.of(current), 25, 1250).getFirst();
    }
}
