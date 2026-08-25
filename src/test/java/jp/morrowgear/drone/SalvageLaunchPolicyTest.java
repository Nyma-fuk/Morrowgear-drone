package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class SalvageLaunchPolicyTest {
	private static final BlockPos DOCK = new BlockPos(100, 64, 200);

	@Test
	void dockedCarrierClimbsVerticallyBeforeInterceptingTheLoad() {
		assertTrue(SalvageLaunchPolicy.requiresDepartureLane(new Vec3(100.5, 64.29, 200.5), DOCK));
		assertEquals(new Vec3(100.5, 68.8, 200.5), SalvageLaunchPolicy.departureTarget(DOCK));
	}

	@Test
	void carrierLeavesDepartureControlAfterClearingTheDockEnvelope() {
		assertFalse(SalvageLaunchPolicy.requiresDepartureLane(new Vec3(100.5, 67.7, 200.5), DOCK));
		assertFalse(SalvageLaunchPolicy.requiresDepartureLane(new Vec3(100.5, 68.5, 200.5), DOCK));
		assertFalse(SalvageLaunchPolicy.requiresDepartureLane(new Vec3(104.0, 65.0, 200.5), DOCK));
	}
}
