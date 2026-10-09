package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class SystemBoundaryConsistencyTest {
	@Test
	void everyTransientMissionUsesTheSharedNonStandbyFallback() throws Exception {
		String source = Files.readString(Path.of(
			"src/main/java/jp/morrowgear/drone/DroneEntity.java")).replace("\r\n", "\n");
		assertTrue(source.contains("applySafeMissionFallback(level, reason, true)"));
		assertTrue(source.contains("applySafeMissionFallback(level, \"SERVICE COMPLETE\", true)"));
		assertTrue(source.contains("applySafeMissionFallback(level, \"POWER RESTORED\", true)"));
		assertTrue(source.contains("applySafeMissionFallback(level, \"RECOVERY CLEAR\", false)"));
		assertTrue(source.contains("applySafeMissionFallback(level,\n\t\t\t\"FIELD COMPLETE\", false)"));
	}

	@Test
	void cabinExteriorSupportIncludesDockReallocationAndWingDetachment() throws Exception {
		String source = Files.readString(Path.of(
			"src/main/java/jp/morrowgear/drone/DroneEntity.java")).replace("\r\n", "\n");
		assertTrue(source.contains("if (ownerSupportsExterior) maintainDockAllocation(level, owner, formation);"));
		assertTrue(source.contains("serviceReturn, solarServiceAssigned(),\n\t\t\tisPowerLost(), carrierService != null"));
	}
}
