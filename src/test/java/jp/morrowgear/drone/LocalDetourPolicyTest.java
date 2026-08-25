package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class LocalDetourPolicyTest {
	@Test void stableDetourIsHeldWhileTheMissionGoalRemainsContinuous() {
		assertTrue(LocalDetourPolicy.reusable(Vec3.ZERO, new Vec3(3, 1, 0),
			new Vec3(30, 6, 0), new Vec3(32, 6, 1), 12, true));
	}

	@Test void blockedReachedExpiredOrRetaskedDetourIsReplanned() {
		Vec3 detour = new Vec3(3, 1, 0);
		Vec3 goal = new Vec3(30, 6, 0);
		assertFalse(LocalDetourPolicy.reusable(Vec3.ZERO, detour, goal, goal, 12, false));
		assertFalse(LocalDetourPolicy.reusable(new Vec3(2.5, 1, 0), detour, goal, goal, 12, true));
		assertFalse(LocalDetourPolicy.reusable(Vec3.ZERO, detour, goal, goal, 0, true));
		assertFalse(LocalDetourPolicy.reusable(Vec3.ZERO, detour, goal,
			new Vec3(60, 6, 0), 12, true));
	}
}
