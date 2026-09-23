package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class MissileAudioTimelineTest {
	@Test void debrisStartsAfterTheActualExplosionAndRetainsItsPosition() {
		var timeline = new MissileAudioTimeline();
		UUID id = new UUID(0, 1);
		assertTrue(timeline.detonated(id, 100, 12.5, 64, -3, 4));
		for (long tick = 100; tick < 107; tick++) assertTrue(timeline.tick(tick).isEmpty());
		var cues = timeline.tick(107);
		assertEquals(1, cues.size());
		assertEquals(new MissileAudioTimeline.Cue(12.5, 64, -3, 0.5f), cues.getFirst());
		assertTrue(timeline.tick(107).isEmpty());
		assertTrue(timeline.tick(108).isEmpty());
		assertFalse(timeline.detonated(id, 108, 200, 90, 2, 1));
		timeline.tick(180);
		assertEquals(0, timeline.size());
	}

	@Test void lateCallbacksDoNotReplayAStaleBacklog() {
		var timeline = new MissileAudioTimeline();
		timeline.detonated(new UUID(0, 1), 100, 0, 0, 0, 1);
		assertTrue(timeline.tick(111).isEmpty());
		assertTrue(timeline.tick(112).isEmpty());
		timeline.tick(201);
		assertEquals(0, timeline.size());
	}

	@Test void slightCallbackDelayIsTolerated() {
		var timeline = new MissileAudioTimeline();
		timeline.detonated(new UUID(0, 1), 100, 0, 0, 0, 1);
		assertEquals(1, timeline.tick(110).size());
	}

	@Test void separateWorldsAndBackwardClockChangesCannotLeakAudio() {
		var first = new MissileAudioTimeline();
		var second = new MissileAudioTimeline();
		first.detonated(new UUID(0, 1), 100, 0, 0, 0, 1);
		first.tick(100);
		assertTrue(second.tick(107).isEmpty());
		assertTrue(first.tick(99).isEmpty());
		assertEquals(0, first.size());
		assertTrue(first.tick(107).isEmpty());
	}

	@Test void schedulerIsBoundedAndRejectsInvalidCoordinates() {
		var timeline = new MissileAudioTimeline();
		assertFalse(timeline.detonated(new UUID(0, 0), 100, Double.NaN, 0, 0, 1));
		for (int i = 0; i < DroneAudioPolicy.MAX_MISSILE_SEQUENCES; i++)
			assertTrue(timeline.detonated(new UUID(0, i), 100, 0, 0, 0, 1));
		assertFalse(timeline.detonated(new UUID(1, 0), 100, 0, 0, 0, 1));
		assertEquals(DroneAudioPolicy.MAX_MISSILE_SEQUENCES, timeline.size());
		timeline.clear();
		assertEquals(0, timeline.size());
		assertTrue(timeline.tick(107).isEmpty());
	}
}
