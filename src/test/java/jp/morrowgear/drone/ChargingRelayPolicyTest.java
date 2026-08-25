package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import net.minecraft.world.phys.Vec3;

final class ChargingRelayPolicyTest {
	@Test
	void stowedRelayLocksToTheRotatingBayWithoutOscillation() {
		assertTrue(ChargingRelayPolicy.lockToBay(false, true, 1.2));
		assertTrue(ChargingRelayPolicy.lockToBay(false, false, .39));
		assertFalse(ChargingRelayPolicy.lockToBay(false, false, .41));
		assertFalse(ChargingRelayPolicy.lockToBay(true, true, 0.0));
	}

	@Test
	void beamAppearsAtTheChargingEnvelopeBeforePowerTransfer() {
		assertTrue(ChargingRelayPolicy.showBeam(true, 2.4));
		assertFalse(ChargingRelayPolicy.showBeam(true, 4.6));
		assertFalse(ChargingRelayPolicy.showBeam(false, 2.4));
		assertTrue(ChargingRelayPolicy.beamStable(4));
		assertFalse(ChargingRelayPolicy.transferThisTick(11, 20));
		assertTrue(ChargingRelayPolicy.transferThisTick(12, 20));
	}

	@Test
	void wirelessDockChargesAtHalfTheNormalDockRate() {
		assertEquals(.5, ChargingRelayPolicy.relativeChargeRate(), .0001);
	}

	@Test
	void relayMatchesDroneVelocityInsteadOfOscillatingAroundTheMovingSlot() {
		Vec3 targetVelocity = new Vec3(.18, 0, .08);
		Vec3 first = ChargingRelayPolicy.followVelocity(Vec3.ZERO, Vec3.ZERO,
			new Vec3(0, 2.35, 0), targetVelocity, .56);
		Vec3 second = ChargingRelayPolicy.followVelocity(first, first,
			new Vec3(.18, 2.35, .08), targetVelocity, .56);
		assertTrue(first.length() <= .0651);
		assertTrue(second.subtract(first).length() <= .0651);
		assertTrue(second.dot(targetVelocity) > 0.0);
	}
}
