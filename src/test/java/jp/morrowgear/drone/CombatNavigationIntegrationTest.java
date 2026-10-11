package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class CombatNavigationIntegrationTest {
	private static String entity() throws Exception {
		return Files.readString(Path.of("src/main/java/jp/morrowgear/drone/DroneEntity.java"));
	}

	@Test
	void blockedDedicatedCombatFlightUsesTheSharedTerrainRouter() throws Exception {
		String source = entity();
		String flight = source.substring(source.indexOf("boolean laserOrbit ="),
			source.indexOf("boolean safetyRecovery =", source.indexOf("boolean laserOrbit =")));
		assertTrue(flight.contains("boolean combatRouteRequired = dedicatedCombatFlight"));
		assertTrue(flight.contains("(!dedicatedCombatFlight || combatRouteRequired)"));
		assertTrue(flight.contains("laserIngress || routedFlight || laserIndividualFiringRoute"));
		assertTrue(flight.contains("routedFlight || autocannonIndividualFiringRoute"));
		assertTrue(flight.contains("FlightDynamics.steerRoute"));
	}

	@Test
	void everyCombatStateTransitionInvalidatesThePreviousRoutePlan() throws Exception {
		String source = entity();
		String state = source.substring(source.indexOf("private void setCombatState("),
			source.indexOf("private float laserEmitterVolume", source.indexOf("private void setCombatState(")));
		assertTrue(state.contains("if (state != previous) resetNavigationPlan()"));
		String reset = source.substring(source.indexOf("private void resetNavigationPlan("),
			source.indexOf("private Vec3 strategicNavigationTarget", source.indexOf("private void resetNavigationPlan(")));
		assertTrue(reset.contains("progressSample = null"));
		assertTrue(reset.contains("stalledTicks = 0"));
	}
}
