package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

final class SecurityPatrolPolicyTest {
	@Test
	void patrolSlotsSpreadAroundAnchor() {
		Vec3 anchor = new Vec3(10, 64, 20);
		Vec3 first = SecurityPatrolPolicy.patrolPosition(anchor, 0, 4, 0, 12);
		Vec3 opposite = SecurityPatrolPolicy.patrolPosition(anchor, 2, 4, 0, 12);

		assertTrue(first.distanceTo(opposite) > 14.0);
		assertEquals(72.0, first.y, 0.001);
	}

	@Test
	void interceptLineStaysBetweenAnchorAndThreat() {
		Vec3 anchor = new Vec3(0, 64, 0);
		Vec3 threat = new Vec3(0, 64, 12);
		Vec3 center = SecurityPatrolPolicy.interceptPosition(anchor, threat, 1, 3);

		assertEquals(0.0, center.x, 0.001);
		assertTrue(center.z > anchor.z && center.z < threat.z);
	}
}
