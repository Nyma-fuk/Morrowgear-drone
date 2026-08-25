package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class DroneNavigatorStrategicEscapeTest {
	@Test void strategicFanIncludesLongRangeDirectionsAwayFromAnOccludedTarget() {
		Vec3 forward = new Vec3(1, 0, 0);
		List<Vec3> directions = DroneNavigator.strategicEscapeDirections(forward, new Vec3(0, 0, 1));
		assertEquals(3, directions.size());
		assertTrue(directions.stream().allMatch(direction -> direction.dot(forward) < -0.6));
		assertTrue(directions.stream().anyMatch(direction -> direction.z > 0.5));
		assertTrue(directions.stream().anyMatch(direction -> direction.z < -0.5));
	}
}
