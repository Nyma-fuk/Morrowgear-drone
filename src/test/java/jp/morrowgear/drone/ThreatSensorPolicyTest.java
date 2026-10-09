package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ThreatSensorPolicyTest {
	@Test void discoveryVolumesAndReportLifetimeRemainExplicit() {
		assertEquals(24, ThreatSensorPolicy.OWNER_GUARD_RADIUS);
		assertEquals(24, ThreatSensorPolicy.LOCAL_SECURITY_RADIUS);
		assertEquals(64, ThreatSensorPolicy.SCOUT_RADIUS);
		assertEquals(32, ThreatSensorPolicy.SCOUT_VERTICAL);
		assertEquals(64, ThreatSensorPolicy.MAX_PATROL_RADIUS);
		assertEquals(120, ThreatSensorPolicy.REPORT_TTL_TICKS);
	}

	@Test void dataLinkHasNoDistanceFilterButRequiresOwnerDimensionAndLiveLoadedTarget() throws Exception {
		String network = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/FleetThreatNetwork.java"));
		String coordinator = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/CombatTheaterCoordinator.java"));
		assertTrue(network.contains("new Key(dimension, owner)"));
		assertFalse(network.contains("distanceTo"));
		assertTrue(coordinator.contains("level.getEntity(report.entityId())"));
		assertTrue(coordinator.contains("living.isAlive()"));
	}
}
