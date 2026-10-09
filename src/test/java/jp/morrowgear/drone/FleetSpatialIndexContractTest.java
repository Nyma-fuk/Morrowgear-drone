package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class FleetSpatialIndexContractTest {
	@Test
	void separationUsesTheOwnerTickSpatialRoster() throws Exception {
		String roster = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/MorrowgearDrone.java"));
		String drone = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/DroneEntity.java"));
		assertTrue(roster.contains("Map<SpatialCell, List<DroneEntity>> spatial"));
		assertTrue(drone.contains("MorrowgearDrone.ownedNearbyDrones(level, ownerId(), area)"));
	}
}
