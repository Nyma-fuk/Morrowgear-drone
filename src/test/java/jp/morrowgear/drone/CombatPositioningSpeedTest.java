package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class CombatPositioningSpeedTest {
	@Test void ingressIsFasterThanTheEstablishedWeaponPattern() {
		assertTrue(CombatPolicy.gunFlightSpeed(30, false) > CombatPolicy.gunFlightSpeed(3, false));
		assertTrue(CombatPolicy.laserFlightSpeed(30, true) > CombatPolicy.laserFlightSpeed(3, false));
		assertTrue(CombatPolicy.missileFlightSpeed(30) > CombatPolicy.missileFlightSpeed(3));
	}

	@Test void positioningSpeedsStayInsideTheAirframeEnvelope() {
		assertEquals(1.24, CombatPolicy.gunFlightSpeed(30, false), 1e-9);
		assertEquals(1.16, CombatPolicy.laserFlightSpeed(30, true), 1e-9);
		assertEquals(1.30, CombatPolicy.missileFlightSpeed(30), 1e-9);
		assertTrue(CombatPolicy.gunFlightSpeed(3, true) <= CombatPolicy.GUN_POSITIONING_SPEED);
	}

	@Test void combatEntryAndRejoinDoNotFallBackToSlowArrivalCaps() {
		assertEquals(1.18, CombatPolicy.attackTransitionFlightSpeed(30), 1e-9);
		assertEquals(0.82, CombatPolicy.attackTransitionFlightSpeed(3), 1e-9);
		assertEquals(1.16, CombatPolicy.rejoinFlightSpeed(30), 1e-9);
		assertEquals(0.64, CombatPolicy.rejoinFlightSpeed(3), 1e-9);
		assertTrue(CombatPolicy.rejoinFlightSpeed(3) > FlightDynamics.speedLimit(3, false, false));
	}
}
