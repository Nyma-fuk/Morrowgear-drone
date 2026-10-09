package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ThreatSensorRuntimeContractTest {
	@Test void manyAircraftShareOneOwnerWorldScanPerTick() {
		assertEquals(1, ThreatSensorRuntime.worldScansForTest(64));
		assertEquals(1, ThreatSensorRuntime.worldScansForTest(4, 64));
		assertEquals(0, ThreatSensorRuntime.worldScansForTest(0));
	}

	@Test void productionThreatPublishersUseTheSharedIndex() throws Exception {
		String source = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/DroneEntity.java"));
		String section = source.substring(source.indexOf("private void tickSecurityPatrol"),
			source.indexOf("private void updateEmergencyInterception"));
		assertTrue(section.contains("ThreatSensorRuntime.engageable"));
		assertTrue(section.contains("ThreatSensorRuntime.vanillaEnemies"));
		assertFalse(section.contains("getEntitiesOfClass(LivingEntity.class"));
	}
}
