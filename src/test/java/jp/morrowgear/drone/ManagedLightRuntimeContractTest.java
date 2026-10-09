package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ManagedLightRuntimeContractTest {
	@Test void emitterPhasesAreStableAndDistributed() {
		UUID one = new UUID(1, 2), two = new UUID(3, 7);
		assertEquals(ManagedLightRuntime.phase(one, 6), ManagedLightRuntime.phase(one, 6));
		assertTrue(ManagedLightRuntime.phase(two, 6) >= 0);
		assertTrue(ManagedLightRuntime.phase(two, 6) < 6);
	}

	@Test void movingLightsUseReferenceCountsInsteadOfNearbyEntityScans() throws Exception {
		String drone = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/DroneEntity.java"));
		String section = drone.substring(drone.indexOf("private void updateDroneLight"),
			drone.indexOf("private DockTarget dockTarget"));
		assertTrue(section.contains("ManagedLightRuntime.acquire"));
		assertTrue(section.contains("ManagedLightRuntime.release"));
		assertFalse(section.contains("getEntitiesOfClass"));
	}
}
