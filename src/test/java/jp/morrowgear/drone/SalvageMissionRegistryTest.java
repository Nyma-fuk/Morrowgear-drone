package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SalvageMissionRegistryTest {
	@Test void eitherLoadOrderReclaimsSameReservationWithoutAllowingAnotherResponder() {
		UUID load = UUID.randomUUID(), carrier = UUID.randomUUID(), other = UUID.randomUUID();
		for (boolean loadFirst : new boolean[]{true, false}) {
			SalvageMissionRegistry.clear();
			assertTrue(SalvageMissionRegistry.reserve(load, carrier));
			if (!loadFirst) SalvageMissionRegistry.setSuspended(load, carrier, true);
			assertFalse(SalvageMissionRegistry.reserve(load, other));
			assertTrue(SalvageMissionRegistry.reserve(load, carrier));
			SalvageMissionRegistry.setSuspended(load, carrier, true);
			assertTrue(SalvageMissionRegistry.isSuspended(load));
		}
	}

	@Test void orphanTimeoutAndTargetDestructionReleaseBothReservationAndSuspension() {
		UUID load = UUID.randomUUID(), carrier = UUID.randomUUID();
		SalvageMissionRegistry.reserve(load, carrier);
		SalvageMissionRegistry.setSuspended(load, carrier, true);
		SalvageMissionRegistry.release(load, carrier);
		assertFalse(SalvageMissionRegistry.isReserved(load));
		assertFalse(SalvageMissionRegistry.isSuspended(load));
		SalvageMissionRegistry.reserve(load, carrier);
		SalvageMissionRegistry.setSuspended(load, carrier, true);
		SalvageMissionRegistry.targetDestroyed(load);
		assertFalse(SalvageMissionRegistry.isReserved(load));
		assertFalse(SalvageMissionRegistry.isSuspended(load));
	}
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
