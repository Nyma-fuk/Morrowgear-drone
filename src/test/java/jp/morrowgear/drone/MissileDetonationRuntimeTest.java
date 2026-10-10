package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class MissileDetonationRuntimeTest {
	@Test
	void synchronizedSalvoIsCappedAndContinuesOnNextTick() {
		MissileDetonationRuntime.TickBudget budget = new MissileDetonationRuntime.TickBudget(8);
		for (int index = 0; index < 8; index++) assertTrue(budget.tryAcquire(100));
		assertFalse(budget.tryAcquire(100));
		assertEquals(8, budget.used());
		assertTrue(budget.tryAcquire(101));
		assertEquals(1, budget.used());
	}

	@Test void heavyBlastBudgetSerializesLargeCarrierExplosions() {
		assertEquals(1, MissileDetonationRuntime.MAX_HEAVY_DETONATIONS_PER_LEVEL_TICK);
		MissileDetonationRuntime.TickBudget budget = new MissileDetonationRuntime.TickBudget(
			MissileDetonationRuntime.MAX_HEAVY_DETONATIONS_PER_LEVEL_TICK);
		assertTrue(budget.tryAcquire(20));
		assertFalse(budget.tryAcquire(20));
		assertTrue(budget.tryAcquire(21));
	}
}
