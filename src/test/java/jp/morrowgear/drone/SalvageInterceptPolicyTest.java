package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class SalvageInterceptPolicyTest {
	private static final Vec3 LOAD = new Vec3(19.5, -62.0, -38.5);

	@Test
	void acceptsPrecisionHoverWithNormalFlightControllerError() {
		assertTrue(SalvageInterceptPolicy.readyToHook(new Vec3(19.62, -58.72, -38.61), LOAD));
	}

	@Test
	void rejectsUnsafeOrMisalignedApproaches() {
		assertFalse(SalvageInterceptPolicy.readyToHook(new Vec3(21.0, -59.0, -38.5), LOAD));
		assertFalse(SalvageInterceptPolicy.readyToHook(new Vec3(19.5, -60.5, -38.5), LOAD));
		assertFalse(SalvageInterceptPolicy.readyToHook(new Vec3(19.5, -57.9, -38.5), LOAD));
	}
}
