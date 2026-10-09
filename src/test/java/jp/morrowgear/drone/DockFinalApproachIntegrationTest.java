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
		assertTrue(compact.contains("smoothFlightMotion(level, previousVelocity, requestedVelocity, safetyRecovery, dockCollisionSweep)"));
		assertTrue(source.contains("FlightDynamics.landingSweep(getBoundingBox(), displacement)"));
	}

	@Test void validatedSideGateAlignmentCannotBeReplacedByGenericRecoveryRouting() throws Exception {
		String source = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/DroneEntity.java"));
		int flightStart = source.indexOf("boolean dockLaneAlignment =");
		int flightEnd = source.indexOf("boolean safetyRecovery =", flightStart);
		String flight = source.substring(flightStart, flightEnd);
		assertTrue(flight.contains("entityData.get(DOCK_STAGE) == 1"));
		assertTrue(flight.contains("!dockLaneAlignment"));
		assertTrue(flight.contains("dockDeparture || dockLaneAlignment"));
		assertTrue(flight.contains("boolean dockCollisionSweep = finalApproach || dockLaneAlignment"));
		assertTrue(source.contains("safetyRecovery, dockCollisionSweep"));

		int transitionStart = source.indexOf("private void advanceDockStage(");
		int transitionEnd = source.indexOf("private Vec3 strategicNavigationTarget", transitionStart);
		String transition = source.substring(transitionStart, transitionEnd);
		assertTrue(transition.contains("resetNavigationPlan()"));
		assertTrue(transition.contains("recoveryAttempts = 0"));
		assertTrue(transition.contains("RECOVERY_LEVEL, 0"));
		assertTrue(transition.contains("RECOVERY_SINCE, -1L"));
	}

	@Test void visualThreeBlockEnvelopeKeepsAUsableServiceLaneCollisionMargin() throws Exception {
		String registration = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/MorrowgearDrone.java"));
		assertTrue(registration.contains(".sized(2.8f, 0.75f)"));
		assertTrue(registration.contains("rendered rotor envelope remains 3x3"));
	}
}
