package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class PowerLostBeaconLeasePolicyTest {
	@Test
	void heartbeatLeaseExpiresStaleHudIndicatorsWithoutRacingNormalUpdates() {
		long detected = 10_000;
		assertTrue(PowerLostBeaconLeasePolicy.isCurrent(detected, detected));
		assertTrue(PowerLostBeaconLeasePolicy.isCurrent(detected,
			detected + PowerLostBeaconLeasePolicy.HEARTBEAT_TICKS * 2));
		assertTrue(PowerLostBeaconLeasePolicy.isCurrent(detected,
			detected + PowerLostBeaconLeasePolicy.EXPIRY_TICKS));
		assertFalse(PowerLostBeaconLeasePolicy.isCurrent(detected,
			detected + PowerLostBeaconLeasePolicy.EXPIRY_TICKS + 1));
	}

	@Test
	void dimensionClockMovingBackwardsDoesNotPrematurelyExpireAValidBeacon() {
		assertTrue(PowerLostBeaconLeasePolicy.isCurrent(500, 100));
	}
}
