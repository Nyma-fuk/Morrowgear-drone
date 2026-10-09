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
}
