package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class SupplyNetworkSchedulePolicyTest {
	@Test void aNewCargoOrDockWakesTheSchedulerWithoutWaitingForThePeriodicTick() {
		assertTrue(SupplyNetworkSchedulePolicy.shouldEvaluate(7, 6, true));
		assertFalse(SupplyNetworkSchedulePolicy.shouldEvaluate(7, 7, true));
	}

	@Test void periodicEvaluationStillRepairsMissedStateChanges() {
		assertTrue(SupplyNetworkSchedulePolicy.shouldEvaluate(20, 19, false));
		assertFalse(SupplyNetworkSchedulePolicy.shouldEvaluate(21, 20, false));
	}
}
