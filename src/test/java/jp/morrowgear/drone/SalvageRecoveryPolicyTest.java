package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class SalvageRecoveryPolicyTest {
	@Test void recoveryServiceContinuesUntilTheRestoredAircraftCanLaunch() {
		assertTrue(SalvageRecoveryPolicy.serviceTickRequired(SalvageState.DELIVER, true));
		assertTrue(SalvageRecoveryPolicy.serviceTickRequired(SalvageState.SERVICE, true));
		assertFalse(SalvageRecoveryPolicy.serviceTickRequired(SalvageState.RETURN, true));
		assertFalse(SalvageRecoveryPolicy.serviceTickRequired(SalvageState.SERVICE, false));
	}

	@Test void requiresFlightHealthAndAllSubsystemsBeforeRelease() {
		BatteryTier tier = BatteryTier.STANDARD;
		int flight = SalvageRecoveryPolicy.flightTarget(tier);
		assertTrue(SalvageRecoveryPolicy.ready(flight, tier, 12.0f, 20.0f, 600, 600, 600));
		assertFalse(SalvageRecoveryPolicy.ready(flight - 1, tier, 12.0f, 20.0f, 600, 600, 600));
		assertFalse(SalvageRecoveryPolicy.ready(flight, tier, 11.9f, 20.0f, 600, 600, 600));
		assertFalse(SalvageRecoveryPolicy.ready(flight, tier, 12.0f, 20.0f, 599, 600, 600));
		assertFalse(SalvageRecoveryPolicy.ready(flight, tier, 12.0f, 20.0f, 600, 599, 600));
		assertFalse(SalvageRecoveryPolicy.ready(flight, tier, 12.0f, 20.0f, 600, 600, 599));
	}

	@Test void readinessScalesWithInstalledBatteryTier() {
		for (BatteryTier tier : BatteryTier.values()) {
			int target = SalvageRecoveryPolicy.flightTarget(tier);
			assertTrue(target > 0);
			assertTrue(SalvageRecoveryPolicy.ready(target, tier, 20.0f, 20.0f, 1000, 1000, 1000));
		}
	}
}
