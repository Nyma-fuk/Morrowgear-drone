package jp.morrowgear.drone;

import static jp.morrowgear.drone.OperationEvent.Kind.*;
import static jp.morrowgear.drone.OperationTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class OperationRadioQueueTest {
    @Test void dangerThenInterceptionThenSelectedContextThenCompletion() {
        var queue = queue();
        queue.selection(event -> event.wing().equals("SELECTED"));
        var complete = event(OPERATION_COMPLETE, 0);
        var start = event(OWNER, WORK_STARTED, 0, "SELECTED");
        var intercept = event(INTERCEPTING, 0);
        var lost = event(POWER_LOST, 0);
        for (var event : new OperationEvent[] {complete, start, intercept, lost}) queue.offer(event, 0, 0);
        assertEquals(lost, queue.request(0, STANDARD).orElseThrow());
        queue.authorized(lost.id(), null, 0, STANDARD);
        assertEquals(intercept, queue.request(0, STANDARD).orElseThrow());
        queue.authorized(intercept.id(), null, 0, STANDARD);
        assertEquals(start, queue.request(0, STANDARD).orElseThrow());
        queue.authorized(start.id(), null, 0, STANDARD);
        assertEquals(complete, queue.request(0, STANDARD).orElseThrow());
    }

    @Test void playbackHasOneSlotAndFourSecondNormalGap() {
        var queue = queue();
        var first = event(WORK_STARTED, 0);
        queue.offer(first, 0, 0);
        queue.request(0, STANDARD);
        assertTrue(queue.authorized(first.id(), first, 0, STANDARD).isPresent());
        queue.offer(event(OPERATION_COMPLETE, 0), 0, 0);
        assertTrue(queue.request(1_000, STANDARD).isEmpty());
        queue.finished(first.id());
        assertTrue(queue.request(3_999, STANDARD).isEmpty());
        assertTrue(queue.request(4_000, STANDARD).isPresent());
    }

    @Test void dangerInterruptsNormalButDoesNotOverlapOtherDanger() {
        var queue = queue();
        var work = event(WORK_STARTED, 0);
        queue.offer(work, 0, 0); queue.request(0, STANDARD); queue.authorized(work.id(), work, 0, STANDARD);
        var lost = event(POWER_LOST, 0);
        queue.offer(lost, 500, 0);
        assertEquals(lost, queue.request(500, STANDARD).orElseThrow());
        assertTrue(queue.authorized(lost.id(), lost, 500, STANDARD).orElseThrow().interrupt());
        queue.offer(event(DANGER, 0), 600, 0);
        assertTrue(queue.request(600, STANDARD).isEmpty());
    }

    @Test void dangerDoesNotWaitBehindSlowNormalAuthorization() {
        var queue = queue();
        var work = event(WORK_STARTED, 0);
        queue.offer(work, 0, 0); queue.request(0, STANDARD);
        var lost = event(POWER_LOST, 0);
        queue.offer(lost, 500, 0);
        assertEquals(lost, queue.request(500, STANDARD).orElseThrow());
        assertTrue(queue.authorized(work.id(), work, 501, STANDARD).isEmpty());
        assertTrue(queue.authorized(lost.id(), lost, 502, STANDARD).isPresent());
    }

    @Test void repeatingPowerWarningIsSuppressedEvenAfterCommandChanges() {
        var queue = queue();
        var first = event(POWER_LOST, 0);
        queue.offer(first, 0, 0); queue.request(0, STANDARD); queue.authorized(first.id(), first, 0, STANDARD);
        queue.finished(first.id());
        var changedMission = new OperationEvent(UUID.randomUUID(), OWNER, UUID.randomUUID(), UNIT, 10, 1000,
                POWER_LOST, first.wing(), first.dimension(), first.sourceName(), first.voiceNumber(), 1, 1, 64, 3);
        queue.offer(changedMission, 5_000, 0);
        assertEquals(0, queue.size());
    }

    @Test void authorizingEntryCountsTowardEightItemBound() {
        var queue = queue();
        queue.offer(event(WORK_STARTED, 0), 0, 0); queue.request(0, STANDARD);
        for (int i = 0; i < 100; i++) queue.offer(event(OWNER, OPERATION_COMPLETE, 0, "WING-" + i), 0, 0);
        assertEquals(8, queue.size());
        queue.offer(event(POWER_LOST, 0), 0, 0);
        assertEquals(8, queue.size());
    }

    @Test void duplicateMeaningIsSuppressedForFifteenSeconds() {
        var queue = queue();
        var first = event(POWER_LOST, 0);
        queue.offer(first, 0, 0); queue.request(0, STANDARD); queue.authorized(first.id(), first, 0, STANDARD);
        queue.finished(first.id());
        queue.offer(event(POWER_LOST, 0), 14_999, 0);
        assertEquals(0, queue.size());
        queue.offer(event(POWER_LOST, 0), 15_000, 0);
        assertEquals(1, queue.size());
    }

    @Test void expiryIncludesAgeBeforeClientReceptionAndAuthorizationLatency() {
        var queue = queue();
        var event = event(WORK_STARTED, 0);
        queue.offer(event, 1000, 7_000);
        queue.request(1000, STANDARD);
        assertTrue(queue.authorized(event.id(), event, 2000, STANDARD).isEmpty());
        queue.offer(event(WORK_STARTED, 0), 3000, 8000);
        assertEquals(0, queue.size());
    }

    @Test void hundredReportsHaveBoundedQueueAndCriticalEvictsCompletion() {
        var queue = queue();
        for (int i = 0; i < 100; i++) queue.offer(event(OWNER, OPERATION_COMPLETE, 0, "WING-" + i), 0, 0);
        assertEquals(8, queue.size());
        var lost = event(POWER_LOST, 0);
        queue.offer(lost, 0, 0);
        assertEquals(8, queue.size());
        assertEquals(lost, queue.request(0, STANDARD).orElseThrow());
    }

    @Test void otherOwnerAndMismatchedAuthorizationAreRejected() {
        var queue = queue();
        queue.offer(event(OTHER, POWER_LOST, 0, "WING-2"), 0, 0);
        assertEquals(0, queue.size());
        var work = event(WORK_STARTED, 0);
        queue.offer(work, 0, 0); queue.request(0, STANDARD);
        assertTrue(queue.authorized(UUID.randomUUID(), work, 0, STANDARD).isEmpty());
        assertTrue(queue.authorized(work.id(), event(OTHER, WORK_STARTED, 0, "WING-2"), 0, STANDARD).isEmpty());
    }

    @Test void reconnectDropsQueueActiveAuthorizationAndDedupState() {
        var queue = queue();
        var work = event(WORK_STARTED, 0);
        queue.offer(work, 0, 0); queue.request(0, STANDARD);
        queue.connect(OWNER);
        assertEquals(0, queue.size());
        assertTrue(queue.authorized(work.id(), work, 0, STANDARD).isEmpty());
        assertTrue(queue.active(0).isEmpty());
    }

    @Test void voiceOffStillAllowsSubtitlesAndImportantDefaultDoesNotSpeakRoutineReports() {
        var queue = queue();
        var work = event(WORK_STARTED, 0);
        var off = new OperationRadioSettings(OperationRadioSettings.VoiceMode.OFF, 1, true);
        queue.offer(work, 0, 0); queue.request(0, off);
        var playback = queue.authorized(work.id(), work, 0, off).orElseThrow();
        assertFalse(playback.voice()); assertTrue(playback.subtitle());
        assertFalse(OperationRadioSettings.DEFAULT.audible(work));
        assertTrue(OperationRadioSettings.DEFAULT.audible(event(POWER_LOST, 0)));
    }

    @Test void completelyMutedQueueNeverRequestsAuthorization() {
        var queue = queue();
        queue.offer(event(POWER_LOST, 0), 0, 0);
        assertTrue(queue.request(0, new OperationRadioSettings(OperationRadioSettings.VoiceMode.OFF, 1, false)).isEmpty());
    }

    private static OperationRadioQueue queue() { var result = new OperationRadioQueue(); result.connect(OWNER); return result; }
}
