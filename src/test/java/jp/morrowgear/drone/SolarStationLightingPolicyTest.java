package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class SolarStationLightingPolicyTest {
	@Test
	void fourSatellitesRemainEvenlyDistributedAroundTheStation() {
		for (int slot = 0; slot < SolarStationLightingPolicy.SATELLITE_COUNT; slot++) {
			Vec3 offset = SolarStationLightingPolicy.orbitOffset(slot, 37.0f);
			assertEquals(SolarStationLightingPolicy.ORBIT_RADIUS,
				Math.hypot(offset.x, offset.z), .0001);
			assertEquals(SolarStationLightingPolicy.HEIGHT_OFFSET, offset.y, .0001);
		}
		Vec3 first = SolarStationLightingPolicy.orbitOffset(0, 0);
		Vec3 opposite = SolarStationLightingPolicy.orbitOffset(2, 0);
		assertEquals(0.0, first.x + opposite.x, .0001);
		assertEquals(0.0, first.z + opposite.z, .0001);
	}

	@Test
	void directAreaThreePointThreeBlocksBelowRetainsAtLeastLightLevelEight() {
		assertTrue(SolarStationLightingPolicy.directLightLowerBound(3.3) >= 8);
		assertEquals(9, SolarStationLightingPolicy.directLightLowerBound(3.3));
	}
}
