package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class SalvagePowerRestorePolicyTest {
	@Test void releasedSalvageTaskCanImmediatelySelectAnUnreservedPowerLostLoad() {
		assertTrue(PowerLossPolicy.salvageEligible(true, true, false));
	}
}
