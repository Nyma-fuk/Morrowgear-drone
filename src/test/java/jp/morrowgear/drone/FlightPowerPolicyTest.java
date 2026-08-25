package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class FlightPowerPolicyTest {
	@Test
	void efficientReturnCruiseAvoidsCatchUpPowerPenalty() {
		assertEquals(6, FlightPowerPolicy.drain(1.08, false));
		assertEquals(4, FlightPowerPolicy.drain(1.08, true));
		assertEquals(2, FlightPowerPolicy.drain(0.28, true));
	}

	@Test
	void suspendedSalvageLoadAddsAStablePowerCost() {
		assertEquals(4, FlightPowerPolicy.drain(0.28, false, true));
		assertEquals(6, FlightPowerPolicy.drain(0.58, false, true));
		assertEquals(8, FlightPowerPolicy.drain(1.08, false, true));
	}
}
