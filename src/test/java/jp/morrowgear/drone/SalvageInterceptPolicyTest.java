package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class SalvageInterceptPolicyTest {
	private static final Vec3 LOAD = new Vec3(19.5, -62.0, -38.5);

	@Test
	void serviceReturnAndDockApproachAlwaysPreemptInterceptNavigation() {
		assertTrue(SalvageInterceptPolicy.controlsNavigation(
			SalvageState.INTERCEPT, true, false, false));
		assertFalse(SalvageInterceptPolicy.controlsNavigation(
			SalvageState.INTERCEPT, true, true, true));
		assertFalse(SalvageInterceptPolicy.controlsNavigation(
			SalvageState.INTERCEPT, true, false, true));
		assertFalse(SalvageInterceptPolicy.controlsNavigation(
			SalvageState.INTERCEPT, false, false, false));
	}

	@Test
	void acceptsPrecisionHoverWithNormalFlightControllerError() {
		assertTrue(SalvageInterceptPolicy.readyToHook(new Vec3(19.62, -58.72, -38.61), LOAD, 1.2));
		assertTrue(SalvageInterceptPolicy.readyToHook(
			SalvageInterceptPolicy.approachPosition(LOAD, 1.2), LOAD, 1.2));
	}

	@Test
	void rejectsUnsafeOrMisalignedApproaches() {
		assertFalse(SalvageInterceptPolicy.readyToHook(new Vec3(22.0, -59.0, -38.5), LOAD, 1.2));
		assertFalse(SalvageInterceptPolicy.readyToHook(new Vec3(19.5, -60.5, -38.5), LOAD, 1.2));
		assertFalse(SalvageInterceptPolicy.readyToHook(new Vec3(19.5, -55.5, -38.5), LOAD, 1.2));
	}
}
