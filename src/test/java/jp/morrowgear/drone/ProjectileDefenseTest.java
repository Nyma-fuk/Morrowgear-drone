package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import net.minecraft.world.phys.Vec3;

final class ProjectileDefenseTest {
	@Test
	void detectsAProjectileCrossingTheShieldDuringTheNextTick() {
		assertTrue(ProjectileDefense.crossesShield(
			new Vec3(0, 1.5, 0), new Vec3(2, 0, 0), new Vec3(1, 1.5, 0), 0.8));
	}

	@Test
	void ignoresAProjectileWhosePathMissesTheShield() {
		assertFalse(ProjectileDefense.crossesShield(
			new Vec3(0, 4, 0), new Vec3(2, 0, 0), new Vec3(1, 1.5, 0), 0.8));
	}

	@Test
	void gravityPredictionFindsAnArrowDroppingTowardThePlayersFeet() {
		ProjectileDefense.TrajectoryPrediction prediction = ProjectileDefense.predictClosestToVerticalTarget(
			new Vec3(0, 3.2, 0), new Vec3(0.8, -0.15, 0),
			6.0, 0.0, 0.0, 1.8, 0.05, 0.99, 20);

		assertTrue(prediction.missDistance() < 0.7);
		assertTrue(prediction.closestPoint().y < 1.2);
	}

	@Test
	void gravityPredictionRejectsAParallelMiss() {
		ProjectileDefense.TrajectoryPrediction prediction = ProjectileDefense.predictClosestToVerticalTarget(
			new Vec3(0, 4.2, 5), new Vec3(0.8, -0.02, 0),
			6.0, 0.0, 0.0, 1.8, 0.05, 0.99, 20);

		assertTrue(prediction.missDistance() > 4.0);
		assertEquals(5.0, prediction.closestPoint().z, 0.0001);
	}
}
