package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class DockFinalApproachIntegrationTest {
	@Test void sideLaneChecksTheOpenCorridorThenDelegatesTheRimToFinalApproach() throws Exception {
		String source = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/DroneEntity.java"));
		String compact = source.replaceAll("\\s+", " ");
		int start = source.indexOf("private DockLane sideDockLane(");
		int end = source.indexOf("private int corridorScore(", start);
		String method = source.substring(start, end);
		int dockTargetStart = source.indexOf("private DockTarget dockTarget(");
		String dockTarget = source.substring(dockTargetStart, start);
		assertTrue(method.contains("DroneNavigator.corridorClear(level, this, outer, gate)"));
		assertFalse(method.contains("DroneNavigator.corridorClear(level, this, gate, landing)"));
		assertTrue(dockTarget.contains("DockApproachPlan.ingressRoute(position(), landing, lane.outer()"));
		assertTrue(dockTarget.contains("atOuterLeg ? 1 : 0"));
		assertTrue(dockTarget.contains("if (stage >= 2)"));
		assertFalse(dockTarget.contains("corridorClear(level, this, position(), landing)"));
		assertTrue(compact.contains("smoothFlightMotion(level, previousVelocity, requestedVelocity, safetyRecovery, finalApproach)"));
		assertTrue(source.contains("FlightDynamics.landingSweep(getBoundingBox(), displacement)"));
	}
}
