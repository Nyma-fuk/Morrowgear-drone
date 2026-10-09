package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class FleetRosterIndexContractTest {
	@Test void missionAndPatrolLookupsUseTheOncePerTickOwnerRoster() throws Exception {
		String registry = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/MorrowgearDrone.java"));
		assertTrue(registry.contains("ownedMissionDrones"));
		assertTrue(registry.contains("ownedSecurityPatrolDrones"));
		assertTrue(registry.contains("Map<MissionWingKey, List<DroneEntity>> missions"));
		String entity = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/DroneEntity.java"));
		String mission = entity.substring(entity.indexOf("private List<DroneEntity> allAssignedMissionMembers"),
			entity.indexOf("int assignedMissionSizeForVerification"));
		assertTrue(mission.contains("ownedMissionDrones"));
		assertFalse(mission.contains("ownedDrones(level, owner, 512).stream()"));
		String patrol = entity.substring(entity.indexOf("private Vec3 securityTargetPosition"),
			entity.indexOf("private double ensureFollowOrbitPhase"));
		assertTrue(patrol.contains("ownedSecurityPatrolDrones"));
		assertFalse(patrol.contains("getEntitiesOfClass"));
	}
}
