package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class RuntimeCacheLifecycleContractTest {
	@Test
	void lifecycleIsRegisteredAndClearsDerivedCaches() throws Exception {
		String initializer = Files.readString(Path.of(
			"src/main/java/jp/morrowgear/drone/MorrowgearDrone.java"));
		String lifecycle = Files.readString(Path.of(
			"src/main/java/jp/morrowgear/drone/RuntimeCacheLifecycle.java"));
		assertTrue(initializer.contains("RuntimeCacheLifecycle.register()"));
		assertTrue(lifecycle.contains("ServerPlayConnectionEvents.DISCONNECT.register"));
		assertTrue(lifecycle.contains("ServerLifecycleEvents.SERVER_STOPPED.register"));
		assertTrue(lifecycle.contains("MorrowgearDrone.clearDroneRosterCache()"));
		assertTrue(lifecycle.contains("ThreatAssessment.clear()"));
		assertTrue(lifecycle.contains("NavigationEnvironment.clear()"));
		assertTrue(lifecycle.contains("CombatTheaterCoordinator.clear()"));
	}
}
