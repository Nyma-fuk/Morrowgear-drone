package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import net.minecraft.world.phys.Vec3;

final class MeleeDefenseTest {
	@Test
	void impulseAlwaysPushesAHostileAwayFromTheOwner() {
		Vec3 impulse = MeleeDefense.outwardImpulse(Vec3.ZERO, new Vec3(2, 0, 1));
		assertTrue(impulse.dot(new Vec3(2, 0, 1)) > 0);
		assertEquals(0.08, impulse.y, 0.0001);
	}
}
