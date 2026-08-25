package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SalvageMissionRegistryTest {
	@AfterEach
	void reset() {
		SalvageMissionRegistry.clear();
	}

	@Test
	void oneDisabledAirframeHasOneResponder() {
		UUID target = UUID.randomUUID();
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		assertTrue(SalvageMissionRegistry.reserve(target, first));
		assertFalse(SalvageMissionRegistry.reserve(target, second));
		assertTrue(SalvageMissionRegistry.heldBy(target, first));
		assertFalse(SalvageMissionRegistry.isSuspended(target));
		SalvageMissionRegistry.setSuspended(target, first, true);
		assertTrue(SalvageMissionRegistry.isSuspended(target));
		SalvageMissionRegistry.release(target, first);
		assertFalse(SalvageMissionRegistry.isSuspended(target));
		assertTrue(SalvageMissionRegistry.reserve(target, second));
	}
}
