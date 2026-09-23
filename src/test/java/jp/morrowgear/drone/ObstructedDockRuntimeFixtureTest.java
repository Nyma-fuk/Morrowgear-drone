package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ObstructedDockRuntimeFixtureTest {
	@Test void liveNavigationProbeCannotInheritTheCycledSecurityRole() throws Exception {
		assertEquals(DroneRole.SECURITY, DroneRole.values()[4],
			"the live failure depends on verifier index 4 inheriting SECURITY");
		String verifier = Files.readString(Path.of(
			"src/main/java/jp/morrowgear/drone/MorrowgearRuntimeVerifier.java"));
		String fixture = section(verifier, "private void assignObstructedDockReturn()",
			"private void assertObstructedDockReturn()");
		int reset = fixture.indexOf("reset(drone)");
		int isolate = fixture.indexOf("drone.assignRole(DroneRole.FIELD)");
		int assignDock = fixture.indexOf("drone.assignDock(dock)");
		int startReturn = fixture.indexOf("drone.setMode(DroneMode.DOCK)");
		assertTrue(reset >= 0 && reset < isolate);
		assertTrue(isolate < assignDock && assignDock < startReturn);
	}

	@Test void geometryTimeoutAndDockingAssertionRemainStrict() throws Exception {
		String verifier = Files.readString(Path.of(
			"src/main/java/jp/morrowgear/drone/MorrowgearRuntimeVerifier.java"));
		assertTrue(verifier.contains("new CaseStep(\"dock return with roof and blocked approaches\", 900,"));
		String fixture = section(verifier, "private void assignObstructedDockReturn()",
			"private void assertObstructedDockReturn()");
		assertTrue(fixture.contains("Direction.NORTH, net.minecraft.core.Direction.SOUTH"));
		assertTrue(fixture.contains("Direction.WEST"));
		assertFalse(fixture.contains("Direction.EAST"));
		String assertion = section(verifier, "private void assertObstructedDockReturn()",
			"private void assignObstructedRegroup()");
		assertTrue(assertion.contains("require(docked,"));
		assertTrue(assertion.contains("only open east approach"));
	}

	@Test void liveVerifierReportsNavigationStateWithoutChangingAcceptance() throws Exception {
		String verifier = Files.readString(Path.of(
			"src/main/java/jp/morrowgear/drone/MorrowgearRuntimeVerifier.java"));
		assertTrue(verifier.contains("[MORROWGEAR VERIFY] NAV DIAG"));
		assertTrue(verifier.contains("[MORROWGEAR VERIFY] NAV FINAL dock"));
		assertTrue(verifier.contains("[MORROWGEAR VERIFY] NAV FINAL large"));
		String entity = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/DroneEntity.java"));
		assertTrue(entity.contains("dockRoute=%d/%d"));
		assertTrue(entity.contains("strategicWaypoint=%s"));
	}

	private static String section(String source, String start, String end) {
		int from = source.indexOf(start);
		int to = source.indexOf(end, from + start.length());
		assertTrue(from >= 0 && to > from);
		return source.substring(from, to);
	}
}
