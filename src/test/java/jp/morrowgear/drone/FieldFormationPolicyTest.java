package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class FieldFormationPolicyTest {
	@Test
	void orbitDistributesEveryRolePeerAcrossTheWorkArc() {
		Vec3 center = new Vec3(10, 64, -8);
		Set<Vec3> slots = new HashSet<>();
		for (int slot = 0; slot < 8; slot++) {
			Vec3 target = FieldFormationPolicy.orbit(center, slot, 8, 12, 6, 200, 0.03, 0);
			slots.add(target);
			assertEquals(12.0, target.multiply(1, 0, 1)
				.distanceTo(center.multiply(1, 0, 1)), 0.0001);
			assertEquals(72.0, target.y, 0.0001);
		}
		assertEquals(8, slots.size());
	}

	@Test
	void escortMovesWithCargoWithoutCollapsingSlots() {
		Vec3 firstLeader = new Vec3(0, 70, 0);
		Vec3 movedLeader = new Vec3(15, 72, -4);
		Vec3 first = FieldFormationPolicy.escort(firstLeader, 0, 3, 3, 2.5, 100, 0);
		Vec3 moved = FieldFormationPolicy.escort(movedLeader, 0, 3, 3, 2.5, 100, 0);
		assertTrue(first.distanceTo(FieldFormationPolicy.escort(firstLeader, 1, 3, 3, 2.5, 100, 0)) > 3);
		assertEquals(movedLeader.subtract(firstLeader), moved.subtract(first));
	}
}
