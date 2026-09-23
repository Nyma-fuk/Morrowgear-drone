package jp.morrowgear.drone;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CombatEffectClockTest {
	@Test void usesWorldTimeForAlreadyExistingAircraft() {
		assertEquals(2, CombatEffectClock.shotAge(200002, 200000));
		assertEquals(100, CombatEffectClock.shotAge(200100, 200000));
	}
	@Test void neverReplaysUnsetOrExpiredEvents() {
		assertEquals(Integer.MAX_VALUE, CombatEffectClock.shotAge(0, -1000));
		assertEquals(Integer.MAX_VALUE, CombatEffectClock.shotAge(4_000_000_000L, 1));
	}
	@Test void boundsSmallClockSkewAndSupportsLongRunningWorlds() {
		assertEquals(0, CombatEffectClock.shotAge(200000, 200001));
		assertEquals(1, CombatEffectClock.shotAge(4_000_000_001L, 4_000_000_000L));
	}
}
