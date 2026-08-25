package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PowerLossPolicyTest {
	@Test
	void onlyOwnedPowerLostUnitsCanBeRecovered() {
		assertTrue(PowerLossPolicy.manuallyRecoverable(true, true, 3.9));
		assertFalse(PowerLossPolicy.manuallyRecoverable(false, true, 2.0));
		assertFalse(PowerLossPolicy.manuallyRecoverable(true, false, 2.0));
		assertFalse(PowerLossPolicy.manuallyRecoverable(true, true, 4.1));
	}

	@Test
	void salvageReservationIsExclusive() {
		assertTrue(PowerLossPolicy.salvageEligible(true, true, false));
		assertFalse(PowerLossPolicy.salvageEligible(true, true, true));
	}

	@Test
	void fieldEmergencyTransferKeepsDonorReserve() {
		assertTrue(PowerLossPolicy.fieldTransferAllowed(40, 1));
		assertTrue(PowerLossPolicy.fieldTransferAllowed(40, 8));
		assertFalse(PowerLossPolicy.fieldTransferAllowed(18, 1));
		assertFalse(PowerLossPolicy.fieldTransferAllowed(40, 0));
		assertFalse(PowerLossPolicy.fieldTransferAllowed(40, 9));
	}

	@Test
	void disabledAirframeFallsWithHorizontalInertia() {
		Vec3 next = PowerLossPolicy.fallVelocity(new Vec3(0.8, 0.1, -0.4), false);
		assertEquals(0.788, next.x, 0.0001);
		assertEquals(0.055, next.y, 0.0001);
		assertEquals(-0.394, next.z, 0.0001);
	}

	@Test
	void disabledAirframeStopsDescendingOnGround() {
		Vec3 next = PowerLossPolicy.fallVelocity(new Vec3(0.4, -0.7, 0.2), true);
		assertEquals(0.0, next.y, 0.0001);
		assertTrue(next.x > 0.0 && next.x < 0.4);
	}
}
